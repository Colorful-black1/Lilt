//! Versioned, local-only backup files.
//!
//! A backup contains user-owned state only: settings, playlists, queues,
//! favourites, play counts, bookmarks, lyrics, tag overrides and EQ profiles.
//! The scanned music index is deliberately excluded; it is rebuilt from the
//! files on disk after a restore.

use crate::model::{
    now_secs, EqProfile, Persisted, Playlist, Queue, Settings, SongExtra, FADE_MAX_MS,
};
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};
use std::path::Path;

pub const BACKUP_APP: &str = "lilt";
pub const BACKUP_FORMAT: u32 = 1;
pub const BACKUP_PLATFORM: &str = "pc";

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default)]
pub struct BackupFile {
    pub app: String,
    pub format: u32,
    pub platform: String,
    pub created: u64,
    pub data: BackupData,
}

impl Default for BackupFile {
    fn default() -> Self {
        Self {
            app: BACKUP_APP.to_owned(),
            format: BACKUP_FORMAT,
            platform: BACKUP_PLATFORM.to_owned(),
            created: now_secs(),
            data: BackupData::default(),
        }
    }
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct BackupData {
    pub settings: Settings,
    pub library_roots: Vec<String>,
    pub excluded: Vec<String>,
    pub included: Vec<String>,
    pub playlists: Vec<Playlist>,
    pub queues: Vec<Queue>,
    pub active_queue: u64,
    pub songs: HashMap<String, SongExtra>,
    pub eq_profiles: HashMap<String, EqProfile>,
    pub next_id: u64,
    pub recent_searches: Vec<String>,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct RestoreSummary {
    pub playlists: usize,
    pub queues: usize,
    pub song_states: usize,
    pub favorites: usize,
    pub lyrics: usize,
    pub bookmarks: usize,
    pub eq_profiles: usize,
}

impl RestoreSummary {
    pub fn merge(&mut self, other: &RestoreSummary) {
        self.playlists += other.playlists;
        self.queues += other.queues;
        self.song_states += other.song_states;
        self.favorites += other.favorites;
        self.lyrics += other.lyrics;
        self.bookmarks += other.bookmarks;
        self.eq_profiles += other.eq_profiles;
    }
}

/// Builds a backup from the current in-memory state. Call `save_now` first so
/// the last playback position is included.
pub fn make_backup(state: &Persisted) -> BackupFile {
    BackupFile {
        app: BACKUP_APP.to_owned(),
        format: BACKUP_FORMAT,
        platform: BACKUP_PLATFORM.to_owned(),
        created: now_secs(),
        data: BackupData {
            settings: state.settings.clone(),
            library_roots: state.library_roots.clone(),
            excluded: state.excluded.clone(),
            included: state.included.clone(),
            playlists: state.playlists.clone(),
            queues: state.queues.clone(),
            active_queue: state.active_queue,
            songs: state.songs.clone(),
            eq_profiles: state.eq_profiles.clone(),
            next_id: state.next_id,
            recent_searches: state.recent_searches.clone(),
        },
    }
}

pub fn to_json(backup: &BackupFile) -> Result<String, String> {
    serde_json::to_string_pretty(backup).map_err(|error| error.to_string())
}

pub fn from_json(text: &str) -> Result<BackupFile, String> {
    let mut backup: BackupFile = serde_json::from_str(text)
        .map_err(|error| format!("This is not a Lilt backup: {error}"))?;
    if backup.app != BACKUP_APP {
        return Err("This file was not created by Lilt.".to_owned());
    }
    if backup.format != BACKUP_FORMAT {
        return Err(format!(
            "Backup format {} is not supported by this build (expected {}).",
            backup.format, BACKUP_FORMAT
        ));
    }
    if backup.platform != BACKUP_PLATFORM {
        return Err(
            "This backup was created on Android. Restore it in Lilt for Android.".to_owned(),
        );
    }
    backup.data.settings.sanitize();
    backup
        .data
        .playlists
        .retain(|playlist| !playlist.name.trim().is_empty());
    backup
        .data
        .queues
        .retain(|queue| !queue.name.trim().is_empty());
    normalize_ids(&mut backup.data);
    Ok(backup)
}

pub fn read_file(path: &Path) -> Result<BackupFile, String> {
    let text = std::fs::read_to_string(path)
        .map_err(|error| format!("Could not read the backup: {error}"))?;
    from_json(&text)
}

/// Applies user-owned data in place. Scanned songs are not touched; the app
/// starts a rescan afterwards so playlist keys resolve against real files.
pub fn apply_backup(
    state: &mut Persisted,
    backup: BackupFile,
    current_roots: &[String],
) -> RestoreSummary {
    let data = backup.data;
    let mut summary = RestoreSummary::default();

    state.settings = data.settings;
    state.settings.sanitize();

    // Keep roots from the backup only when they still exist. If a backup came
    // from another computer, the current roots are a safer fallback.
    let restored_roots: Vec<String> = data
        .library_roots
        .into_iter()
        .filter(|root| Path::new(root).is_dir())
        .collect();
    if !restored_roots.is_empty() {
        state.library_roots = restored_roots;
    } else if state.library_roots.is_empty() {
        state.library_roots = current_roots.to_vec();
    }
    state.excluded = data.excluded;
    state.included = data.included;
    state.playlists = data.playlists;
    state.queues = data.queues;
    state.active_queue = data.active_queue;
    state.songs = data.songs;
    state.eq_profiles = data.eq_profiles;
    state.next_id = data.next_id.max(state.next_id);
    state.recent_searches = data.recent_searches;

    summary.playlists = state.playlists.len();
    summary.queues = state.queues.len();
    summary.eq_profiles = state.eq_profiles.len();
    for extra in state.songs.values() {
        if extra.favorite {
            summary.favorites += 1;
        }
        if extra.play_count > 0 || extra.last_played.is_some() || extra.last_position > 0.0 {
            summary.song_states += 1;
        }
        if extra.lyrics.is_some() {
            summary.lyrics += 1;
        }
        summary.bookmarks += extra.bookmarks.len();
    }
    summary
}

/// Reassigns colliding ids in a hand-edited file and keeps the active queue
/// pointing at a real queue.
pub fn normalize_ids(data: &mut BackupData) {
    let mut used: HashSet<u64> = HashSet::new();
    let mut next = data.next_id.max(1);
    for playlist in &mut data.playlists {
        if playlist.id == 0 || !used.insert(playlist.id) {
            while used.contains(&next) {
                next += 1;
            }
            playlist.id = next;
            used.insert(playlist.id);
            next += 1;
        }
        playlist.name = playlist.name.trim().to_owned();
        playlist.updated = playlist.updated.max(playlist.created);
    }
    let mut used_queues: HashSet<u64> = HashSet::new();
    for queue in &mut data.queues {
        if queue.id == 0 || !used_queues.insert(queue.id) {
            while used_queues.contains(&next) || used.contains(&next) {
                next += 1;
            }
            queue.id = next;
            used_queues.insert(queue.id);
            next += 1;
        }
        if queue.index >= queue.songs.len() {
            queue.index = 0;
        }
        if !queue.position.is_finite() || queue.position < 0.0 {
            queue.position = 0.0;
        }
        queue.name = queue.name.trim().to_owned();
    }
    if !data
        .queues
        .iter()
        .any(|queue| queue.id == data.active_queue)
    {
        data.active_queue = data.queues.first().map(|queue| queue.id).unwrap_or(0);
    }
    data.next_id = data
        .next_id
        .max(next)
        .max(
            data.playlists
                .iter()
                .map(|playlist| playlist.id)
                .max()
                .unwrap_or(0)
                + 1,
        )
        .max(data.queues.iter().map(|queue| queue.id).max().unwrap_or(0) + 1);
}

/// Sanitizes values a hand-edited or old backup could contain.
impl Settings {
    pub fn sanitize(&mut self) {
        self.volume = finite_range(self.volume, 0.0, 1.0, 0.8);
        self.playback_speed = finite_range(self.playback_speed, 0.5, 3.0, 1.0);
        self.lyrics_font_size = finite_range(self.lyrics_font_size, 10.0, 24.0, 13.0);
        self.lyrics_opacity = self.lyrics_opacity.clamp(30, 100);
        self.fade_in_ms = self.fade_in_ms.min(FADE_MAX_MS);
        self.fade_out_ms = self.fade_out_ms.min(FADE_MAX_MS);
        self.crossfade_seconds = finite_range(self.crossfade_seconds, 0.0, 12.0, 0.0);
        self.min_play_percent = self.min_play_percent.min(100);
        self.desktop_lyrics_font_size =
            finite_range(self.desktop_lyrics_font_size, 18.0, 56.0, 34.0);
        self.day_start_minutes = self.day_start_minutes.min(1439);
        self.day_end_minutes = self.day_end_minutes.min(1439);
        self.min_duration_seconds = self.min_duration_seconds.min(3600);
        self.sleep_history_hm.truncate(5);
        self.sleep_history_n.truncate(5);
    }
}

fn finite_range(value: f32, min: f32, max: f32, fallback: f32) -> f32 {
    if value.is_finite() {
        value.clamp(min, max)
    } else {
        fallback
    }
}

/// A stable count used by the confirmation dialog before data is replaced.
pub fn describe(data: &BackupData) -> String {
    let favorites = data.songs.values().filter(|extra| extra.favorite).count();
    let lyrics = data
        .songs
        .values()
        .filter(|extra| extra.lyrics.is_some())
        .count();
    let bookmarks: usize = data
        .songs
        .values()
        .map(|extra: &SongExtra| extra.bookmarks.len())
        .sum();
    format!(
        "{}, {}, {}, {}, {}",
        count_label(data.playlists.len(), "playlist", "playlists"),
        count_label(data.queues.len(), "queue", "queues"),
        count_label(favorites, "favourite", "favourites"),
        count_label(lyrics, "lyric file", "lyric files"),
        count_label(bookmarks, "bookmark", "bookmarks"),
    )
}

fn count_label(count: usize, one: &str, many: &str) -> String {
    format!("{count} {}", if count == 1 { one } else { many })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::{Bookmark, SongExtra};

    fn sample_state() -> Persisted {
        let mut state = Persisted::default();
        state.settings.volume = 0.42;
        state.settings.fade_in_ms = 750;
        state.playlists.push(Playlist {
            id: 7,
            name: "Night list".to_owned(),
            songs: vec!["a.mp3".to_owned(), "b.mp3".to_owned()],
            created: 10,
            updated: 20,
        });
        state.queues.push(Queue {
            id: 9,
            name: "Evening".to_owned(),
            songs: vec!["b.mp3".to_owned()],
            index: 0,
            position: 12.5,
            updated: 30,
        });
        state.active_queue = 9;
        state.next_id = 10;
        state.songs.insert(
            "a.mp3".to_owned(),
            SongExtra {
                favorite: true,
                play_count: 3,
                lyrics: Some("[00:01.00]Line".to_owned()),
                bookmarks: vec![Bookmark {
                    position: 4.0,
                    note: "Chorus".to_owned(),
                    created: 1,
                }],
                ..SongExtra::default()
            },
        );
        state
    }

    #[test]
    fn backup_round_trip_keeps_user_data() {
        let state = sample_state();
        let text = to_json(&make_backup(&state)).unwrap();
        let parsed = from_json(&text).unwrap();
        assert_eq!(parsed.data.settings.volume, 0.42);
        assert_eq!(parsed.data.playlists.len(), 1);
        assert_eq!(parsed.data.playlists[0].songs, vec!["a.mp3", "b.mp3"]);
        assert_eq!(parsed.data.queues[0].position, 12.5);
        assert!(parsed.data.songs["a.mp3"].favorite);
        assert_eq!(parsed.data.songs["a.mp3"].bookmarks[0].note, "Chorus");
        let mut restored = Persisted::default();
        let summary = apply_backup(&mut restored, parsed, &[]);
        assert_eq!(summary.playlists, 1);
        assert_eq!(summary.favorites, 1);
        assert_eq!(summary.lyrics, 1);
        assert_eq!(summary.bookmarks, 1);
        assert_eq!(restored.settings.fade_in_ms, 750);
        assert_eq!(restored.active_queue, 9);
    }

    #[test]
    fn backup_rejects_other_platforms_and_versions() {
        let mut backup = make_backup(&sample_state());
        backup.platform = "android".to_owned();
        let text = serde_json::to_string(&backup).unwrap();
        assert!(from_json(&text).unwrap_err().contains("Android"));

        backup.platform = "pc".to_owned();
        backup.format = 99;
        let text = serde_json::to_string(&backup).unwrap();
        assert!(from_json(&text).unwrap_err().contains("format 99"));
    }

    #[test]
    fn backup_sanitizes_hand_edited_values() {
        let mut backup = make_backup(&sample_state());
        backup.data.settings.volume = 7.0;
        backup.data.settings.playback_speed = 99.0;
        backup.data.settings.lyrics_font_size = 900.0;
        backup.data.settings.fade_out_ms = 99_000;
        backup.data.settings.min_play_percent = 999;
        let text = serde_json::to_string(&backup).unwrap();
        let parsed = from_json(&text).unwrap();
        assert_eq!(parsed.data.settings.volume, 1.0);
        assert_eq!(parsed.data.settings.playback_speed, 3.0);
        assert_eq!(parsed.data.settings.lyrics_font_size, 24.0);
        assert_eq!(parsed.data.settings.fade_out_ms, FADE_MAX_MS);
        assert_eq!(parsed.data.settings.min_play_percent, 100);
    }

    #[test]
    fn duplicate_ids_are_reassigned_and_active_queue_stays_valid() {
        let mut data = make_backup(&sample_state()).data;
        data.playlists.push(Playlist {
            id: 7,
            name: "Second".to_owned(),
            songs: Vec::new(),
            created: 1,
            updated: 1,
        });
        data.queues.push(Queue {
            id: 9,
            name: "Duplicate".to_owned(),
            songs: Vec::new(),
            index: 3,
            position: -4.0,
            updated: 1,
        });
        data.active_queue = 9;
        normalize_ids(&mut data);
        assert_eq!(data.playlists[0].id, 7);
        assert_ne!(data.playlists[1].id, 7);
        assert_ne!(data.queues[0].id, data.queues[1].id);
        assert_eq!(data.queues[1].index, 0);
        assert_eq!(data.queues[1].position, 0.0);
        assert!(data
            .queues
            .iter()
            .any(|queue| queue.id == data.active_queue));
        assert!(data.next_id > data.queues[1].id);
    }

    #[test]
    fn restore_keeps_existing_roots_when_backup_paths_are_missing() {
        let mut state = Persisted::default();
        state.library_roots = vec!["E:\\Music".to_owned()];
        let mut backup = make_backup(&sample_state());
        backup.data.library_roots = vec!["Z:\\No such folder".to_owned()];
        let summary = apply_backup(&mut state, backup, &["E:\\Music".to_owned()]);
        assert_eq!(state.library_roots, vec!["E:\\Music"]);
        assert_eq!(summary.playlists, 1);
    }

    #[test]
    fn describes_the_most_important_counts() {
        let data = make_backup(&sample_state()).data;
        let text = describe(&data);
        assert!(text.contains("1 playlist"));
        assert!(text.contains("1 queue"));
        assert!(text.contains("1 favourite"));
        assert!(text.contains("1 lyric file"));
        assert!(text.contains("1 bookmark"));
    }
}
