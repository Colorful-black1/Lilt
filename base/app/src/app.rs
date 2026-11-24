use crate::audio::AudioEngine;
use crate::backup;
use crate::desktop::{self, DesktopAction, DesktopIntegration, MediaInfo};
use crate::duplicates;
use crate::i18n::tr;
use crate::library;
use crate::lyrics_fetch::{
    self, FetchCache, FetchCommand, FetchEvent, LyricsCandidate, LyricsFetcher,
};
use crate::model::*;
use crate::secret;
use crate::theme::{self, Palette};
use crate::ui::companion::{CompanionAction, CompanionSnapshot, CompanionWindows};
use eframe::egui;
use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::mpsc::{channel, Receiver};
use std::sync::{Arc, RwLock};
use std::time::{Duration, Instant};

#[derive(Clone, Debug)]
pub struct Toast {
    pub text: String,
    pub born: Instant,
}

#[derive(Clone, Debug)]
pub enum ConfirmAction {
    DeletePlaylist(u64),
    DeleteQueue(u64),
    ClearQueue(u64),
    RemoveLibraryRoot(String),
    RemoveExcluded(String),
    RemoveIncluded(String),
    ClearHistory,
    RestoreBackup(String),
    ResetStarter,
}

#[derive(Clone, Debug)]
pub struct TagEditorState {
    pub keys: Vec<String>,
    pub title: String,
    pub artist: String,
    pub album: String,
    pub genre: String,
    pub year: String,
    pub track: String,
    pub cover: Option<String>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SleepMode {
    AfterTime,
    AfterSongs,
}

/// Per-song state of the online lyric lookup. Only the current song's entry is
/// shown; keeping the map lets a lookup finish after the user has moved on.
#[derive(Clone, Debug, PartialEq)]
pub enum LyricsStatus {
    Idle,
    Fetching,
    AiFetching,
    NotFound,
    Instrumental,
    Ambiguous(usize),
    Error(String),
}

#[derive(Clone, Debug)]
pub enum Modal {
    NewPlaylist {
        name: String,
        songs: Vec<String>,
    },
    RenamePlaylist {
        id: u64,
        name: String,
    },
    NewQueue {
        name: String,
        songs: Vec<String>,
    },
    RenameQueue {
        id: u64,
        name: String,
    },
    AddToQueue {
        keys: Vec<String>,
        queue_id: Option<u64>,
        new_name: String,
    },
    AddToPlaylist {
        keys: Vec<String>,
        playlist_id: Option<u64>,
        new_name: String,
    },
    LoadQueueFromPlaylist {
        queue_id: u64,
    },
    Bookmark {
        key: String,
        note: String,
    },
    Speed {
        value: f32,
    },
    TagEditor(TagEditorState),
    LyricsEditor {
        key: String,
        text: String,
    },
    AiLyricsPreview {
        key: String,
        title: String,
        provider: String,
        text: String,
    },
    LyricsCandidates {
        key: String,
        title: String,
        candidates: Vec<LyricsCandidate>,
        selected: usize,
    },
    SleepTimer {
        hours: u32,
        minutes: u32,
        songs: u32,
        mode: SleepMode,
        wait_till_end: bool,
        fade: bool,
    },
    Confirm {
        title: String,
        body: String,
        action: ConfirmAction,
    },
    About,
    Shortcuts,
    MissingFiles(Vec<MissingFile>),
    /// Each group is one suspected duplicate set, best copy first.
    Duplicates(Vec<Vec<Song>>),
}

#[derive(Clone, Debug)]
pub struct AlbumEntry {
    pub name: String,
    pub artist: String,
    pub songs: Vec<usize>,
    pub duration: f64,
}

#[derive(Clone, Debug)]
pub struct ArtistEntry {
    pub name: String,
    pub songs: Vec<usize>,
    pub album_count: usize,
}

#[derive(Clone, Debug)]
pub enum ScanEvent {
    Progress(usize, String),
    Done(Vec<Song>, library::ScanStats),
    Failed(String),
}

/// A running play/pause fade. The target runs these on short-lived threads
/// (`vb`/`ub`) with a 100 ms tick and integer volume steps 0…100.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum FadeKind {
    In,
    Out,
}

/// Two playback markers. `a` is always set first; `b` switches the loop on.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct AbLoop {
    pub a: f64,
    pub b: Option<f64>,
}

#[derive(Clone, Debug)]
pub struct MissingFile {
    pub key: String,
    pub title: String,
    pub path: String,
    pub playlists: Vec<String>,
    pub queues: Vec<String>,
    pub favorite: bool,
    pub lyrics: bool,
    pub bookmarks: usize,
    pub plays: u32,
}

#[derive(Clone, Copy, Debug)]
struct PlayFade {
    started: Instant,
    duration_ms: u32,
    kind: FadeKind,
    /// Player volume when the fade began, 0…1 (`q60.E()` before the ramp).
    start_volume: f32,
}

pub struct LiltApp {
    pub state: Persisted,
    pub songs: Vec<Song>,
    pub folders: FolderIndex,
    pub albums: Vec<AlbumEntry>,
    pub artists: Vec<ArtistEntry>,
    pub scan_rx: Option<Receiver<ScanEvent>>,
    pub scanning: bool,
    pub scan_seen: usize,
    pub scan_path: String,
    pub last_scan: Option<u64>,
    pub scan_error: Option<String>,

    pub page: Page,
    pub detail: Option<DetailPage>,
    pub folder_path: Option<PathBuf>,
    pub home_tab: HomeTab,
    pub folder_layout: FolderLayout,
    pub sort: SortMode,
    pub sort_desc: bool,
    pub search: String,
    pub search_focus: bool,
    pub selected: HashSet<String>,
    pub selection_mode: bool,
    pub jump_letter: Option<char>,
    pub favorites_only: bool,

    pub current_key: Option<String>,
    pub playing: bool,
    /// `k_b_fdid` / `k_b_fdod` fade currently running.
    fade: Option<PlayFade>,
    pub shuffle: bool,
    pub repeat: RepeatMode,
    pub pending_next: Option<usize>,
    pub counted_current: bool,
    pub ab_loop: Option<AbLoop>,
    /// One-shot "pause at the end of the current track" flag.
    pub stop_after_current: bool,
    pub lyrics: Option<Vec<(f64, String)>>,
    pub lyrics_for: Option<String>,
    pub lyrics_fetcher: LyricsFetcher,
    pub lyrics_fetch_cache: FetchCache,
    pub lyrics_status: HashMap<String, LyricsStatus>,
    pub lyrics_candidates: HashMap<String, Vec<LyricsCandidate>>,
    pub lyrics_candidate_request: Option<String>,
    pub lyrics_inflight: HashSet<String>,
    pub ai_key_saved: bool,
    pub ai_key_input: String,
    pub ai_models: Vec<String>,
    pub ai_models_loading: bool,
    pub ai_testing: bool,
    pub ai_status: Option<(bool, String)>,

    pub audio: Option<AudioEngine>,
    pub output_devices: Vec<String>,
    pub eq_shared: Arc<RwLock<EqProfile>>,
    pub active_eq: EqProfile,

    pub now_playing: bool,
    pub queue_drawer: bool,
    pub lyrics_open: bool,
    pub modal: Option<Modal>,
    pub toast: Option<Toast>,

    pub palette: Palette,
    pub theme_key: (ThemeMode, Accent, bool, bool),
    pub cover_cache: HashMap<String, Option<egui::TextureHandle>>,

    pub sleep_deadline: Option<Instant>,
    pub sleep_total: u64,
    pub sleep_songs_left: Option<u32>,
    pub sleep_stop_pending: bool,
    pub sleep_fade_from: Option<(Instant, f32)>,
    pub sleep_wait_till_end: bool,
    pub sleep_fade: bool,
    pub last_save: Instant,
    pub last_pos_save: Instant,
    pub last_play_pos: f64,
    pub dirty: bool,
    pub rng_state: u64,
    pub frame_count: u64,
    pub screenshot_started: Instant,
    pub screenshot: Option<PathBuf>,
    pub screenshot_requested: bool,
    pub screenshot_saved: bool,
    pub close_after_screenshot: bool,
    pub context_menu_song: Option<String>,
    pub scroll_to_current: bool,
    pub fallback_attempted: bool,
    pub desktop: Option<DesktopIntegration>,
    pub desktop_error: Option<String>,
    pub hotkey_error: Option<String>,
    pub media_error: Option<String>,
    pub companions: CompanionWindows,
    pub hidden_to_tray: bool,
    pub quitting: bool,
    pub was_minimized: bool,
    desktop_attempts: u8,
    desktop_next_try: Instant,
}

impl LiltApp {
    pub fn new(cc: &eframe::CreationContext<'_>, screenshot: Option<PathBuf>) -> Self {
        theme::install_fonts(&cc.egui_ctx);
        let system_dark = cc.egui_ctx.theme() == egui::Theme::Dark;
        let mut state = library::load_state();
        crate::console::set_enabled(state.settings.developer_mode);
        crate::console::log(format!("Lilt {} starting", APP_VERSION));
        crate::console::log(format!("Data directory: {}", library::data_dir().display()));
        let minute = theme::local_minute_of_day();
        let daylight =
            minute >= state.settings.day_start_minutes && minute < state.settings.day_end_minutes;
        let palette = Palette::new(
            state.settings.theme,
            state.settings.accent,
            system_dark,
            daylight,
        );
        theme::apply_style(&cc.egui_ctx, palette);

        let valid_roots = state
            .library_roots
            .iter()
            .filter(|r| Path::new(r).is_dir())
            .count();
        if valid_roots == 0 {
            library::ensure_starter_library(&mut state);
        }
        let mut active_eq = state.eq_profile(state.settings.eq_device);
        active_eq.enabled = state.settings.eq_enabled;
        let eq_shared = Arc::new(RwLock::new(active_eq.clone()));
        let theme_key = (
            state.settings.theme,
            state.settings.accent,
            system_dark,
            daylight,
        );
        let initial_page = match std::env::var("LILT_PAGE").unwrap_or_default().as_str() {
            "settings" => Page::Settings,
            "equalizer" => Page::Equalizer,
            "queues" => Page::Queues,
            "playlists" => Page::Playlists,
            "songs" => Page::Songs,
            _ => Page::Folders,
        };

        let mut app = Self {
            state,
            songs: Vec::new(),
            folders: FolderIndex::default(),
            albums: Vec::new(),
            artists: Vec::new(),
            scan_rx: None,
            scanning: false,
            scan_seen: 0,
            scan_path: String::new(),
            last_scan: None,
            scan_error: None,
            page: initial_page,
            detail: None,
            folder_path: None,
            home_tab: HomeTab::Folders,
            folder_layout: FolderLayout::Grid,
            sort: SortMode::Title,
            sort_desc: false,
            search: String::new(),
            search_focus: false,
            selected: HashSet::new(),
            selection_mode: false,
            jump_letter: None,
            favorites_only: false,
            current_key: None,
            playing: false,
            fade: None,
            shuffle: false,
            repeat: RepeatMode::Off,
            pending_next: None,
            counted_current: false,
            ab_loop: None,
            stop_after_current: false,
            lyrics: None,
            lyrics_for: None,
            lyrics_fetcher: LyricsFetcher::new(cc.egui_ctx.clone()),
            lyrics_fetch_cache: FetchCache::load(),
            lyrics_status: HashMap::new(),
            lyrics_candidates: HashMap::new(),
            lyrics_candidate_request: None,
            lyrics_inflight: HashSet::new(),
            ai_key_saved: secret::has_api_key(),
            ai_key_input: String::new(),
            ai_models: Vec::new(),
            ai_models_loading: false,
            ai_testing: false,
            ai_status: None,
            audio: None,
            output_devices: AudioEngine::output_devices(),
            eq_shared,
            active_eq,
            now_playing: false,
            queue_drawer: false,
            lyrics_open: false,
            modal: None,
            toast: None,
            palette,
            theme_key,
            cover_cache: HashMap::new(),
            sleep_deadline: None,
            sleep_total: 0,
            sleep_songs_left: None,
            sleep_stop_pending: false,
            sleep_fade_from: None,
            sleep_wait_till_end: false,
            sleep_fade: false,
            last_save: Instant::now(),
            last_pos_save: Instant::now(),
            last_play_pos: 0.0,
            dirty: false,
            rng_state: 0x9e37_79b9_7f4a_7c15,
            frame_count: 0,
            screenshot_started: Instant::now(),
            screenshot,
            screenshot_requested: false,
            screenshot_saved: false,
            close_after_screenshot: false,
            context_menu_song: None,
            scroll_to_current: false,
            fallback_attempted: false,
            desktop: None,
            desktop_error: None,
            hotkey_error: None,
            media_error: None,
            companions: CompanionWindows::new(),
            hidden_to_tray: false,
            quitting: false,
            was_minimized: false,
            desktop_attempts: 0,
            desktop_next_try: Instant::now() + Duration::from_millis(800),
        };

        if app.state.settings.scan_on_start {
            app.start_scan();
        } else {
            app.current_key = None;
        }
        if let Ok(start) = std::env::var("LILT_START_PAGE") {
            app.page = match start.as_str() {
                "songs" => Page::Songs,
                "albums" => Page::Albums,
                "artists" => Page::Artists,
                "playlists" => Page::Playlists,
                "queues" => Page::Queues,
                "equalizer" => Page::Equalizer,
                "settings" => Page::Settings,
                "bookmarks" => Page::Bookmarks,
                "most" => Page::MostPlayed,
                "history" => Page::History,
                "recent" => Page::Recent,
                _ => Page::Folders,
            };
            if start == "now" {
                app.now_playing = true;
            }
        }
        app
    }

    pub fn lang(&self) -> Language {
        self.state.settings.language
    }

    pub fn t(&self, en: &'static str, zh: &'static str) -> &'static str {
        tr(self.lang(), en, zh)
    }

    pub fn mark_dirty(&mut self) {
        self.dirty = true;
    }

    pub fn notify(&mut self, text: impl Into<String>) {
        self.toast = Some(Toast {
            text: text.into(),
            born: Instant::now(),
        });
    }

    pub fn start_scan(&mut self) {
        if self.scanning {
            return;
        }
        if self.state.library_roots.is_empty() {
            library::ensure_starter_library(&mut self.state);
        }
        let roots: Vec<PathBuf> = self
            .state
            .library_roots
            .iter()
            .map(|r| canonical_clean(&PathBuf::from(r)))
            .filter(|p| p.is_dir())
            .collect();
        if roots.is_empty() {
            let starter = library::ensure_starter_library(&mut self.state);
            let text = starter.to_string_lossy().to_string();
            if !self.state.library_roots.contains(&text) {
                self.state.library_roots.push(text);
            }
        }
        let roots: Vec<PathBuf> = self
            .state
            .library_roots
            .iter()
            .map(|r| canonical_clean(&PathBuf::from(r)))
            .filter(|p| p.is_dir())
            .collect();
        let excluded: Vec<PathBuf> = self.state.excluded.iter().map(PathBuf::from).collect();
        let included: Vec<PathBuf> = self.state.included.iter().map(PathBuf::from).collect();
        let only_included = self.state.settings.only_included;
        let settings = self.state.settings.clone();
        let extras = self.state.songs.clone();
        crate::console::log(format!("Scanning {} root(s)", roots.len()));
        let (tx, rx) = channel();
        self.scan_rx = Some(rx);
        self.scanning = true;
        self.scan_seen = 0;
        self.scan_path.clear();
        self.scan_error = None;
        std::thread::spawn(move || {
            let (songs, stats) = library::scan_library(
                &roots,
                &excluded,
                &included,
                only_included,
                &settings,
                &extras,
                |seen, path| {
                    let _ = tx.send(ScanEvent::Progress(
                        seen,
                        path.file_name()
                            .map(|n| n.to_string_lossy().to_string())
                            .unwrap_or_default(),
                    ));
                },
            );
            let _ = tx.send(ScanEvent::Done(songs, stats));
        });
    }

    fn poll_scan(&mut self) {
        if self.scan_rx.is_none() {
            return;
        }
        let mut events = Vec::new();
        if let Some(rx) = &self.scan_rx {
            while let Ok(event) = rx.try_recv() {
                events.push(event);
            }
        }
        let mut done = false;
        let mut restarted = false;
        for event in events {
            match event {
                ScanEvent::Progress(seen, name) => {
                    self.scan_seen = seen;
                    self.scan_path = name;
                }
                ScanEvent::Done(songs, stats) => {
                    done = true;
                    restarted = self.apply_scan(songs, stats);
                }
                ScanEvent::Failed(error) => {
                    done = true;
                    self.scanning = false;
                    crate::console::log(format!("Scan failed: {error}"));
                    self.scan_error = Some(error);
                }
            }
        }
        if done && !restarted {
            self.scan_rx = None;
        }
    }

    fn apply_scan(&mut self, songs: Vec<Song>, stats: library::ScanStats) -> bool {
        if songs.is_empty() && self.state.starter_library && !self.fallback_attempted {
            if let Some(demo) = library::bundled_demo_dir() {
                self.fallback_attempted = true;
                self.state.library_roots = vec![demo.to_string_lossy().to_string()];
                self.scanning = false;
                self.scan_rx = None;
                self.start_scan();
                return true;
            }
        }
        self.songs = songs;
        self.scanning = false;
        self.last_scan = Some(now_secs());
        crate::console::log(format!(
            "Scan finished: {} songs, {} files seen, {} skipped",
            self.songs.len(),
            stats.files_seen,
            stats.skipped
        ));
        self.rebuild_groups();
        let roots: Vec<PathBuf> = self
            .state
            .library_roots
            .iter()
            .map(|r| canonical_clean(&PathBuf::from(r)))
            .collect();
        self.folders = FolderIndex::build(&self.songs, &roots);
        if self.folder_path.is_none() && roots.len() == 1 {
            self.folder_path = Some(roots[0].clone());
        }
        if self.state.queues.is_empty() && self.state.starter_library && !self.songs.is_empty() {
            let id = self.state.alloc_id();
            self.state.queues.push(Queue {
                id,
                name: self.t("All songs", "所有歌曲").to_owned(),
                songs: self.songs.iter().map(|s| s.key.clone()).collect(),
                index: 0,
                position: 0.0,
                updated: now_secs(),
            });
            self.state.active_queue = id;
        }
        for queue in &mut self.state.queues {
            queue
                .songs
                .retain(|key| self.songs.iter().any(|s| &s.key == key));
            if queue.songs.is_empty() {
                queue.index = 0;
                queue.position = 0.0;
            } else if queue.index >= queue.songs.len() {
                queue.index = queue.songs.len() - 1;
            }
        }
        let active_key = self
            .active_queue()
            .and_then(|q| q.songs.get(q.index))
            .cloned();
        if self
            .current_key
            .as_ref()
            .is_none_or(|key| !self.songs.iter().any(|s| &s.key == key))
        {
            self.current_key = active_key.or_else(|| self.songs.first().map(|s| s.key.clone()));
        }
        self.dirty = true;
        self.save_now();
        if std::env::var_os("LILT_AUTOPLAY").is_some() && !self.playing && !self.songs.is_empty() {
            self.toggle_play();
            if let Ok(seek) = std::env::var("LILT_SEEK") {
                if let Ok(seconds) = seek.parse::<f64>() {
                    self.seek_to(seconds);
                }
            }
            if std::env::var_os("LILT_PAUSE_AFTER_SEEK").is_some() {
                self.toggle_play();
            }
        }
        if let (Ok(start), Ok(end)) = (std::env::var("LILT_AB_A"), std::env::var("LILT_AB_B")) {
            if let (Ok(start), Ok(end)) = (start.parse::<f64>(), end.parse::<f64>()) {
                if end > start + 0.25 {
                    self.ab_loop = Some(AbLoop {
                        a: start,
                        b: Some(end),
                    });
                }
            }
        }
        if std::env::var_os("LILT_STOP_AFTER").is_some() {
            self.stop_after_current = true;
            self.drop_queued_next();
        }
        if let Ok(offset) = std::env::var("LILT_LYRICS_OFFSET") {
            if let (Ok(value), Some(key)) = (offset.parse::<i64>(), self.current_key.clone()) {
                self.set_lyrics_offset(&key, value);
                self.save_now();
            }
        }
        if std::env::var_os("LILT_LYRICS").is_some() {
            self.lyrics_open = true;
        }
        if std::env::var_os("LILT_DESKTOP_LYRICS").is_some() {
            self.state.settings.desktop_lyrics = true;
        }
        if std::env::var_os("LILT_DESKTOP_LYRICS_LOCKED").is_some() {
            self.state.settings.desktop_lyrics = true;
            self.state.settings.desktop_lyrics_locked = true;
        }
        if std::env::var_os("LILT_MINI_PLAYER").is_some() {
            self.state.settings.mini_player = true;
        }
        if let Ok(query) = std::env::var("LILT_SEARCH") {
            self.search = query;
        }
        if let Ok(secs) = std::env::var("LILT_SLEEP_SECS") {
            if let Ok(seconds) = secs.parse::<u64>() {
                self.sleep_deadline = Some(Instant::now() + Duration::from_secs(seconds));
                self.sleep_total = seconds;
                self.sleep_wait_till_end = self.state.settings.sleep_wait_till_end;
                self.sleep_fade = self.state.settings.sleep_fade;
            }
        }
        if let Ok(detail) = std::env::var("LILT_DETAIL") {
            if let Some((kind, name)) = detail.split_once(':') {
                match kind {
                    "album" => {
                        self.page = Page::Albums;
                        self.open_album(name.to_owned());
                    }
                    "artist" => {
                        self.page = Page::Artists;
                        self.open_artist(name.to_owned());
                    }
                    "playlist" => {
                        if let Some(id) = self
                            .state
                            .playlists
                            .iter()
                            .find(|p| p.name.eq_ignore_ascii_case(name))
                            .map(|p| p.id)
                        {
                            self.page = Page::Playlists;
                            self.open_playlist(id);
                        }
                    }
                    "queue" => {
                        if let Some(id) = self.state.queues.first().map(|q| q.id) {
                            self.page = Page::Queues;
                            self.open_queue(id);
                        }
                    }
                    _ => {}
                }
            }
        }
        if let Ok(modal) = std::env::var("LILT_MODAL") {
            self.selected.clear();
            if let Some(key) = self.current_key.clone() {
                self.selected.insert(key);
            }
            match modal.as_str() {
                "tag" => self.open_tag_editor(),
                "lyrics" => self.open_lyrics_editor(),
                "sleep" => self.open_sleep_timer(),
                "queue" => {
                    self.modal = Some(Modal::AddToQueue {
                        keys: self.selected_or_current_keys(),
                        queue_id: self.active_queue().map(|q| q.id),
                        new_name: String::new(),
                    });
                }
                "playlist" => {
                    self.modal = Some(Modal::NewPlaylist {
                        name: String::new(),
                        songs: self.selected_or_current_keys(),
                    });
                }
                "speed" => {
                    self.modal = Some(Modal::Speed {
                        value: self.state.settings.playback_speed,
                    });
                }
                "about" => self.modal = Some(Modal::About),
                "shortcuts" => self.modal = Some(Modal::Shortcuts),
                "missing" => self.open_missing_files(),
                "duplicates" => self.open_duplicates(),
                _ => {}
            }
        }
        false
    }

    pub fn rebuild_groups(&mut self) {
        let mut album_map: HashMap<String, AlbumEntry> = HashMap::new();
        let mut artist_map: HashMap<String, ArtistEntry> = HashMap::new();
        for (index, song) in self.songs.iter().enumerate() {
            let album_key = format!(
                "{}\u{1f}{}",
                song.album.to_lowercase(),
                song.album_artist.to_lowercase()
            );
            let entry = album_map.entry(album_key).or_insert_with(|| AlbumEntry {
                name: song.display_album().to_owned(),
                artist: if song.album_artist.trim().is_empty() {
                    song.display_artist().to_owned()
                } else {
                    song.album_artist.clone()
                },
                songs: Vec::new(),
                duration: 0.0,
            });
            entry.songs.push(index);
            entry.duration += song.duration;
            let artist_entry = artist_map
                .entry(song.display_artist().to_lowercase())
                .or_insert_with(|| ArtistEntry {
                    name: song.display_artist().to_owned(),
                    songs: Vec::new(),
                    album_count: 0,
                });
            artist_entry.songs.push(index);
        }
        for artist in artist_map.values_mut() {
            let mut albums: HashSet<String> = HashSet::new();
            for index in &artist.songs {
                albums.insert(self.songs[*index].album.to_lowercase());
            }
            artist.album_count = albums.len();
        }
        let mut albums: Vec<AlbumEntry> = album_map.into_values().collect();
        albums.sort_by(|a, b| a.name.to_lowercase().cmp(&b.name.to_lowercase()));
        let mut artists: Vec<ArtistEntry> = artist_map.into_values().collect();
        artists.sort_by(|a, b| a.name.to_lowercase().cmp(&b.name.to_lowercase()));
        self.albums = albums;
        self.artists = artists;
    }

    pub fn save_now(&mut self) {
        let _ = library::save_state(&self.state);
        self.dirty = false;
        self.last_save = Instant::now();
    }

    fn maybe_save(&mut self) {
        if self.dirty && self.last_save.elapsed() > Duration::from_millis(900) {
            self.save_now();
        }
    }

    pub fn song_by_key(&self, key: &str) -> Option<&Song> {
        self.songs.iter().find(|s| s.key == key)
    }

    pub fn current_song(&self) -> Option<&Song> {
        self.current_key
            .as_deref()
            .and_then(|key| self.song_by_key(key))
    }

    pub fn active_queue(&self) -> Option<&Queue> {
        self.state.active_queue()
    }

    pub fn active_queue_songs(&self) -> Vec<&Song> {
        let Some(queue) = self.active_queue() else {
            return Vec::new();
        };
        queue
            .songs
            .iter()
            .filter_map(|key| self.song_by_key(key))
            .collect()
    }

    pub fn queue_songs_for(&self, id: u64) -> Vec<&Song> {
        self.state
            .queue_by_id(id)
            .map(|queue| {
                queue
                    .songs
                    .iter()
                    .filter_map(|key| self.song_by_key(key))
                    .collect()
            })
            .unwrap_or_default()
    }

    pub fn position(&self) -> f64 {
        if let Some(audio) = &self.audio {
            if audio.loaded() {
                return audio.position();
            }
        }
        self.active_queue()
            .map(|q| q.position)
            .unwrap_or(self.last_play_pos)
    }

    pub fn duration(&self) -> f64 {
        self.current_song().map(|s| s.duration).unwrap_or(0.0)
    }

    pub fn sleep_remaining(&self) -> Option<u64> {
        self.sleep_deadline
            .map(|deadline| deadline.saturating_duration_since(Instant::now()).as_secs())
    }

    pub fn ensure_audio(&mut self) -> bool {
        if self.audio.is_some() {
            return true;
        }
        let wanted = self.state.settings.output_device.clone();
        match AudioEngine::new(self.eq_shared.clone(), &wanted) {
            Ok(engine) => {
                self.audio = Some(engine);
                true
            }
            Err(error) => {
                self.notify(error);
                false
            }
        }
    }

    pub fn refresh_output_devices(&mut self) {
        self.output_devices = AudioEngine::output_devices();
        let current = self.state.settings.output_device.clone();
        if !current.is_empty() && !self.output_devices.iter().any(|name| name == &current) {
            self.state.settings.output_device.clear();
            self.dirty = true;
        }
    }

    pub fn test_audio_output(&mut self) {
        if self.audio.is_none() && !self.ensure_audio() {
            return;
        }
        if let Some(audio) = &mut self.audio {
            if let Err(error) = audio.play_test_tone() {
                self.notify(error);
            } else {
                let label = audio.device_name().to_owned();
                self.notify(format!("Test tone on {label}"));
            }
        }
    }

    pub fn set_output_device(&mut self, name: String) {
        if self.state.settings.output_device == name {
            return;
        }
        let was_playing = self.playing;
        let position = self.position();
        let index = self.active_queue().map(|queue| queue.index).unwrap_or(0);
        self.state.settings.output_device = name;
        self.audio = None;
        self.fade = None;
        self.playing = false;
        self.dirty = true;
        self.save_now();
        if self.ensure_audio() {
            let label = self
                .audio
                .as_ref()
                .map(|audio| audio.device_name().to_owned())
                .unwrap_or_else(|| "system default".to_owned());
            self.notify(format!("Sound output: {label}"));
            if was_playing {
                self.start_index(index, position);
            } else if let Some(audio) = &mut self.audio {
                let _ = audio.play_test_tone();
            }
        }
    }

    fn is_daylight(&self) -> bool {
        let minute = theme::local_minute_of_day();
        minute >= self.state.settings.day_start_minutes
            && minute < self.state.settings.day_end_minutes
    }

    fn effective_volume(&self) -> f32 {
        let mut volume = self.state.settings.volume.clamp(0.0, 1.0);
        if self.state.settings.normalize {
            if let Some(gain) = self.current_song().and_then(|s| s.replay_gain) {
                volume *= 10f32.powf(gain / 20.0);
            }
        }
        volume.clamp(0.0, 1.0)
    }

    fn next_random(&mut self, max: usize) -> usize {
        if max == 0 {
            return 0;
        }
        self.rng_state = self
            .rng_state
            .wrapping_mul(6_364_136_223_846_793_005)
            .wrapping_add(1_442_695_040_888_963_407);
        (self.rng_state >> 33) as usize % max
    }

    fn upcoming_index(&mut self, current: usize, queue_len: usize, auto: bool) -> Option<usize> {
        if queue_len == 0 {
            return None;
        }
        if auto && self.repeat == RepeatMode::One {
            return Some(current);
        }
        if self.shuffle {
            if queue_len == 1 {
                return Some(0);
            }
            let mut pick = self.next_random(queue_len);
            if pick == current {
                pick = (pick + 1) % queue_len;
            }
            return Some(pick);
        }
        if current + 1 < queue_len {
            Some(current + 1)
        } else {
            match self.repeat {
                RepeatMode::All => Some(0),
                _ => None,
            }
        }
    }

    fn queue_next(&mut self) {
        if self.pending_next.is_some() || self.stop_after_current {
            return;
        }
        let Some(queue) = self.active_queue() else {
            return;
        };
        let len = queue.songs.len();
        let current = queue.index;
        let next = self.upcoming_index(current, len, true);
        if let Some(next_index) = next {
            if let Some(key) = self.active_queue().and_then(|q| q.songs.get(next_index)) {
                if let Some(song) = self.song_by_key(key) {
                    let path = song.path.clone();
                    if self.ensure_audio() {
                        if let Some(audio) = &mut self.audio {
                            if matches!(audio.append_next(&path), Ok(true)) {
                                self.pending_next = Some(next_index);
                            }
                        }
                    }
                }
            }
        }
    }

    pub fn play_context(&mut self, keys: Vec<String>, index: usize, name: &str) {
        self.clear_stop_after_current();
        let existing: Vec<String> = keys
            .iter()
            .filter(|key| self.song_by_key(key).is_some())
            .cloned()
            .collect();
        if existing.is_empty() {
            return;
        }
        let index = index.min(existing.len() - 1);
        let mut use_existing = None;
        if let Some(queue) = self.active_queue() {
            if queue.name == name && queue.songs == existing {
                use_existing = Some(queue.id);
            }
        }
        let queue_id = if let Some(id) = use_existing {
            id
        } else {
            let mut candidate = name.trim().to_owned();
            if candidate.is_empty() {
                candidate = self.t("New queue", "新队列").to_owned();
            }
            let base = candidate.clone();
            let mut suffix = 2;
            while self.state.queues.iter().any(|q| q.name == candidate) {
                candidate = format!("{base} {suffix}");
                suffix += 1;
            }
            self.create_or_replace_queue(&candidate, existing, ConflictPolicy::Replace)
        };
        self.state.active_queue = queue_id;
        self.start_index(index, 0.0);
    }

    pub fn play_active_queue_at(&mut self, index: usize) {
        self.clear_stop_after_current();
        if self.active_queue().is_some() {
            self.start_index(index, 0.0);
        }
    }

    fn start_index(&mut self, index: usize, position: f64) {
        let was_playing = self.playing;
        let Some(queue) = self.active_queue() else {
            return;
        };
        if queue.songs.is_empty() {
            return;
        }
        let index = index.min(queue.songs.len() - 1);
        let key = queue.songs[index].clone();
        let Some(song) = self.song_by_key(&key) else {
            return;
        };
        let duration = song.duration;
        let path = song.path.clone();
        let position = position.clamp(0.0, (duration - 0.15).max(0.0));
        self.current_key = Some(key.clone());
        self.pending_next = None;
        self.ab_loop = None;
        self.counted_current = false;
        self.lyrics = None;
        self.lyrics_for = None;
        self.last_play_pos = position;
        self.last_pos_save = Instant::now();

        if !self.ensure_audio() {
            return;
        }
        let volume = self.effective_volume();
        let speed = self.state.settings.playback_speed.clamp(0.5, 3.0);
        let mut error = None;
        if let Some(audio) = &mut self.audio {
            if let Err(err) = audio.play(&path, position, volume, speed) {
                error = Some(err);
            } else {
                audio.set_speed(speed);
            }
        }
        if let Some(error) = error {
            self.notify(error);
            self.playing = false;
            return;
        }
        self.playing = true;
        // `MusicService.W` resume path: `ac.e0(k_b_fdid, 1)` starts a fade-in.
        // Only a start from a paused state fades; auto-advance (`MusicService.V`)
        // has no `ac.e0`/`ac.M` call. A track started from paused begins at
        // volume 0 and ramps like `ub.run`.
        let fade_in = self.state.settings.fade_in_ms.min(FADE_MAX_MS);
        if fade_in > 0 && !was_playing {
            if let Some(audio) = &mut self.audio {
                audio.set_volume(0.0);
            }
            self.start_play_fade(FadeKind::In, fade_in);
        } else {
            self.fade = None;
        }
        if let Some(queue) = self.state.active_queue_mut() {
            queue.index = index;
            queue.position = position;
            queue.updated = now_secs();
        }
        self.last_save = Instant::now();
        self.dirty = true;
        if self.sleep_fade && self.sleep_songs_left == Some(1) {
            self.begin_sleep_fade();
        }
        if self.state.settings.gapless && position < 0.1 {
            self.queue_next();
        }
        if let Some(extra) = self.state.songs.get_mut(&key) {
            extra.last_position = position;
            extra.last_played = Some(now_secs());
        }
        if let Some(song) = self.songs.iter_mut().find(|s| s.key == key) {
            song.last_played = Some(now_secs());
        }
    }

    pub fn toggle_play(&mut self) {
        if self.playing {
            // `MusicService.W` pause path: `ac.M(k_b_fdod, 2, null)`.
            // A positive fade duration ramps the volume down first and pauses
            // when `vb.run` reaches the end; 0 pauses at once.
            let fade_out = self.state.settings.fade_out_ms.min(FADE_MAX_MS);
            if fade_out > 0 && self.audio.is_some() {
                self.start_play_fade(FadeKind::Out, fade_out);
                self.playing = false;
                let position = self.position();
                if let Some(queue) = self.state.active_queue_mut() {
                    queue.position = position;
                }
                self.save_now();
            } else {
                self.pause_now();
            }
            return;
        }
        // Pressing play during a fade-out cancels it (`ac.e0` clears `j`).
        self.fade = None;
        if self.current_key.is_none() && !self.songs.is_empty() {
            let keys: Vec<String> = self.songs.iter().map(|s| s.key.clone()).collect();
            let name = self.t("All songs", "所有歌曲").to_owned();
            self.play_context(keys, 0, &name);
            return;
        }
        let Some(queue) = self.active_queue() else {
            if !self.songs.is_empty() {
                let keys: Vec<String> = self.songs.iter().map(|s| s.key.clone()).collect();
                let name = self.t("All songs", "所有歌曲").to_owned();
                self.play_context(keys, 0, &name);
            }
            return;
        };
        let index = queue.index.min(queue.songs.len().saturating_sub(1));
        let mut position = queue.position;
        let duration = self.duration();
        if duration > 0.0 && position >= duration - 0.5 {
            position = 0.0;
        }
        let audio_loaded = self
            .audio
            .as_ref()
            .map(|audio| audio.loaded() && !audio.is_empty())
            .unwrap_or(false);
        if audio_loaded {
            let fade_in = self.state.settings.fade_in_ms.min(FADE_MAX_MS);
            let volume = self.effective_volume();
            if let Some(audio) = &mut self.audio {
                audio.resume();
                if fade_in == 0 {
                    audio.set_volume(volume);
                }
            }
            self.playing = true;
            if fade_in > 0 {
                self.start_play_fade(FadeKind::In, fade_in);
            } else {
                self.fade = None;
            }
            if self.state.settings.gapless {
                self.queue_next();
            }
        } else {
            self.start_index(index, position);
        }
    }

    fn start_play_fade(&mut self, kind: FadeKind, duration_ms: u32) {
        let duration_ms = duration_ms.min(FADE_MAX_MS);
        if duration_ms == 0 {
            self.fade = None;
            return;
        }
        let start_volume = self
            .audio
            .as_ref()
            .map(|audio| audio.volume())
            .unwrap_or(0.0);
        self.fade = Some(PlayFade {
            started: Instant::now(),
            duration_ms,
            kind,
            start_volume,
        });
    }

    /// A tick of `vb.run` / `ub.run`: 100 ms per step, integer volume units
    /// 0…100 in the target; `q60.E()` reads the level and `q60.b0()` writes it.
    fn update_play_fade(&mut self) {
        let Some(fade) = self.fade else {
            return;
        };
        let elapsed = fade.started.elapsed().as_millis().min(u32::MAX as u128) as u32;
        let base = self.effective_volume();
        let current = self
            .audio
            .as_ref()
            .map(|audio| audio.volume())
            .unwrap_or(base);
        // `ub.run` sleeps 300 ms before its first fade-in step when the
        // duration is odd; `vb.run` always sleeps 100 ms. Each thread then
        // runs `duration/100` steps of 100 ms.
        let steps = (fade.duration_ms / 100).max(1);
        let done_ms = match fade.kind {
            FadeKind::In => {
                let first = if fade.duration_ms % 2 == 1 { 300 } else { 100 };
                first + steps * 100
            }
            FadeKind::Out => 100 + steps * 100,
        };
        let done = elapsed >= done_ms;
        match fade.kind {
            FadeKind::In => {
                let level = base * fade_in_level(fade.duration_ms, elapsed);
                if level > current {
                    if let Some(audio) = &mut self.audio {
                        audio.set_volume(level);
                    }
                }
                if done {
                    if let Some(audio) = &mut self.audio {
                        audio.set_volume(base);
                    }
                    self.fade = None;
                }
            }
            FadeKind::Out => {
                let level = fade_out_level(fade.start_volume, fade.duration_ms, elapsed);
                if level < current {
                    if let Some(audio) = &mut self.audio {
                        audio.set_volume(level);
                    }
                }
                if done {
                    // The target leaves the player at the faded level and
                    // pauses once the ramp is over.
                    if let Some(audio) = &mut self.audio {
                        audio.set_volume(0.0);
                        audio.pause();
                    }
                    self.fade = None;
                    self.playing = false;
                    let position = self.position();
                    if let Some(queue) = self.state.active_queue_mut() {
                        queue.position = position;
                    }
                    self.save_now();
                }
            }
        }
    }

    fn pause_now(&mut self) {
        if let Some(audio) = &self.audio {
            audio.pause();
        }
        self.playing = false;
        self.fade = None;
        let position = self.position();
        if let Some(queue) = self.state.active_queue_mut() {
            queue.position = position;
        }
        self.save_now();
    }

    pub fn next_track(&mut self, auto: bool) {
        if !auto {
            self.clear_stop_after_current();
        }
        let Some((len, current)) = self.active_queue().map(|q| (q.songs.len(), q.index)) else {
            return;
        };
        if len == 0 {
            return;
        }
        let current = current.min(len - 1);
        if auto && self.repeat == RepeatMode::One {
            self.start_index(current, 0.0);
            return;
        }
        if let Some(next) = self.upcoming_index(current, len, auto) {
            self.start_index(next, 0.0);
            return;
        }
        if self.state.settings.jump_queues {
            if let Some(next_queue) = self.next_queue_id() {
                self.state.active_queue = next_queue;
                if let Some(q) = self.active_queue() {
                    if !q.songs.is_empty() {
                        let index = q.index.min(q.songs.len() - 1);
                        let position = q.position;
                        self.start_index(index, position);
                        return;
                    }
                }
            }
        }
        if let Some(audio) = &self.audio {
            audio.pause();
        }
        self.fade = None;
        self.playing = false;
        if let Some(queue) = self.state.active_queue_mut() {
            queue.position = 0.0;
        }
        self.last_play_pos = 0.0;
        self.notify(
            self.t("End of the current queue", "已到队列末尾")
                .to_owned(),
        );
    }

    fn next_queue_id(&self) -> Option<u64> {
        let mut queues: Vec<&Queue> = self
            .state
            .queues
            .iter()
            .filter(|q| !q.songs.is_empty())
            .collect();
        queues.sort_by_key(|q| q.id);
        if queues.is_empty() {
            return None;
        }
        let active_pos = queues.iter().position(|q| q.id == self.state.active_queue);
        let next_pos = active_pos.map(|pos| (pos + 1) % queues.len()).unwrap_or(0);
        Some(queues[next_pos].id)
    }

    pub fn prev_track(&mut self) {
        self.clear_stop_after_current();
        let pos = self.position();
        if !self.state.settings.previous_always && pos > crate::model::PREVIOUS_REPLAY_SECONDS {
            self.seek_to(0.0);
            return;
        }
        let Some((len, current)) = self.active_queue().map(|q| (q.songs.len(), q.index)) else {
            return;
        };
        if len == 0 {
            return;
        }
        let current = current.min(len - 1);
        if current > 0 {
            self.start_index(current - 1, 0.0);
        } else if self.repeat == RepeatMode::All {
            self.start_index(len - 1, 0.0);
        } else {
            self.seek_to(0.0);
        }
    }

    pub fn seek_to(&mut self, seconds: f64) {
        if let Some(ab) = self.ab_loop {
            if let Some(b) = ab.b {
                if seconds < ab.a - 0.05 || seconds > b + 0.05 {
                    self.ab_loop = None;
                }
            }
        }
        self.seek_to_inner(seconds);
    }

    /// Used by the loop itself; keeps the markers active.
    fn seek_to_loop_start(&mut self, seconds: f64) {
        self.seek_to_inner(seconds);
    }

    fn seek_to_inner(&mut self, seconds: f64) {
        let duration = self.duration();
        let seconds = seconds.clamp(0.0, (duration - 0.05).max(0.0));
        let was_playing = self.playing;
        if self.pending_next.is_some() {
            let index = self.active_queue().map(|q| q.index).unwrap_or(0);
            self.start_index(index, seconds);
            if !was_playing {
                if let Some(audio) = &self.audio {
                    audio.pause();
                }
                self.fade = None;
                self.playing = false;
            }
            return;
        }
        if let Some(audio) = &self.audio {
            if audio.loaded() {
                audio.seek(seconds);
                self.last_play_pos = seconds;
                if let Some(queue) = self.state.active_queue_mut() {
                    queue.position = seconds;
                }
                self.dirty = true;
                return;
            }
        }
        let Some(queue) = self.active_queue() else {
            return;
        };
        let index = queue.index;
        self.start_index(index, seconds);
        if !was_playing {
            if let Some(audio) = &self.audio {
                audio.pause();
            }
            self.fade = None;
            self.playing = false;
        }
    }

    pub fn seek_fraction(&mut self, fraction: f64) {
        self.seek_to(fraction.clamp(0.0, 1.0) * self.duration());
    }

    pub fn toggle_ab_loop(&mut self) {
        let position = self.position();
        match self.ab_loop {
            None => {
                self.ab_loop = Some(AbLoop {
                    a: position,
                    b: None,
                });
                self.notify(format!(
                    "{} {}",
                    self.t("A set at", "A 点已设在"),
                    format_duration(position)
                ));
            }
            Some(ab) if ab.b.is_none() => {
                if position <= ab.a + 0.25 {
                    self.notify(self.t("Set B later than A", "B 点需要晚于 A 点").to_owned());
                } else {
                    self.ab_loop = Some(AbLoop {
                        a: ab.a,
                        b: Some(position),
                    });
                    self.notify(format!(
                        "A-B {} – {}",
                        format_duration(ab.a),
                        format_duration(position)
                    ));
                }
            }
            Some(_) => {
                self.ab_loop = None;
                self.notify(self.t("A-B loop off", "A-B 循环已关闭").to_owned());
            }
        }
    }

    pub fn clear_ab_loop(&mut self) {
        if self.ab_loop.take().is_some() {
            self.notify(self.t("A-B loop off", "A-B 循环已关闭").to_owned());
        }
    }

    /// One-shot "stop at the end of this song". Enabling it also drops a
    /// gapless preload that is already queued, so the next track cannot start
    /// between the toggle and the end of the current track.
    pub fn toggle_stop_after_current(&mut self) {
        self.stop_after_current = !self.stop_after_current;
        if self.stop_after_current {
            self.drop_queued_next();
            self.notify(
                self.t("Will stop after this song", "本曲结束后停止")
                    .to_owned(),
            );
        } else {
            self.notify(
                self.t("Stop after this song off", "已取消本曲结束后停止")
                    .to_owned(),
            );
        }
    }

    pub fn clear_stop_after_current(&mut self) {
        self.stop_after_current = false;
    }

    fn drop_queued_next(&mut self) {
        if self.pending_next.take().is_none() {
            return;
        }
        let Some(song) = self.current_song().cloned() else {
            return;
        };
        let position = self.position();
        let was_playing = self.playing;
        let volume = self.effective_volume();
        let speed = self.state.settings.playback_speed.clamp(0.5, 3.0);
        let mut error = None;
        if let Some(audio) = &mut self.audio {
            if let Err(err) = audio.play(&song.path, position, volume, speed) {
                error = Some(err);
            } else {
                audio.set_speed(speed);
                if !was_playing {
                    audio.pause();
                }
            }
        }
        if let Some(error) = error {
            self.notify(error);
            self.playing = false;
        }
    }

    fn stop_after_song(&mut self) {
        if let Some(audio) = &self.audio {
            audio.pause();
        }
        self.fade = None;
        self.playing = false;
        self.stop_after_current = false;
        let duration = self.duration();
        self.last_play_pos = duration;
        if let Some(queue) = self.state.active_queue_mut() {
            queue.position = duration;
        }
        if let Some(key) = self.current_key.clone() {
            if let Some(extra) = self.state.songs.get_mut(&key) {
                extra.last_position = duration;
            }
            if let Some(song) = self.songs.iter_mut().find(|song| song.key == key) {
                song.last_position = duration;
            }
        }
        self.notify(
            self.t("Stopped after this song", "本曲结束后已停止")
                .to_owned(),
        );
        self.save_now();
    }

    pub fn set_volume(&mut self, value: f32) {
        self.state.settings.volume = value.clamp(0.0, 1.0);
        let vol = self.effective_volume();
        if let Some(audio) = &mut self.audio {
            audio.set_volume(vol);
        }
        self.dirty = true;
    }

    /// Used by the global volume shortcuts. Steps stay at 5%, matching the
    /// practical granularity of the player bar and tray actions.
    pub fn adjust_volume(&mut self, delta: f32) {
        let next = (self.state.settings.volume + delta).clamp(0.0, 1.0);
        self.set_volume(next);
        let percent = (next * 100.0).round() as u32;
        self.notify(format!("{} {percent}%", self.t("Volume", "音量")));
    }

    pub fn toggle_desktop_lyrics(&mut self) {
        self.state.settings.desktop_lyrics = !self.state.settings.desktop_lyrics;
        self.dirty = true;
        desktop::log_line(if self.state.settings.desktop_lyrics {
            "desktop lyrics shown"
        } else {
            "desktop lyrics hidden"
        });
    }

    pub fn toggle_desktop_lyrics_lock(&mut self) {
        self.state.settings.desktop_lyrics_locked = !self.state.settings.desktop_lyrics_locked;
        self.dirty = true;
        desktop::log_line(if self.state.settings.desktop_lyrics_locked {
            "desktop lyrics locked"
        } else {
            "desktop lyrics unlocked"
        });
    }

    pub fn toggle_mini_player(&mut self) {
        self.state.settings.mini_player = !self.state.settings.mini_player;
        self.dirty = true;
        desktop::log_line(if self.state.settings.mini_player {
            "mini player shown"
        } else {
            "mini player hidden"
        });
    }

    pub fn set_speed(&mut self, value: f32) {
        self.state.settings.playback_speed = value.clamp(0.5, 3.0);
        if let Some(audio) = &mut self.audio {
            audio.set_speed(value);
        }
        self.dirty = true;
    }

    pub fn toggle_shuffle(&mut self) {
        self.shuffle = !self.shuffle;
        self.notify(if self.shuffle {
            self.t("Shuffle on", "随机播放已开启").to_owned()
        } else {
            self.t("Shuffle off", "随机播放已关闭").to_owned()
        });
    }

    pub fn cycle_repeat(&mut self) {
        self.repeat = match self.repeat {
            RepeatMode::Off => RepeatMode::All,
            RepeatMode::All => RepeatMode::One,
            RepeatMode::One => RepeatMode::Off,
        };
    }

    pub fn toggle_favorite(&mut self, key: &str) {
        let mut value = false;
        {
            let extra = self.state.extra_mut(key);
            extra.favorite = !extra.favorite;
            value = extra.favorite;
        }
        if let Some(song) = self.songs.iter_mut().find(|s| s.key == key) {
            song.favorite = value;
        }
        self.dirty = true;
        self.notify(if value {
            self.t("Added to favorites", "已加入收藏").to_owned()
        } else {
            self.t("Removed from favorites", "已取消收藏").to_owned()
        });
    }

    pub fn mark_played_if_needed(&mut self) {
        if self.counted_current {
            return;
        }
        let Some((key, duration)) = self
            .current_song()
            .map(|song| (song.key.clone(), song.duration))
        else {
            return;
        };
        if duration <= 1.0 {
            return;
        }
        // `MusicService.p()`: target_ms = duration_ms * percent / 100 - crossfade_ms.
        let threshold = (duration * self.state.settings.min_play_percent as f64 / 100.0
            - self.state.settings.crossfade_seconds as f64)
            .max(0.0);
        if self.position() + 0.2 >= threshold {
            self.counted_current = true;
            let now = now_secs();
            {
                let extra = self.state.extra_mut(&key);
                extra.play_count += 1;
                extra.last_played = Some(now);
            }
            if let Some(song) = self.songs.iter_mut().find(|s| s.key == key) {
                song.play_count += 1;
                song.last_played = Some(now);
            }
            self.dirty = true;
        }
    }

    fn update_sleep_timer(&mut self) {
        if let Some(deadline) = self.sleep_deadline {
            if deadline <= Instant::now() {
                // `MusicService.z0(j, wait, fade)` stores `f = wait` and
                // `g = wait && fade`. At the alarm, `a_sltmcl` pauses now,
                // unless wait is on, in which case the current song ends first.
                if self.sleep_wait_till_end && self.playing && self.current_key.is_some() {
                    self.sleep_deadline = None;
                    self.sleep_stop_pending = true;
                    if self.sleep_fade {
                        self.begin_sleep_fade();
                    }
                    self.notify(
                        self.t("Will stop after this song", "本曲结束后停止")
                            .to_owned(),
                    );
                } else {
                    self.stop_for_sleep();
                    return;
                }
            }
        }

        if let Some((started, span)) = self.sleep_fade_from {
            if self.playing && span > 0.0 {
                let elapsed = started.elapsed().as_secs_f32();
                let factor = (1.0 - elapsed / span).clamp(0.0, 1.0);
                let volume = self.effective_volume() * factor;
                if let Some(audio) = &mut self.audio {
                    audio.set_volume(volume);
                }
            }
        }
    }

    /// `dd.l0()` fades over the current song's remaining duration, and skips
    /// the fade when less than `0x3e8` ms (1000 ms) is left.
    fn begin_sleep_fade(&mut self) {
        let remaining = (self.duration() - self.position()).max(0.0);
        if remaining > 1.0 {
            self.sleep_fade_from = Some((Instant::now(), remaining as f32));
        }
    }

    fn clear_sleep_state(&mut self) {
        self.sleep_deadline = None;
        self.sleep_total = 0;
        self.sleep_songs_left = None;
        self.sleep_stop_pending = false;
        self.sleep_fade_from = None;
    }

    fn stop_for_sleep(&mut self) {
        if let Some(audio) = &self.audio {
            audio.pause();
        }
        self.fade = None;
        self.playing = false;
        self.clear_sleep_state();
        let volume = self.effective_volume();
        if let Some(audio) = &mut self.audio {
            audio.set_volume(volume);
        }
        self.notify(
            self.t("Stopped by sleep timer", "睡眠定时已暂停播放")
                .to_owned(),
        );
    }

    /// `zy.d(int)` / `MusicService.A0(int, boolean)`: stop after N songs,
    /// with 1..=99 (the dialog clamps the field with `0x63`).
    pub fn set_sleep_songs(&mut self, songs: u32, wait: bool, fade: bool) {
        self.clear_sleep_state();
        let songs = songs.clamp(1, 99);
        self.sleep_wait_till_end = wait;
        self.sleep_fade = fade;
        self.sleep_songs_left = Some(songs);
        self.notify(format!(
            "{} {songs} {}",
            self.t("Sleep timer: stop after", "睡眠定时：播放"),
            self.t("songs", "首后停止")
        ));
        if songs == 1 && fade && self.playing {
            self.begin_sleep_fade();
        }
        self.push_sleep_history_n(songs);
        self.dirty = true;
    }

    /// `zy.a(int, int)`: `h * 0x36ee80 + m * 0xea60 + 0x3e8` ms.
    pub fn set_sleep_time(&mut self, hours: u32, minutes: u32, wait: bool, fade: bool) {
        self.clear_sleep_state();
        self.sleep_wait_till_end = wait;
        self.sleep_fade = fade;
        let seconds = hours as u64 * 3600 + minutes as u64 * 60 + 1;
        if seconds <= 1 {
            return;
        }
        self.sleep_deadline = Some(Instant::now() + Duration::from_secs(seconds));
        self.sleep_total = seconds;
        self.notify(format!(
            "{} {}:{}",
            self.t("Sleep timer set for", "睡眠定时已设为"),
            hours,
            format!("{minutes:02}")
        ));
        self.push_sleep_history_hm(hours, minutes);
        self.dirty = true;
    }

    fn push_sleep_history_hm(&mut self, hours: u32, minutes: u32) {
        let history = &mut self.state.settings.sleep_history_hm;
        history.retain(|entry| *entry != (hours, minutes));
        if history.len() == 5 {
            history.remove(0);
        }
        history.push((hours, minutes));
    }

    fn push_sleep_history_n(&mut self, songs: u32) {
        let history = &mut self.state.settings.sleep_history_n;
        history.retain(|entry| *entry != songs);
        if history.len() == 5 {
            history.remove(0);
        }
        history.push(songs);
    }

    pub fn stop_sleep_timer(&mut self) {
        self.clear_sleep_state();
        let volume = self.effective_volume();
        if let Some(audio) = &mut self.audio {
            audio.set_volume(volume);
        }
        self.notify(self.t("Sleep timer off", "睡眠定时已关闭").to_owned());
    }

    pub fn open_sleep_timer(&mut self) {
        let (hours, minutes) = self
            .state
            .settings
            .sleep_history_hm
            .last()
            .copied()
            .unwrap_or((1, 30));
        let songs = self
            .state
            .settings
            .sleep_history_n
            .last()
            .copied()
            .unwrap_or(10);
        self.modal = Some(Modal::SleepTimer {
            hours,
            minutes,
            songs,
            mode: SleepMode::AfterTime,
            wait_till_end: self.state.settings.sleep_wait_till_end,
            fade: self.state.settings.sleep_fade,
        });
    }

    fn update_playback(&mut self) {
        if !self.playing {
            return;
        }
        let Some((len, empty, pos)) = self
            .audio
            .as_ref()
            .map(|audio| (audio.len(), audio.is_empty(), audio.position()))
        else {
            return;
        };
        // Gapless transition: only advance the visible queue when Rodio has
        // actually consumed the queued source. The sink clears its queued
        // flag here, so the next append can succeed. Returning avoids writing
        // the previous track's end position onto the new track.
        let transition_started = if self.pending_next.is_some() {
            self.audio
                .as_mut()
                .map(|audio| audio.take_transition(len))
                .unwrap_or(false)
        } else {
            false
        };
        if let Some(index) = take_pending_transition(&mut self.pending_next, transition_started) {
            if let Some(queue) = self.state.active_queue_mut() {
                queue.index = index;
                queue.position = 0.0;
                queue.updated = now_secs();
            }
            if let Some(key) = self
                .active_queue()
                .and_then(|q| q.songs.get(index))
                .cloned()
            {
                self.current_key = Some(key.clone());
                self.counted_current = false;
                self.lyrics = None;
                self.lyrics_for = None;
                if let Some(extra) = self.state.songs.get_mut(&key) {
                    extra.last_position = 0.0;
                }
                self.queue_next();
            }
            return;
        }
        if empty {
            if self.stop_after_current {
                self.stop_after_song();
                return;
            }
            // Keep `playing` true across an automatic track change: the target
            // does not fade-in on auto-advance (`MusicService.V` has no
            // `ac.e0`/`ac.M` call). `next_track` clears it if the queue ends.
            // `MusicService.s(ac)`: the songs counter is decremented when a
            // song ends; at 0 the service pauses and shows the sleep message.
            if self.sleep_stop_pending {
                self.stop_for_sleep();
                return;
            }
            if let Some(left) = self.sleep_songs_left {
                if left <= 1 {
                    self.stop_for_sleep();
                    return;
                }
                self.sleep_songs_left = Some(left - 1);
            }
            let repeat_same = self.repeat == RepeatMode::One;
            if repeat_same {
                let index = self.active_queue().map(|q| q.index).unwrap_or(0);
                self.start_index(index, 0.0);
            } else {
                self.next_track(true);
            }
            return;
        }

        if let Some(ab) = self.ab_loop {
            if let Some(target) = ab_loop_target(Some(ab), pos) {
                self.seek_to_loop_start(target);
                return;
            }
        }

        self.last_play_pos = pos;
        if let Some(queue) = self.state.active_queue_mut() {
            queue.position = pos;
        }
        self.mark_played_if_needed();

        if self.last_pos_save.elapsed() > Duration::from_secs(3) {
            self.last_pos_save = Instant::now();
            let key = self.current_key.clone();
            if let Some(key) = key {
                let pos = self.position();
                if let Some(extra) = self.state.songs.get_mut(&key) {
                    extra.last_position = pos;
                }
                if let Some(song) = self.songs.iter_mut().find(|s| s.key == key) {
                    song.last_position = pos;
                }
                self.dirty = true;
            }
        }
    }

    pub fn create_or_replace_queue(
        &mut self,
        name: &str,
        songs: Vec<String>,
        policy: ConflictPolicy,
    ) -> u64 {
        let mut name = name.trim().to_owned();
        if name.is_empty() {
            name = self.t("New queue", "新队列").to_owned();
        }
        if let Some(existing_id) = self
            .state
            .queues
            .iter()
            .find(|q| q.name == name)
            .map(|q| q.id)
        {
            if let Some(queue) = self.state.queues.iter_mut().find(|q| q.id == existing_id) {
                if is_replace(policy) {
                    queue.songs = dedupe_songs(songs);
                    queue.index = 0;
                    queue.position = 0.0;
                } else {
                    let mut existing: HashSet<String> = queue.songs.iter().cloned().collect();
                    for key in songs {
                        if existing.insert(key.clone()) {
                            queue.songs.push(key);
                        }
                    }
                }
                queue.updated = now_secs();
                return existing_id;
            }
        }
        // A unique name is required for named queues.
        while self.state.queues.iter().any(|q| q.name == name) {
            name.push(' ');
            name.push('2');
        }
        if self.state.queues.len() >= MAX_QUEUES {
            let victim = self.state.queues.iter().min_by_key(|q| q.id).map(|q| q.id);
            if let Some(id) = victim {
                self.state.queues.retain(|q| q.id != id);
                self.notify(format!(
                    "{} {}",
                    self.t(
                        "Queue limit reached. Removed an older queue.",
                        "队列数量已达上限，已移除较旧队列"
                    ),
                    MAX_QUEUES
                ));
            }
        }
        let id = self.state.alloc_id();
        self.state.queues.push(Queue {
            id,
            name,
            songs: dedupe_songs(songs),
            index: 0,
            position: 0.0,
            updated: now_secs(),
        });
        self.dirty = true;
        id
    }

    pub fn new_queue_dialog(&mut self) {
        self.modal = Some(Modal::NewQueue {
            name: String::new(),
            songs: self.selected_or_current_keys(),
        });
    }

    pub fn selected_or_current_keys(&self) -> Vec<String> {
        if !self.selected.is_empty() {
            return self.selected.iter().cloned().collect();
        }
        self.active_queue()
            .map(|q| q.songs.clone())
            .unwrap_or_default()
    }

    pub fn switch_queue(&mut self, id: u64, resume: bool) {
        let old_position = self.position();
        if let Some(old) = self.state.active_queue_mut() {
            old.position = old_position;
        }
        let Some(queue) = self.state.queue_by_id(id) else {
            return;
        };
        let index = queue.index;
        let position = queue.position;
        let empty = queue.songs.is_empty();
        let current = queue.songs.get(index).cloned();
        self.state.active_queue = id;
        self.pending_next = None;
        if empty {
            self.notify(self.t("This queue is empty", "这个队列是空的").to_owned());
            self.current_key = None;
            self.playing = false;
            return;
        }
        if resume {
            self.start_index(index, position);
        } else {
            self.current_key = current;
            self.playing = false;
            self.fade = None;
            if let Some(audio) = &mut self.audio {
                audio.stop();
            }
        }
        self.dirty = true;
        self.save_now();
    }

    pub fn delete_queue(&mut self, id: u64) {
        self.state.queues.retain(|q| q.id != id);
        if self.state.active_queue == id {
            self.state.active_queue = self.state.queues.first().map(|q| q.id).unwrap_or(0);
            self.playing = false;
            self.pending_next = None;
            self.fade = None;
            if let Some(audio) = &mut self.audio {
                audio.stop();
            }
        }
        self.dirty = true;
        self.save_now();
    }

    pub fn rename_playlist(&mut self, id: u64, name: String) {
        let name = name.trim().to_owned();
        if name.is_empty() {
            return;
        }
        if self
            .state
            .playlists
            .iter()
            .any(|p| p.id != id && p.name.eq_ignore_ascii_case(&name))
        {
            self.notify(
                self.t(
                    "A playlist with this name already exists",
                    "已存在同名播放列表",
                )
                .to_owned(),
            );
            return;
        }
        if let Some(playlist) = self.state.playlists.iter_mut().find(|p| p.id == id) {
            playlist.name = name;
            playlist.updated = now_secs();
        }
        self.dirty = true;
        self.save_now();
    }

    pub fn rename_queue(&mut self, id: u64, name: String) {
        let name = name.trim().to_owned();
        if name.is_empty() {
            return;
        }
        if self
            .state
            .queues
            .iter()
            .any(|q| q.id != id && q.name == name)
        {
            self.notify(
                self.t("A queue with this name already exists", "已存在同名队列")
                    .to_owned(),
            );
            return;
        }
        if let Some(queue) = self.state.queues.iter_mut().find(|q| q.id == id) {
            queue.name = name;
            queue.updated = now_secs();
        }
        self.dirty = true;
        self.save_now();
    }

    pub fn remove_from_queue(&mut self, id: u64, key: &str) {
        if let Some(queue) = self.state.queues.iter_mut().find(|q| q.id == id) {
            if let Some(pos) = queue.songs.iter().position(|k| k == key) {
                queue.songs.remove(pos);
                if pos < queue.index {
                    queue.index = queue.index.saturating_sub(1);
                }
                if queue.index >= queue.songs.len() {
                    queue.index = queue.songs.len().saturating_sub(1);
                }
                queue.updated = now_secs();
            }
        }
        self.dirty = true;
    }

    pub fn move_in_queue(&mut self, id: u64, from: usize, to: usize) {
        if let Some(queue) = self.state.queues.iter_mut().find(|q| q.id == id) {
            if from < queue.songs.len() {
                let current_key = queue.songs.get(queue.index).cloned();
                let item = queue.songs.remove(from);
                let to = to.min(queue.songs.len());
                queue.songs.insert(to, item);
                if let Some(key) = current_key {
                    if let Some(index) = queue.songs.iter().position(|k| k == &key) {
                        queue.index = index;
                    }
                }
                queue.updated = now_secs();
            }
        }
        self.dirty = true;
    }

    pub fn switch_eq_device(&mut self, device: DeviceProfile) {
        self.state.settings.eq_device = device;
        let mut profile = self.state.eq_profile(device);
        profile.enabled = self.state.settings.eq_enabled;
        profile.touch();
        self.active_eq = profile.clone();
        if let Ok(mut guard) = self.eq_shared.write() {
            *guard = profile.clone();
        }
        self.state.set_eq_profile(device, profile);
        self.dirty = true;
    }

    pub fn set_eq_profile(&mut self, mut profile: EqProfile) {
        profile.enabled = self.state.settings.eq_enabled;
        profile.touch();
        self.active_eq = profile.clone();
        self.state
            .set_eq_profile(self.state.settings.eq_device, profile.clone());
        if let Ok(mut guard) = self.eq_shared.write() {
            *guard = profile;
        }
        self.dirty = true;
    }

    pub fn set_eq_enabled(&mut self, enabled: bool) {
        self.state.settings.eq_enabled = enabled;
        self.active_eq.enabled = enabled;
        self.active_eq.touch();
        self.state
            .set_eq_profile(self.state.settings.eq_device, self.active_eq.clone());
        if let Ok(mut guard) = self.eq_shared.write() {
            *guard = self.active_eq.clone();
        }
        self.dirty = true;
    }

    pub fn load_playlist_into_queue(&mut self, queue_id: u64, playlist_id: u64) {
        let Some(playlist) = self.state.playlist_by_id(playlist_id) else {
            return;
        };
        let songs = playlist.songs.clone();
        let policy = self.state.settings.queue_conflict;
        if let Some(queue) = self.state.queues.iter_mut().find(|q| q.id == queue_id) {
            match policy {
                ConflictPolicy::Replace => {
                    queue.songs = dedupe_songs(songs);
                    queue.index = 0;
                    queue.position = 0.0;
                }
                ConflictPolicy::Merge => {
                    let mut existing: HashSet<String> = queue.songs.iter().cloned().collect();
                    for key in songs {
                        if existing.insert(key.clone()) {
                            queue.songs.push(key);
                        }
                    }
                }
            }
            queue.updated = now_secs();
        }
        self.dirty = true;
        self.notify(
            self.t("Playlist loaded into queue", "播放列表已装载到队列")
                .to_owned(),
        );
    }

    pub fn add_to_queue(&mut self, queue_id: u64, keys: Vec<String>) {
        let valid: Vec<String> = keys
            .into_iter()
            .filter(|key| self.song_by_key(key).is_some())
            .collect();
        let mut added = 0;
        if let Some(queue) = self.state.queues.iter_mut().find(|q| q.id == queue_id) {
            let mut existing: HashSet<String> = queue.songs.iter().cloned().collect();
            for key in valid {
                if existing.insert(key.clone()) {
                    queue.songs.push(key);
                    added += 1;
                }
            }
            queue.updated = now_secs();
        }
        self.dirty = true;
        self.notify(format!(
            "{} {}",
            added,
            self.t("added to queue", "首歌曲已加入队列")
        ));
    }

    pub fn create_playlist(&mut self, name: String, songs: Vec<String>) {
        let name = name.trim().to_owned();
        if name.is_empty() {
            return;
        }
        if self
            .state
            .playlists
            .iter()
            .any(|p| p.name.eq_ignore_ascii_case(&name))
        {
            self.notify(
                self.t(
                    "A playlist with this name already exists",
                    "已存在同名播放列表",
                )
                .to_owned(),
            );
            return;
        }
        let id = self.state.alloc_id();
        let now = now_secs();
        self.state.playlists.push(Playlist {
            id,
            name,
            songs: dedupe_songs(songs),
            created: now,
            updated: now,
        });
        self.dirty = true;
        self.save_now();
    }

    pub fn add_to_playlist(&mut self, id: u64, keys: Vec<String>) {
        let valid: Vec<String> = keys
            .into_iter()
            .filter(|key| self.song_by_key(key).is_some())
            .collect();
        let mut added = 0;
        if let Some(playlist) = self.state.playlists.iter_mut().find(|p| p.id == id) {
            let mut existing: HashSet<String> = playlist.songs.iter().cloned().collect();
            for key in valid {
                if existing.insert(key.clone()) {
                    playlist.songs.push(key.clone());
                    added += 1;
                }
            }
            playlist.updated = now_secs();
        }
        self.dirty = true;
        self.notify(format!(
            "{} {}",
            added,
            self.t("songs added", "首歌曲已加入")
        ));
    }

    pub fn remove_from_playlist(&mut self, id: u64, key: &str) {
        if let Some(playlist) = self.state.playlists.iter_mut().find(|p| p.id == id) {
            playlist.songs.retain(|k| k != key);
            playlist.updated = now_secs();
        }
        self.dirty = true;
    }

    pub fn move_in_playlist(&mut self, id: u64, from: usize, to: usize) {
        if let Some(playlist) = self.state.playlists.iter_mut().find(|p| p.id == id) {
            if from < playlist.songs.len() {
                let item = playlist.songs.remove(from);
                let to = to.min(playlist.songs.len());
                playlist.songs.insert(to, item);
                playlist.updated = now_secs();
            }
        }
        self.dirty = true;
    }

    pub fn delete_playlist(&mut self, id: u64) {
        self.state.playlists.retain(|p| p.id != id);
        if matches!(self.detail, Some(DetailPage::Playlist(pid)) if pid == id) {
            self.detail = None;
            self.page = Page::Playlists;
        }
        self.dirty = true;
        self.save_now();
    }

    pub fn set_tags(&mut self, keys: Vec<String>, fields: TagEditorState) {
        let now = now_secs();
        for key in &keys {
            let extra = self.state.extra_mut(key);
            if !fields.title.trim().is_empty() {
                extra.title = Some(fields.title.trim().to_owned());
            }
            if !fields.artist.trim().is_empty() {
                extra.artist = Some(fields.artist.trim().to_owned());
            }
            if !fields.album.trim().is_empty() {
                extra.album = Some(fields.album.trim().to_owned());
            }
            if !fields.genre.trim().is_empty() {
                extra.genre = Some(fields.genre.trim().to_owned());
            }
            if !fields.year.trim().is_empty() {
                extra.year = Some(fields.year.trim().to_owned());
            }
            if !fields.track.trim().is_empty() {
                extra.track = fields.track.trim().parse().ok();
            }
            if let Some(cover) = &fields.cover {
                extra.cover = Some(cover.clone());
            }
            extra.last_played = extra.last_played.or(Some(now));
        }
        for song in &mut self.songs {
            if !keys.contains(&song.key) {
                continue;
            }
            if let Some(extra) = self.state.songs.get(&song.key) {
                if let Some(value) = &extra.title {
                    song.title = value.clone();
                }
                if let Some(value) = &extra.artist {
                    song.artist = value.clone();
                }
                if let Some(value) = &extra.album {
                    song.album = value.clone();
                }
                if let Some(value) = &extra.genre {
                    song.genre = value.clone();
                }
                if let Some(value) = &extra.year {
                    song.year = value.clone();
                }
                if let Some(value) = extra.track {
                    song.track = value;
                }
                if let Some(value) = &extra.cover {
                    song.cover_path = Some(PathBuf::from(value));
                }
                song.favorite = extra.favorite;
            }
        }
        self.cover_cache.clear();
        self.rebuild_groups();
        self.dirty = true;
        self.save_now();
        self.notify(self.t("Tags updated", "标签已更新").to_owned());
    }

    pub fn lyrics_offset_for(&self, key: &str) -> i64 {
        self.state.extra(key).lyrics_offset_ms
    }

    /// Positive values delay the lyrics. The value is stored per song, so
    /// different masters can keep different timing corrections.
    pub fn set_lyrics_offset(&mut self, key: &str, offset_ms: i64) {
        let cleaned = library::clamp_lyrics_offset(offset_ms);
        if self.state.extra(key).lyrics_offset_ms == cleaned {
            return;
        }
        self.state.extra_mut(key).lyrics_offset_ms = cleaned;
        self.mark_dirty();
    }

    pub fn save_lyrics_for(&mut self, key: &str, text: String) {
        let auto = !text.trim().is_empty();
        if let Some(song) = self.song_by_key(key).cloned() {
            if auto {
                let _ = library::save_lyrics(&song, &text);
            } else {
                let _ = library::remove_lyrics(&song);
            }
        }
        let extra = self.state.extra_mut(key);
        extra.lyrics = Some(text.clone());
        extra.lyrics_source = LyricsSource::User;
        extra.lyrics_fetched_at = 0;
        extra.lyrics_autofetch = auto;
        if let Some(song) = self.songs.iter_mut().find(|s| s.key == key) {
            song.lyrics_override = Some(text.clone());
            song.lyrics_source = LyricsSource::User;
            song.lyrics_autofetch = auto;
        }
        self.lyrics_status.remove(key);
        self.lyrics_candidates.remove(key);
        self.lyrics_inflight.remove(key);
        self.lyrics_fetch_cache.remove(key);
        self.lyrics = None;
        self.lyrics_for = None;
        self.dirty = true;
        self.save_now();
        self.notify(self.t("Lyrics saved", "歌词已保存").to_owned());
    }

    pub fn save_fetched_lyrics(
        &mut self,
        key: &str,
        text: &str,
        source: LyricsSource,
        persist_now: bool,
    ) {
        let text = text.trim().to_owned();
        if text.is_empty() {
            return;
        }
        if let Some(song) = self.song_by_key(key).cloned() {
            let _ = library::save_lyrics(&song, &text);
        }
        let extra = self.state.extra_mut(key);
        extra.lyrics = Some(text.clone());
        extra.lyrics_source = source;
        extra.lyrics_fetched_at = now_secs();
        extra.lyrics_autofetch = true;
        if let Some(song) = self.songs.iter_mut().find(|s| s.key == key) {
            song.lyrics_override = Some(text.clone());
            song.lyrics_source = source;
            song.lyrics_autofetch = true;
        }
        self.lyrics_fetch_cache.remove(key);
        self.lyrics_candidates.remove(key);
        if self.lyrics_candidate_request.as_deref() == Some(key) {
            self.lyrics_candidate_request = None;
        }
        self.lyrics_inflight.remove(key);
        self.lyrics_status.remove(key);
        if self.current_key.as_deref() == Some(key) {
            self.lyrics = Some(library::parse_lrc(&text));
            self.lyrics_for = Some(key.to_owned());
        }
        self.dirty = true;
        if persist_now {
            self.save_now();
        }
    }

    pub fn lyrics_status_for(&self, key: &str) -> LyricsStatus {
        self.lyrics_status
            .get(key)
            .cloned()
            .unwrap_or(LyricsStatus::Idle)
    }

    pub fn lyrics_source_for(&self, key: &str) -> LyricsSource {
        self.state
            .songs
            .get(key)
            .map(|extra| extra.lyrics_source)
            .unwrap_or(LyricsSource::Local)
    }

    fn set_lyrics_status(&mut self, key: &str, status: LyricsStatus) {
        if status == LyricsStatus::Idle {
            self.lyrics_status.remove(key);
        } else {
            self.lyrics_status.insert(key.to_owned(), status);
        }
    }

    fn cached_lyrics_status(&self, key: &str) -> Option<LyricsStatus> {
        let entry = self.lyrics_fetch_cache.fresh(key, now_secs())?;
        Some(match entry.status {
            lyrics_fetch::CachedStatus::NotFound => LyricsStatus::NotFound,
            lyrics_fetch::CachedStatus::Instrumental => LyricsStatus::Instrumental,
            lyrics_fetch::CachedStatus::Ambiguous => LyricsStatus::Ambiguous(entry.candidates),
            lyrics_fetch::CachedStatus::Error => LyricsStatus::Error(
                entry
                    .error
                    .clone()
                    .unwrap_or_else(|| "fetch failed".to_owned()),
            ),
        })
    }

    fn start_lrclib_fetch(&mut self, song: &Song) {
        if self.lyrics_inflight.contains(&song.key) {
            return;
        }
        self.lyrics_inflight.insert(song.key.clone());
        self.set_lyrics_status(&song.key, LyricsStatus::Fetching);
        self.lyrics_fetcher.send(FetchCommand::Lrclib {
            key: song.key.clone(),
            title: song.display_title().to_owned(),
            artist: song.display_artist().to_owned(),
            album: song.album.clone(),
            duration: song.duration,
        });
    }

    fn maybe_auto_fetch_lyrics(&mut self, song: &Song) {
        if !self.state.settings.online_lyrics {
            return;
        }
        if !self
            .state
            .songs
            .get(&song.key)
            .map(|extra| extra.lyrics_autofetch)
            .unwrap_or(true)
        {
            self.set_lyrics_status(&song.key, LyricsStatus::Idle);
            return;
        }
        if let Some(status) = self.cached_lyrics_status(&song.key) {
            self.set_lyrics_status(&song.key, status);
            return;
        }
        self.start_lrclib_fetch(song);
    }

    pub fn retry_lyrics_for(&mut self, key: &str) {
        let Some(song) = self.song_by_key(key).cloned() else {
            return;
        };
        if !self.state.settings.online_lyrics {
            self.notify(
                self.t(
                    "Enable online lyrics in Settings",
                    "请先在设置中开启在线歌词",
                )
                .to_owned(),
            );
            return;
        }
        self.state.extra_mut(key).lyrics_autofetch = true;
        if let Some(song) = self.songs.iter_mut().find(|song| song.key == key) {
            song.lyrics_autofetch = true;
        }
        self.lyrics_fetch_cache.remove(key);
        self.lyrics_candidates.remove(key);
        self.lyrics_candidate_request = None;
        self.set_lyrics_status(key, LyricsStatus::Idle);
        self.start_lrclib_fetch(&song);
    }

    pub fn open_lyrics_candidates(&mut self, key: &str) {
        let Some(song) = self.song_by_key(key).cloned() else {
            return;
        };
        if let Some(candidates) = self.lyrics_candidates.get(key).cloned() {
            if !candidates.is_empty() {
                self.lyrics_candidate_request = None;
                self.modal = Some(Modal::LyricsCandidates {
                    key: key.to_owned(),
                    title: song.display_title().to_owned(),
                    candidates,
                    selected: 0,
                });
                return;
            }
        }
        self.lyrics_candidate_request = Some(key.to_owned());
        self.notify(
            self.t("Searching LRCLIB again…", "正在重新搜索 LRCLIB…")
                .to_owned(),
        );
        self.start_lrclib_fetch(&song);
    }

    pub fn start_ai_lyrics(&mut self, key: &str) {
        let Some(song) = self.song_by_key(key).cloned() else {
            return;
        };
        if self.lyrics_inflight.contains(key) {
            return;
        }
        let Some(api_key) = secret::load_api_key() else {
            self.notify(
                self.t(
                    "Configure an AI provider and API key in Settings first.",
                    "请先在设置中配置 AI 供应商和 API Key。",
                )
                .to_owned(),
            );
            return;
        };
        let base_url = self.state.settings.ai_base_url.trim();
        let model = self.state.settings.ai_model.trim();
        if base_url.is_empty() || model.is_empty() {
            self.notify(
                self.t(
                    "Choose an AI provider and model in Settings first.",
                    "请先在设置中选择 AI 供应商和模型。",
                )
                .to_owned(),
            );
            return;
        }
        let Ok(base_url) = lyrics_fetch::normalize_base_url(base_url) else {
            self.notify(
                self.t("The AI base URL is invalid.", "AI 接口地址无效。")
                    .to_owned(),
            );
            return;
        };
        let config = lyrics_fetch::AiConfig {
            provider: self.state.settings.ai_provider.clone(),
            base_url,
            model: model.to_owned(),
            api_key,
        };
        crate::console::log(format!(
            "AI lyrics request: provider={} model={} title={:?} artist={:?}",
            config.provider,
            config.model,
            song.display_title(),
            song.display_artist()
        ));
        self.lyrics_inflight.insert(key.to_owned());
        self.set_lyrics_status(key, LyricsStatus::AiFetching);
        self.lyrics_fetcher.send(FetchCommand::Ai {
            key: key.to_owned(),
            config,
            title: song.display_title().to_owned(),
            artist: song.display_artist().to_owned(),
            album: song.album.clone(),
            duration: song.duration,
        });
    }

    pub fn ai_config_from_settings(&self) -> Result<lyrics_fetch::AiConfig, String> {
        let key = secret::load_api_key().ok_or_else(|| "请先保存 API Key".to_owned())?;
        let base_url = self.state.settings.ai_base_url.trim();
        if base_url.is_empty() {
            return Err("请填写接口地址".to_owned());
        }
        if self.state.settings.ai_model.trim().is_empty() {
            return Err("请选择或填写模型".to_owned());
        }
        Ok(lyrics_fetch::AiConfig {
            provider: self.state.settings.ai_provider.clone(),
            base_url: lyrics_fetch::normalize_base_url(base_url)?,
            model: self.state.settings.ai_model.trim().to_owned(),
            api_key: key,
        })
    }

    pub fn choose_ai_provider(&mut self, id: &str) {
        let preset = lyrics_fetch::find_ai_provider(id);
        self.state.settings.ai_provider = preset.id.to_owned();
        self.state.settings.ai_base_url = preset.base_url.to_owned();
        self.state.settings.ai_model = preset
            .models
            .first()
            .map(|model| (*model).to_owned())
            .unwrap_or_default();
        self.ai_models.clear();
        self.ai_status = None;
        self.mark_dirty();
    }

    pub fn save_ai_key(&mut self) {
        if self.ai_key_input.trim().is_empty() {
            return;
        }
        match secret::save_api_key(&self.ai_key_input) {
            Ok(()) => {
                self.ai_key_input.clear();
                self.ai_key_saved = true;
                self.ai_status = Some((true, self.t("API Key saved", "API Key 已保存").to_owned()));
            }
            Err(error) => {
                self.ai_status = Some((false, error));
            }
        }
    }

    pub fn clear_ai_key(&mut self) {
        secret::clear_api_key();
        self.ai_key_saved = false;
        self.ai_key_input.clear();
        self.ai_status = Some((true, self.t("API Key removed", "API Key 已移除").to_owned()));
    }

    pub fn fetch_ai_models(&mut self) {
        if self.ai_models_loading {
            return;
        }
        match self.ai_config_from_settings() {
            Ok(config) => {
                crate::console::log(format!(
                    "AI models request: provider={} model={} base={}",
                    config.provider, config.model, config.base_url
                ));
                self.ai_models_loading = true;
                self.ai_status = None;
                self.lyrics_fetcher.send(FetchCommand::AiModels { config });
            }
            Err(error) => {
                self.ai_status = Some((false, error));
            }
        }
    }

    pub fn test_ai_connection(&mut self) {
        if self.ai_testing {
            return;
        }
        match self.ai_config_from_settings() {
            Ok(config) => {
                crate::console::log(format!(
                    "AI connection test: provider={} model={} base={}",
                    config.provider, config.model, config.base_url
                ));
                self.ai_testing = true;
                self.ai_status = None;
                self.lyrics_fetcher.send(FetchCommand::AiTest { config });
            }
            Err(error) => {
                self.ai_status = Some((false, error));
            }
        }
    }

    pub fn clear_lyrics_fetch_cache(&mut self) {
        self.lyrics_fetch_cache.clear();
        self.lyrics_candidates.clear();
        self.lyrics_status.clear();
        if let Some(key) = self.current_key.clone() {
            if self.lyrics.is_none() {
                if let Some(song) = self.song_by_key(&key).cloned() {
                    self.maybe_auto_fetch_lyrics(&song);
                }
            }
        }
        self.notify(
            self.t("Lyrics fetch cache cleared", "歌词获取缓存已清理")
                .to_owned(),
        );
    }

    fn handle_lrclib_result(
        &mut self,
        key: &str,
        result: Result<Vec<lyrics_fetch::LrclibRecord>, String>,
    ) {
        self.lyrics_inflight.remove(key);
        let Some(song) = self.song_by_key(key).cloned() else {
            return;
        };
        match result {
            Ok(records) => {
                let ranked = lyrics_fetch::rank_candidates(
                    records,
                    song.display_title(),
                    song.display_artist(),
                    song.duration,
                );
                match lyrics_fetch::choose_match(ranked) {
                    lyrics_fetch::MatchOutcome::Best(candidate) => {
                        if let Some(text) = candidate.record.text() {
                            let text = text.to_owned();
                            self.save_fetched_lyrics(key, &text, LyricsSource::Lrclib, false);
                        } else {
                            self.lyrics_fetch_cache.record(
                                key,
                                lyrics_fetch::CachedStatus::NotFound,
                                0,
                                None,
                            );
                            self.set_lyrics_status(key, LyricsStatus::NotFound);
                        }
                    }
                    lyrics_fetch::MatchOutcome::Ambiguous(candidates) => {
                        let count = candidates.len();
                        let open_picker = self.lyrics_candidate_request.as_deref() == Some(key);
                        let modal_candidates = if open_picker {
                            Some(candidates.clone())
                        } else {
                            None
                        };
                        self.lyrics_candidates.insert(key.to_owned(), candidates);
                        self.lyrics_fetch_cache.record(
                            key,
                            lyrics_fetch::CachedStatus::Ambiguous,
                            count,
                            None,
                        );
                        self.set_lyrics_status(key, LyricsStatus::Ambiguous(count));
                        if let Some(candidates) = modal_candidates {
                            self.lyrics_candidate_request = None;
                            self.modal = Some(Modal::LyricsCandidates {
                                key: key.to_owned(),
                                title: song.display_title().to_owned(),
                                candidates,
                                selected: 0,
                            });
                        }
                    }
                    lyrics_fetch::MatchOutcome::Instrumental => {
                        if self.lyrics_candidate_request.as_deref() == Some(key) {
                            self.lyrics_candidate_request = None;
                            self.notify(
                                self.t(
                                    "This track is marked as instrumental; there are no versions to choose.",
                                    "这首歌被标记为纯音乐，没有可选择的版本。",
                                )
                                .to_owned(),
                            );
                        }
                        self.lyrics_fetch_cache.record(
                            key,
                            lyrics_fetch::CachedStatus::Instrumental,
                            0,
                            None,
                        );
                        self.set_lyrics_status(key, LyricsStatus::Instrumental);
                    }
                    lyrics_fetch::MatchOutcome::None => {
                        self.lyrics_fetch_cache.record(
                            key,
                            lyrics_fetch::CachedStatus::NotFound,
                            0,
                            None,
                        );
                        self.set_lyrics_status(key, LyricsStatus::NotFound);
                        if self.lyrics_candidate_request.as_deref() == Some(key) {
                            self.lyrics_candidate_request = None;
                            self.notify(
                                self.t(
                                    "No close lyrics versions were found.",
                                    "没有找到相近的歌词版本。",
                                )
                                .to_owned(),
                            );
                        }
                    }
                }
            }
            Err(error) => {
                if self.lyrics_candidate_request.as_deref() == Some(key) {
                    self.lyrics_candidate_request = None;
                }
                crate::console::log(format!("LRCLIB lookup failed for {key}: {error}"));
                self.lyrics_fetch_cache.record(
                    key,
                    lyrics_fetch::CachedStatus::Error,
                    0,
                    Some(error.clone()),
                );
                self.set_lyrics_status(key, LyricsStatus::Error(error));
            }
        }
    }

    fn handle_fetch_event(&mut self, event: FetchEvent) {
        match event {
            FetchEvent::Lrclib { key, result } => {
                self.handle_lrclib_result(&key, result);
            }
            FetchEvent::AiLyrics { key, result } => {
                self.lyrics_inflight.remove(&key);
                match result {
                    Ok(Some(text)) => {
                        let lines = text.lines().filter(|line| !line.trim().is_empty()).count();
                        crate::console::log(format!(
                            "AI lyrics ok: {key} chars={} lines={lines}",
                            text.len()
                        ));
                        let Some(song) = self.song_by_key(&key).cloned() else {
                            return;
                        };
                        let provider =
                            lyrics_fetch::find_ai_provider(&self.state.settings.ai_provider)
                                .name
                                .to_owned();
                        self.lyrics_status.remove(&key);
                        self.modal = Some(Modal::AiLyricsPreview {
                            key,
                            title: song.display_title().to_owned(),
                            provider,
                            text,
                        });
                    }
                    Ok(None) => {
                        crate::console::log(format!("AI lyrics not found: {key}"));
                        self.set_lyrics_status(&key, LyricsStatus::NotFound);
                        self.notify(
                            self.t(
                                "AI could not find usable lyrics for this track.",
                                "AI 没有返回可用的歌词。",
                            )
                            .to_owned(),
                        );
                    }
                    Err(error) => {
                        crate::console::log(format!("AI lyrics failed: {key}: {error}"));
                        self.set_lyrics_status(&key, LyricsStatus::Error(error.clone()));
                        self.notify(error);
                    }
                }
            }
            FetchEvent::AiModels { result } => {
                self.ai_models_loading = false;
                match result {
                    Ok(models) => {
                        crate::console::log(format!("AI models ok: {} available", models.len()));
                        self.ai_models = models;
                        self.ai_status = Some((
                            true,
                            format!(
                                "{} {}",
                                self.ai_models.len(),
                                self.t("model(s) available", "个可用模型")
                            ),
                        ));
                    }
                    Err(error) => {
                        crate::console::log(format!("AI models failed: {error}"));
                        self.ai_status = Some((false, error));
                    }
                }
            }
            FetchEvent::AiTest { result } => {
                self.ai_testing = false;
                match result {
                    Ok(message) => {
                        crate::console::log(format!("AI connection test ok: {message}"));
                        self.ai_status = Some((true, message));
                    }
                    Err(error) => {
                        crate::console::log(format!("AI connection test failed: {error}"));
                        self.ai_status = Some((false, error));
                    }
                }
            }
            FetchEvent::Skipped { key } => {
                self.lyrics_inflight.remove(&key);
                if self.lyrics_status_for(&key) == LyricsStatus::Fetching {
                    self.set_lyrics_status(&key, LyricsStatus::Idle);
                }
            }
        }
    }

    fn poll_lyrics_fetch(&mut self) {
        let mut events = Vec::new();
        while let Some(event) = self.lyrics_fetcher.try_recv() {
            events.push(event);
        }
        for event in events {
            self.handle_fetch_event(event);
        }
    }

    pub fn set_bookmark(&mut self, key: &str, note: String) {
        let position = if self.current_key.as_deref() == Some(key) {
            self.position()
        } else {
            0.0
        };
        let extra = self.state.extra_mut(key);
        extra.bookmarks.push(Bookmark {
            position,
            note,
            created: now_secs(),
        });
        self.dirty = true;
        self.save_now();
        self.notify(self.t("Bookmark added", "书签已添加").to_owned());
    }

    pub fn jump_to_bookmark(&mut self, key: &str, position: f64) {
        if let Some(queue) = self.active_queue() {
            if let Some(index) = queue.songs.iter().position(|k| k == key) {
                self.start_index(index, position);
                return;
            }
        }
        if let Some(key_index) = self.songs.iter().position(|s| s.key == key) {
            let keys: Vec<String> = self.songs.iter().map(|s| s.key.clone()).collect();
            let name = self.t("Bookmarks", "书签").to_owned();
            self.play_context(keys, key_index, &name);
            self.seek_to(position);
        }
    }

    pub fn toggle_selected(&mut self, key: &str) {
        if !self.selected.remove(key) {
            self.selected.insert(key.to_owned());
        }
    }

    pub fn select_all(&mut self, keys: Vec<String>) {
        if self.selected.len() == keys.len() && keys.iter().all(|k| self.selected.contains(k)) {
            self.selected.clear();
        } else {
            self.selected = keys.into_iter().collect();
        }
    }

    pub fn selection_or_current(&self) -> Vec<String> {
        if !self.selected.is_empty() {
            self.selected.iter().cloned().collect()
        } else {
            self.current_key.iter().cloned().collect()
        }
    }

    pub fn open_tag_editor(&mut self) {
        let keys = self.selection_or_current();
        if keys.is_empty() {
            return;
        }
        let first = self.song_by_key(&keys[0]);
        let (title, artist, album, genre, year, track, cover) = if let Some(song) = first {
            (
                if keys.len() == 1 {
                    song.title.clone()
                } else {
                    String::new()
                },
                if keys.len() == 1 {
                    song.artist.clone()
                } else {
                    String::new()
                },
                if keys.len() == 1 {
                    song.album.clone()
                } else {
                    String::new()
                },
                if keys.len() == 1 {
                    song.genre.clone()
                } else {
                    String::new()
                },
                if keys.len() == 1 {
                    song.year.clone()
                } else {
                    String::new()
                },
                if keys.len() == 1 && song.track > 0 {
                    song.track.to_string()
                } else {
                    String::new()
                },
                song.cover_path
                    .as_ref()
                    .map(|p| p.to_string_lossy().to_string()),
            )
        } else {
            (
                String::new(),
                String::new(),
                String::new(),
                String::new(),
                String::new(),
                String::new(),
                None,
            )
        };
        self.modal = Some(Modal::TagEditor(TagEditorState {
            keys,
            title,
            artist,
            album,
            genre,
            year,
            track,
            cover,
        }));
    }

    pub fn open_lyrics_editor(&mut self) {
        let Some(song) = self.current_song().cloned() else {
            return;
        };
        let text = self
            .lyrics
            .clone()
            .map(|lines| {
                lines
                    .iter()
                    .map(|(time, text)| {
                        let minutes = (*time / 60.0).floor() as u32;
                        let seconds = *time % 60.0;
                        format!("[{:02}:{:05.2}]{}\n", minutes, seconds, text)
                    })
                    .collect::<String>()
            })
            .or_else(|| library::load_lyrics(&song))
            .unwrap_or_default();
        self.modal = Some(Modal::LyricsEditor {
            key: song.key.clone(),
            text,
        });
    }

    pub fn add_library_folder(&mut self) {
        if let Some(folder) = rfd::FileDialog::new().pick_folder() {
            let canonical = canonical_clean(&folder);
            let text = canonical.to_string_lossy().to_string();
            if !self.state.library_roots.iter().any(|r| r == &text) {
                self.state.library_roots.push(text);
                self.dirty = true;
                self.save_now();
                self.start_scan();
            }
        }
    }

    pub fn add_excluded_folder(&mut self) {
        if let Some(folder) = rfd::FileDialog::new().pick_folder() {
            let canonical = canonical_clean(&folder);
            let text = canonical.to_string_lossy().to_string();
            if !self.state.excluded.iter().any(|r| r == &text) {
                self.state.excluded.push(text);
                self.dirty = true;
                self.save_now();
                self.start_scan();
            }
        }
    }

    pub fn add_included_folder(&mut self) {
        if let Some(folder) = rfd::FileDialog::new().pick_folder() {
            let canonical = canonical_clean(&folder);
            let text = canonical.to_string_lossy().to_string();
            if !self.state.included.iter().any(|r| r == &text) {
                self.state.included.push(text);
                self.dirty = true;
                self.save_now();
                self.start_scan();
            }
        }
    }

    pub fn export_queue_m3u(&mut self, id: u64) {
        let Some(queue) = self.state.queue_by_id(id) else {
            return;
        };
        let default_name = format!("{}.m3u", sanitize_file(&queue.name));
        let Some(path) = rfd::FileDialog::new()
            .set_file_name(&default_name)
            .add_filter("M3U playlist", &["m3u", "m3u8"])
            .save_file()
        else {
            return;
        };
        let songs: Vec<Song> = queue
            .songs
            .iter()
            .filter_map(|key| self.song_by_key(key).cloned())
            .collect();
        match library::export_m3u(&path, &songs) {
            Ok(()) => self.notify(self.t("Playlist exported", "已导出").to_owned()),
            Err(error) => self.notify(format!("Export failed: {error}")),
        }
    }

    pub fn export_playlist_m3u(&mut self, id: u64) {
        let Some(playlist) = self.state.playlist_by_id(id) else {
            return;
        };
        let default_name = format!("{}.m3u", sanitize_file(&playlist.name));
        let Some(path) = rfd::FileDialog::new()
            .set_file_name(&default_name)
            .add_filter("M3U playlist", &["m3u", "m3u8"])
            .save_file()
        else {
            return;
        };
        let songs: Vec<Song> = playlist
            .songs
            .iter()
            .filter_map(|key| self.song_by_key(key).cloned())
            .collect();
        match library::export_m3u(&path, &songs) {
            Ok(()) => self.notify(self.t("Playlist exported", "已导出").to_owned()),
            Err(error) => self.notify(format!("Export failed: {error}")),
        }
    }

    pub fn import_m3u(&mut self) {
        let Some(path) = rfd::FileDialog::new()
            .add_filter("M3U playlist", &["m3u", "m3u8"])
            .pick_file()
        else {
            return;
        };
        let paths = library::parse_m3u(&path);
        let matched: Vec<String> = paths
            .iter()
            .filter_map(|path| {
                let key = normalize_key(&canonical_clean(path));
                self.songs
                    .iter()
                    .find(|s| s.key == key)
                    .map(|s| s.key.clone())
            })
            .collect();
        if matched.is_empty() {
            self.notify(
                self.t(
                    "No songs from this playlist were found in the library",
                    "播放列表中的歌曲均不在曲库中",
                )
                .to_owned(),
            );
            return;
        }
        let name = path
            .file_stem()
            .and_then(|s| s.to_str())
            .unwrap_or("Imported playlist")
            .to_owned();
        self.create_playlist(name, matched);
        self.notify(self.t("Playlist imported", "播放列表已导入").to_owned());
    }

    pub fn export_most_played_csv(&mut self) {
        let Some(path) = rfd::FileDialog::new()
            .set_file_name("most-played.csv")
            .add_filter("CSV", &["csv"])
            .save_file()
        else {
            return;
        };
        let mut songs: Vec<Song> = self.songs.clone();
        songs.sort_by(|a, b| b.play_count.cmp(&a.play_count));
        match library::export_csv(&path, &songs) {
            Ok(()) => self.notify(self.t("CSV exported", "CSV 已导出").to_owned()),
            Err(error) => self.notify(format!("Export failed: {error}")),
        }
    }

    pub fn export_backup(&mut self) {
        self.save_now();
        let backup = backup::make_backup(&self.state);
        let Some(path) = rfd::FileDialog::new()
            .set_file_name("lilt-backup.json")
            .add_filter("Lilt backup", &["json"])
            .save_file()
        else {
            return;
        };
        let path = if path.extension().is_none() {
            path.with_extension("json")
        } else {
            path
        };
        let result = backup::to_json(&backup).and_then(|text| {
            std::fs::write(&path, text)
                .map_err(|error| format!("Could not write the backup: {error}"))
        });
        match result {
            Ok(()) => {
                let name = path
                    .file_name()
                    .map(|value| value.to_string_lossy().to_string())
                    .unwrap_or_else(|| path.display().to_string());
                self.notify(format!(
                    "{} {name}",
                    self.t("Backup saved:", "备份已保存：")
                ));
            }
            Err(error) => self.notify(error),
        }
    }

    pub fn choose_backup_to_restore(&mut self) {
        let Some(path) = rfd::FileDialog::new()
            .add_filter("Lilt backup", &["json"])
            .pick_file()
        else {
            return;
        };
        let backup = match backup::read_file(&path) {
            Ok(backup) => backup,
            Err(error) => {
                self.notify(error);
                return;
            }
        };
        let name = path
            .file_name()
            .map(|value| value.to_string_lossy().to_string())
            .unwrap_or_else(|| path.display().to_string());
        let body = format!(
            "{} {name}\n\n{}\n\n{}",
            self.t("File:", "文件："),
            backup::describe(&backup.data),
            self.t(
                "This replaces the playlists, queues, favourites, lyrics, bookmarks and settings currently on this device. The music files themselves are not changed.",
                "这会替换本机现有的播放列表、队列、收藏、歌词、书签与设置；音频文件本身不会被修改。",
            ),
        );
        self.modal = Some(Modal::Confirm {
            title: self.t("Restore this backup?", "恢复此备份？").to_owned(),
            body,
            action: ConfirmAction::RestoreBackup(path.to_string_lossy().to_string()),
        });
    }

    pub fn restore_backup(&mut self, path: &str) {
        let backup = match backup::read_file(Path::new(path)) {
            Ok(backup) => backup,
            Err(error) => {
                self.notify(error);
                return;
            }
        };
        // A scan started with the old state cannot be reused. Drop its
        // receiver; the worker only sends progress and exits on its own.
        self.scan_rx = None;
        self.scanning = false;
        if let Some(audio) = &mut self.audio {
            audio.stop();
        }
        self.playing = false;
        self.fade = None;
        self.current_key = None;
        self.pending_next = None;
        self.lyrics = None;
        self.lyrics_for = None;
        self.selected.clear();
        self.detail = None;
        self.modal = None;
        let current_roots = self.state.library_roots.clone();
        let summary = backup::apply_backup(&mut self.state, backup, &current_roots);
        let mut eq = self.state.eq_profile(self.state.settings.eq_device);
        eq.enabled = self.state.settings.eq_enabled;
        self.active_eq = eq.clone();
        if let Ok(mut guard) = self.eq_shared.write() {
            *guard = eq;
        }
        if let Some(audio) = &mut self.audio {
            audio.set_volume(self.state.settings.volume);
            audio.set_speed(self.state.settings.playback_speed);
        }
        crate::console::set_enabled(self.state.settings.developer_mode);
        self.dirty = true;
        self.start_scan();
        self.save_now();
        self.notify(format!(
            "{} {} {}, {} {}",
            self.t("Backup restored:", "备份已恢复："),
            summary.playlists,
            self.t("playlists", "个播放列表"),
            summary.queues,
            self.t("queues", "个队列"),
        ));
    }

    pub fn missing_file_count(&self) -> usize {
        let available: HashSet<&str> = self.songs.iter().map(|s| s.key.as_str()).collect();
        let mut keys: HashSet<&str> = HashSet::new();
        for playlist in &self.state.playlists {
            for key in &playlist.songs {
                if !available.contains(key.as_str()) {
                    keys.insert(key);
                }
            }
        }
        for queue in &self.state.queues {
            for key in &queue.songs {
                if !available.contains(key.as_str()) {
                    keys.insert(key);
                }
            }
        }
        for (key, extra) in &self.state.songs {
            let has_user_data = extra.favorite
                || extra.play_count > 0
                || extra.lyrics.is_some()
                || !extra.bookmarks.is_empty();
            if has_user_data && !available.contains(key.as_str()) {
                keys.insert(key);
            }
        }
        keys.len()
    }

    pub fn open_missing_files(&mut self) {
        let files = self.missing_files();
        self.modal = Some(Modal::MissingFiles(files));
    }

    /// Groups files that share normalised tags and a similar length. The best
    /// copy sorts first inside each group.
    pub fn open_duplicates(&mut self) {
        let candidates: Vec<duplicates::DuplicateCandidate> = self
            .songs
            .iter()
            .map(|song| duplicates::DuplicateCandidate {
                title: song.display_title().to_owned(),
                artist: song.artist.clone(),
                duration: song.duration,
                size: song.size,
                bitrate: song.bitrate,
            })
            .collect();
        let groups = duplicates::find_duplicate_groups(&candidates);
        let views = groups
            .into_iter()
            .map(|group| {
                group
                    .songs
                    .into_iter()
                    .filter_map(|index| self.songs.get(index).cloned())
                    .collect()
            })
            .filter(|group: &Vec<Song>| group.len() >= 2)
            .collect();
        self.modal = Some(Modal::Duplicates(views));
    }

    /// Playlist, queue and user-data references that no longer resolve to a
    /// file on disk. Playlists keep their keys across rescans on purpose so a
    /// moved file can be pointed at its new path.
    pub fn missing_files(&self) -> Vec<MissingFile> {
        let available: HashSet<String> = self
            .songs
            .iter()
            .filter(|song| song.exists())
            .map(|song| song.key.clone())
            .collect();
        let mut entries: HashMap<String, MissingFile> = HashMap::new();
        for playlist in &self.state.playlists {
            for key in &playlist.songs {
                if available.contains(key) {
                    continue;
                }
                let entry = entries
                    .entry(key.clone())
                    .or_insert_with(|| missing_file_entry(&self.state, key));
                if !entry.playlists.contains(&playlist.name) {
                    entry.playlists.push(playlist.name.clone());
                }
            }
        }
        for queue in &self.state.queues {
            for key in &queue.songs {
                if available.contains(key) {
                    continue;
                }
                let entry = entries
                    .entry(key.clone())
                    .or_insert_with(|| missing_file_entry(&self.state, key));
                if !entry.queues.contains(&queue.name) {
                    entry.queues.push(queue.name.clone());
                }
            }
        }
        for song in &self.songs {
            if song.exists() {
                continue;
            }
            entries.entry(song.key.clone()).or_insert_with(|| {
                let mut entry = missing_file_entry(&self.state, &song.key);
                entry.title = song.display_title().to_owned();
                entry.path = song.path.to_string_lossy().to_string();
                entry
            });
        }
        for (key, extra) in &self.state.songs {
            if available.contains(key) {
                continue;
            }
            let has_user_data = extra.favorite
                || extra.play_count > 0
                || extra.lyrics.is_some()
                || !extra.bookmarks.is_empty();
            if has_user_data {
                entries
                    .entry(key.clone())
                    .or_insert_with(|| missing_file_entry(&self.state, key));
            }
        }
        let mut out: Vec<MissingFile> = entries.into_values().collect();
        out.sort_by(|a, b| a.title.to_lowercase().cmp(&b.title.to_lowercase()));
        out
    }

    pub fn relink_missing_file(&mut self, old_key: &str) {
        let file_name = Path::new(old_key)
            .file_name()
            .and_then(|name| name.to_str())
            .unwrap_or("audio");
        let mut dialog = rfd::FileDialog::new()
            .add_filter("Audio", crate::library::AUDIO_EXTENSIONS)
            .set_file_name(file_name);
        if let Some(parent) = Path::new(old_key).parent() {
            if parent.is_dir() {
                dialog = dialog.set_directory(parent);
            }
        }
        let Some(path) = dialog.pick_file() else {
            return;
        };
        match self.relink_missing_file_to(old_key, &path) {
            Ok(()) => self.notify(self.t("File re-linked", "文件已重新关联").to_owned()),
            Err(error) => self.notify(error),
        }
    }

    /// Replaces every reference to `old_key` with the chosen local audio file.
    /// The file must be inside one of the configured library roots so the next
    /// scan keeps it in the library.
    pub fn relink_missing_file_to(&mut self, old_key: &str, path: &Path) -> Result<(), String> {
        if !library::is_audio(path) {
            return Err(self
                .t(
                    "Choose an audio file Lilt can scan.",
                    "请选择 Lilt 可以扫描的音频文件。",
                )
                .to_owned());
        }
        let canonical = canonical_clean(path);
        if !canonical.is_file() {
            return Err(self
                .t("That file no longer exists.", "该文件已不存在。")
                .to_owned());
        }
        let roots: Vec<PathBuf> = self
            .state
            .library_roots
            .iter()
            .map(|root| canonical_clean(Path::new(root)))
            .collect();
        if !roots.is_empty() && !roots.iter().any(|root| path_under(&canonical, root)) {
            return Err(self
                .t(
                    "Choose a file inside one of the library folders.",
                    "请选择曲库文件夹内的文件。",
                )
                .to_owned());
        }
        let new_key = normalize_key(&canonical);
        let mut extras = self.state.songs.clone();
        if let Some(extra) = extras.remove(old_key) {
            extras.entry(new_key.clone()).or_insert(extra);
        }
        let Some(song) = library::read_song(&canonical, &extras) else {
            return Err(self
                .t(
                    "That file could not be read as audio.",
                    "无法将该文件读取为音频。",
                )
                .to_owned());
        };
        apply_relink(
            &mut self.state,
            &mut self.songs,
            &mut self.current_key,
            &mut self.selected,
            old_key,
            song,
        );
        self.rebuild_groups();
        self.folders = FolderIndex::build(&self.songs, &roots);
        self.dirty = true;
        self.save_now();
        Ok(())
    }

    pub fn folder_song_keys(&self, path: &Path) -> Vec<String> {
        self.songs
            .iter()
            .filter(|s| path_under(&s.path, path))
            .map(|s| s.key.clone())
            .collect()
    }

    pub fn filtered_indices(&self) -> Vec<usize> {
        filter_and_sort(
            &self.songs,
            &self.search,
            self.sort,
            self.sort_desc,
            None,
            None,
        )
    }

    pub fn search_results(&self) -> SearchResults {
        let query = self.search.trim().to_lowercase();
        let mut songs = Vec::new();
        let mut albums = Vec::new();
        let mut artists = Vec::new();
        let mut folders = Vec::new();
        let mut playlists = Vec::new();
        if query.is_empty() {
            return SearchResults::default();
        }
        for (index, song) in self.songs.iter().enumerate() {
            if song.display_title().to_lowercase().contains(&query)
                || song.display_artist().to_lowercase().contains(&query)
                || song.display_album().to_lowercase().contains(&query)
                || song.genre.to_lowercase().contains(&query)
            {
                songs.push(index);
            }
        }
        for (index, album) in self.albums.iter().enumerate() {
            if album.name.to_lowercase().contains(&query)
                || album.artist.to_lowercase().contains(&query)
            {
                albums.push(index);
            }
        }
        for (index, artist) in self.artists.iter().enumerate() {
            if artist.name.to_lowercase().contains(&query) {
                artists.push(index);
            }
        }
        for info in self.folders.folders.values() {
            if info.name.to_lowercase().contains(&query) {
                folders.push(info.path.clone());
            }
        }
        for playlist in &self.state.playlists {
            if playlist.name.to_lowercase().contains(&query) {
                playlists.push(playlist.id);
            }
        }
        songs.sort_by(|a, b| {
            self.songs[*a]
                .display_title()
                .to_lowercase()
                .cmp(&self.songs[*b].display_title().to_lowercase())
        });
        albums.sort_by(|a, b| self.albums[*a].name.cmp(&self.albums[*b].name));
        artists.sort_by(|a, b| self.artists[*a].name.cmp(&self.artists[*b].name));
        folders.sort_by_key(|p| p.file_name().map(|n| n.to_string_lossy().to_lowercase()));
        SearchResults {
            songs,
            albums,
            artists,
            folders,
            playlists,
        }
    }

    pub fn navigate(&mut self, page: Page) {
        self.page = page;
        self.detail = None;
        self.folder_path = None;
        self.selected.clear();
        self.search.clear();
        self.now_playing = false;
        self.queue_drawer = false;
        self.scroll_to_current = false;
    }

    /// Opens the complete song list and asks the table to scroll the playing
    /// row into view. The flag is consumed by `song_table` so later refreshes
    /// do not move the list again.
    pub fn locate_current_song(&mut self) {
        if self.current_song().is_none() {
            self.notify(self.t("Nothing is playing", "暂无播放歌曲").to_owned());
            return;
        }
        self.navigate(Page::Songs);
        self.favorites_only = false;
        self.selection_mode = false;
        self.jump_letter = None;
        self.scroll_to_current = true;
    }

    pub fn open_album(&mut self, name: String) {
        self.detail = Some(DetailPage::Album(name));
        self.search.clear();
    }

    pub fn open_artist(&mut self, name: String) {
        self.detail = Some(DetailPage::Artist(name));
        self.search.clear();
    }

    pub fn open_playlist(&mut self, id: u64) {
        self.detail = Some(DetailPage::Playlist(id));
        self.search.clear();
    }

    pub fn open_queue(&mut self, id: u64) {
        self.detail = Some(DetailPage::Queue(id));
        self.search.clear();
    }

    pub fn open_folder(&mut self, path: PathBuf) {
        self.page = Page::Folders;
        self.detail = None;
        self.folder_path = Some(path);
        self.selected.clear();
        self.search.clear();
    }

    pub fn go_back(&mut self) {
        if self.detail.is_some() {
            self.detail = None;
        } else if self.folder_path.is_some() {
            let parent = self
                .folder_path
                .as_ref()
                .and_then(|p| p.parent())
                .map(|p| p.to_path_buf());
            if let Some(parent) = parent {
                if self
                    .state
                    .library_roots
                    .iter()
                    .any(|root| Path::new(root) == &parent)
                {
                    self.folder_path = Some(parent);
                } else {
                    self.folder_path = None;
                }
            } else {
                self.folder_path = None;
            }
        }
    }

    pub fn breadcrumb(&self) -> Vec<(String, Option<PathBuf>)> {
        let mut chain = vec![(self.t("Library", "曲库").to_owned(), None)];
        let Some(path) = &self.folder_path else {
            return chain;
        };
        if let Some(root) = self
            .state
            .library_roots
            .iter()
            .map(|r| canonical_clean(&PathBuf::from(r)))
            .find(|r| path_under(path, r))
        {
            chain.push((
                root.file_name()
                    .map(|n| n.to_string_lossy().to_string())
                    .unwrap_or_else(|| "Library".to_owned()),
                Some(root.clone()),
            ));
            let mut current = root.clone();
            if let Ok(rest) = path.strip_prefix(&root) {
                for component in rest.components() {
                    current.push(component);
                    chain.push((
                        component.as_os_str().to_string_lossy().to_string(),
                        Some(current.clone()),
                    ));
                }
            }
            if let Some(last) = chain.last_mut() {
                last.1 = None;
            }
        }
        chain
    }

    pub fn load_cover_texture(
        &mut self,
        ctx: &egui::Context,
        song: &Song,
    ) -> Option<egui::TextureHandle> {
        let key = format!(
            "album:{}:{}",
            song.album.to_lowercase(),
            song.cover_path
                .as_ref()
                .map(|p| p.to_string_lossy().to_string())
                .unwrap_or_default()
        );
        if let Some(cached) = self.cover_cache.get(&key) {
            return cached.clone();
        }
        let bytes = if let Some(path) = &song.cover_path {
            std::fs::read(path).ok()
        } else {
            library::read_cover(&song.path)
        };
        let texture = bytes.and_then(|bytes| {
            let image = image::load_from_memory(&bytes).ok()?.to_rgba8();
            let size = [image.width() as usize, image.height() as usize];
            let pixels = image.into_raw();
            let color = egui::ColorImage::from_rgba_unmultiplied(size, &pixels);
            Some(ctx.load_texture(&key, color, egui::TextureOptions::LINEAR))
        });
        self.cover_cache.insert(key, texture.clone());
        texture
    }

    pub fn clear_cover_cache(&mut self) {
        self.cover_cache.clear();
    }

    fn media_info(&self) -> MediaInfo {
        let song = self.current_song();
        let duration = song.map(|song| song.duration).unwrap_or(0.0);
        MediaInfo {
            title: song
                .map(|song| song.display_title().to_owned())
                .unwrap_or_default(),
            artist: song
                .map(|song| song.display_artist().to_owned())
                .unwrap_or_default(),
            album: song
                .map(|song| song.display_album().to_owned())
                .unwrap_or_default(),
            playing: self.playing,
            position: self.position().min(duration),
            duration,
            shuffle: self.shuffle,
            repeat: self.repeat,
        }
    }

    fn companion_snapshot(&mut self, ctx: &egui::Context) -> CompanionSnapshot {
        let current = self.current_song().cloned();
        let cover = current
            .as_ref()
            .and_then(|song| self.load_cover_texture(ctx, song));
        let lyrics_key = current.as_ref().map(|song| song.key.clone());
        let lyrics_offset_ms = lyrics_key
            .as_deref()
            .map(|key| self.lyrics_offset_for(key))
            .unwrap_or(0);
        let lyrics = self.lyrics.clone().unwrap_or_default();
        let duration = current.as_ref().map(|song| song.duration).unwrap_or(0.0);
        let played_color = self
            .state
            .settings
            .desktop_lyrics_played_color
            .map(|[r, g, b]| egui::Color32::from_rgb(r, g, b))
            .unwrap_or(self.palette.accent);
        let unplayed_color = self
            .state
            .settings
            .desktop_lyrics_unplayed_color
            .map(|[r, g, b]| egui::Color32::from_rgb(r, g, b))
            .unwrap_or_else(|| egui::Color32::from_rgb(0xF8, 0xF3, 0xEA));
        CompanionSnapshot {
            language: self.lang(),
            palette: self.palette,
            title: current
                .as_ref()
                .map(|song| song.display_title().to_owned())
                .unwrap_or_else(|| self.t("Nothing playing", "暂无播放").to_owned()),
            artist: current
                .as_ref()
                .map(|song| song.display_artist().to_owned())
                .unwrap_or_default(),
            album: current
                .as_ref()
                .map(|song| song.display_album().to_owned())
                .unwrap_or_default(),
            playing: self.playing,
            has_song: current.is_some(),
            favorite: current.as_ref().is_some_and(|song| song.favorite),
            position: self.position().min(duration.max(0.0)),
            duration,
            volume: self.state.settings.volume,
            shuffle: self.shuffle,
            repeat: self.repeat,
            lyrics,
            lyrics_offset_ms,
            lyrics_opacity: self.state.settings.lyrics_opacity,
            desktop_lyrics_font_size: self.state.settings.desktop_lyrics_font_size,
            desktop_lyrics_played_color: played_color,
            desktop_lyrics_unplayed_color: unplayed_color,
            lyrics_locked: self.state.settings.desktop_lyrics_locked,
            cover,
        }
    }

    fn handle_companion_actions(&mut self, ctx: &egui::Context) {
        for action in self.companions.take_actions() {
            desktop::log_line(&format!("companion {action:?}"));
            match action {
                CompanionAction::TogglePlay => self.toggle_play(),
                CompanionAction::Play => {
                    if !self.playing {
                        self.toggle_play();
                    }
                }
                CompanionAction::Pause => {
                    if self.playing {
                        self.toggle_play();
                    }
                }
                CompanionAction::Previous => self.prev_track(),
                CompanionAction::Next => self.next_track(false),
                CompanionAction::Seek(seconds) => self.seek_to(seconds),
                CompanionAction::ToggleShuffle => self.toggle_shuffle(),
                CompanionAction::CycleRepeat => self.cycle_repeat(),
                CompanionAction::ToggleFavorite => {
                    if let Some(key) = self.current_key.clone() {
                        self.toggle_favorite(&key);
                    }
                }
                CompanionAction::ShowMain => {
                    self.state.settings.mini_player = false;
                    self.mark_dirty();
                    self.show_main_window(ctx);
                }
                CompanionAction::CloseMini => {
                    self.state.settings.mini_player = false;
                    self.mark_dirty();
                }
                CompanionAction::CloseLyrics => {
                    self.state.settings.desktop_lyrics = false;
                    self.mark_dirty();
                }
                CompanionAction::ToggleLyricsLock => self.toggle_desktop_lyrics_lock(),
                CompanionAction::OpenNowPlaying => {
                    self.state.settings.mini_player = false;
                    self.now_playing = true;
                    self.mark_dirty();
                    self.show_main_window(ctx);
                }
            }
        }
    }

    fn update_companions(&mut self, ctx: &egui::Context) {
        let lyrics_visible = self.state.settings.desktop_lyrics && self.screenshot.is_none();
        let mini_visible = self.state.settings.mini_player && self.screenshot.is_none();
        if !lyrics_visible && !mini_visible {
            self.companions.sync(None, ctx, false, false);
            return;
        }
        let snapshot = self.companion_snapshot(ctx);
        self.companions
            .sync(Some(snapshot), ctx, lyrics_visible, mini_visible);
    }

    /// The hidden heartbeat must reflect state changed later in the frame (for
    /// example a companion action that starts playback or marks a save dirty).
    fn sync_hidden_window_state(&self) {
        let busy = self.hidden_to_tray
            && (self.playing
                || self.scanning
                || self.scan_rx.is_some()
                || self.sleep_deadline.is_some()
                || self.dirty);
        if let Some(desktop) = &self.desktop {
            desktop.set_hidden(self.hidden_to_tray);
            desktop.set_heartbeat(busy);
        }
    }

    /// Tray menu, tray clicks and registered shortcuts all arrive here.
    /// Actions are queued by the native callbacks and handled on the UI thread.
    fn update_desktop(&mut self, ctx: &egui::Context, frame: &eframe::Frame) {
        if self.desktop.is_none()
            && self.screenshot.is_none()
            && self.desktop_attempts < 3
            && Instant::now() >= self.desktop_next_try
        {
            self.desktop_attempts += 1;
            match DesktopIntegration::new(ctx, self.lang(), self.state.settings.global_hotkeys) {
                Ok(desktop) => {
                    self.desktop = Some(desktop);
                    self.desktop_error = None;
                }
                Err(error) => {
                    crate::console::log(format!("Desktop integration unavailable: {error}"));
                    self.desktop_error = Some(error);
                    self.desktop_next_try = Instant::now() + Duration::from_secs(2);
                }
            }
        }

        #[cfg(target_os = "windows")]
        {
            use raw_window_handle::{HasWindowHandle, RawWindowHandle};
            if let Ok(handle) = frame.window_handle() {
                if let RawWindowHandle::Win32(win32) = handle.as_raw() {
                    let hwnd = win32.hwnd.get();
                    self.companions.set_main_window(hwnd);
                    if let Some(desktop) = self.desktop.as_mut() {
                        desktop.set_main_window(hwnd);
                    }
                }
            }
        }
        #[cfg(not(target_os = "windows"))]
        let _ = frame;

        if self.desktop.is_some() {
            if let Some(desktop) = &mut self.desktop {
                desktop.set_hotkeys_enabled(self.state.settings.global_hotkeys);
                self.hotkey_error = desktop.hotkey_error().map(str::to_owned);
                self.media_error = desktop.media_error().map(str::to_owned);
            }
            let pending = self
                .desktop
                .as_ref()
                .map(DesktopIntegration::pending_actions)
                .unwrap_or(0);
            if pending > 0 {
                desktop::log_line(&format!("polling {pending} desktop action(s)"));
            }
            let actions = self
                .desktop
                .as_mut()
                .map(|desktop| desktop.poll_actions())
                .unwrap_or_default();
            for action in actions {
                self.handle_desktop_action(ctx, action);
            }
            let track = self.current_song().map(|song| {
                let title = song.display_title();
                let artist = song.display_artist();
                if artist.is_empty() {
                    title.to_owned()
                } else {
                    format!("{title} — {artist}")
                }
            });
            let language = self.lang();
            let playing = self.playing;
            let desktop_lyrics = self.state.settings.desktop_lyrics;
            let lyrics_locked = self.state.settings.desktop_lyrics_locked;
            let mini_player = self.state.settings.mini_player;
            let media = self.media_info();
            if let Some(desktop) = &mut self.desktop {
                desktop.sync(
                    language,
                    playing,
                    track.as_deref(),
                    desktop_lyrics,
                    lyrics_locked,
                    mini_player,
                );
                desktop.sync_media(&media);
            }
        }

        let tray_ready = self
            .desktop
            .as_ref()
            .is_some_and(|desktop| desktop.tray_ready());
        if self.desktop.is_some() {
            self.desktop_error = if tray_ready {
                None
            } else {
                Some(
                    self.t(
                        "The tray icon could not be registered, so close-to-tray stays off.",
                        "托盘图标注册失败，关闭到托盘暂不可用。",
                    )
                    .to_owned(),
                )
            };
        }

        let minimized = ctx.input(|input| input.viewport().minimized.unwrap_or(false));
        if minimized
            && !self.was_minimized
            && !self.hidden_to_tray
            && tray_ready
            && self.state.settings.minimize_to_tray
        {
            self.hide_to_tray(ctx);
        }
        self.was_minimized = minimized || self.hidden_to_tray;

        if ctx.input(|input| input.viewport().close_requested())
            && !self.quitting
            && !self.close_after_screenshot
            && self.state.settings.close_to_tray
            && tray_ready
        {
            ctx.send_viewport_cmd(egui::ViewportCommand::CancelClose);
            self.hide_to_tray(ctx);
        }
    }

    fn handle_desktop_action(&mut self, ctx: &egui::Context, action: DesktopAction) {
        desktop::log_line(&format!(
            "apply {action:?} playing={} volume={:.2}",
            self.playing, self.state.settings.volume
        ));
        match action {
            DesktopAction::Show => self.show_main_window(ctx),
            DesktopAction::Play => {
                if !self.playing {
                    self.toggle_play();
                }
            }
            DesktopAction::Pause => {
                if self.playing {
                    self.toggle_play();
                }
            }
            DesktopAction::TogglePlay => self.toggle_play(),
            DesktopAction::Previous => self.prev_track(),
            DesktopAction::Next => self.next_track(false),
            DesktopAction::VolumeUp => self.adjust_volume(0.05),
            DesktopAction::VolumeDown => self.adjust_volume(-0.05),
            DesktopAction::ToggleDesktopLyrics => self.toggle_desktop_lyrics(),
            DesktopAction::ToggleLyricsLock => self.toggle_desktop_lyrics_lock(),
            DesktopAction::ToggleMiniPlayer => self.toggle_mini_player(),
            DesktopAction::Quit => {
                self.quitting = true;
                self.save_now();
                ctx.send_viewport_cmd(egui::ViewportCommand::Close);
            }
        }
    }

    fn hide_to_tray(&mut self, ctx: &egui::Context) {
        if !self
            .desktop
            .as_ref()
            .is_some_and(|desktop| desktop.tray_ready())
        {
            return;
        }
        self.hidden_to_tray = true;
        self.was_minimized = true;
        self.save_now();
        ctx.send_viewport_cmd(egui::ViewportCommand::Visible(false));
        ctx.request_repaint();
        desktop::log_line("window hidden to tray");
    }

    fn show_main_window(&mut self, ctx: &egui::Context) {
        self.hidden_to_tray = false;
        self.was_minimized = true;
        ctx.send_viewport_cmd(egui::ViewportCommand::Visible(true));
        ctx.send_viewport_cmd(egui::ViewportCommand::Minimized(false));
        ctx.send_viewport_cmd(egui::ViewportCommand::Focus);
        ctx.request_repaint();
        desktop::log_line("window shown from tray");
    }

    fn update_theme(&mut self, ctx: &egui::Context) {
        let system_dark = ctx.theme() == egui::Theme::Dark;
        let daylight = self.is_daylight();
        let key = (
            self.state.settings.theme,
            self.state.settings.accent,
            system_dark,
            daylight,
        );
        if key != self.theme_key {
            self.palette = Palette::new(
                self.state.settings.theme,
                self.state.settings.accent,
                system_dark,
                daylight,
            );
            theme::apply_style(ctx, self.palette);
            self.theme_key = key;
        }
    }

    fn handle_shortcuts(&mut self, ctx: &egui::Context) {
        let escape = ctx.input(|i| i.key_pressed(egui::Key::Escape));
        if escape {
            if self.modal.is_some() {
                self.modal = None;
            } else if self.now_playing {
                self.now_playing = false;
            } else if self.queue_drawer {
                self.queue_drawer = false;
            } else if !self.search.is_empty() {
                self.search.clear();
            }
            return;
        }
        if ctx.wants_keyboard_input() {
            return;
        }
        if ctx.input(|i| i.key_pressed(egui::Key::Space)) {
            self.toggle_play();
        }
        if ctx.input(|i| i.modifiers.command && i.key_pressed(egui::Key::ArrowRight)) {
            self.next_track(false);
        }
        if ctx.input(|i| i.modifiers.command && i.key_pressed(egui::Key::ArrowLeft)) {
            self.prev_track();
        }
        if ctx.input(|i| i.modifiers.command && i.key_pressed(egui::Key::F)) {
            self.search_focus = true;
        }
        if ctx.input(|i| i.key_pressed(egui::Key::ArrowRight)) && self.now_playing {
            self.next_track(false);
        }
        if ctx.input(|i| i.key_pressed(egui::Key::ArrowLeft)) && self.now_playing {
            self.prev_track();
        }
    }

    fn load_current_lyrics(&mut self) {
        let Some(song) = self.current_song().cloned() else {
            self.lyrics = None;
            self.lyrics_for = None;
            return;
        };
        if self.lyrics_for.as_deref() == Some(&song.key) {
            return;
        }
        self.lyrics_for = Some(song.key.clone());
        self.lyrics_fetcher
            .send(FetchCommand::Focus(song.key.clone()));
        self.lyrics_status.remove(&song.key);
        self.lyrics = library::load_lyrics(&song).map(|text| library::parse_lrc(&text));
        if self.lyrics.is_none() {
            self.maybe_auto_fetch_lyrics(&song);
        }
    }

    fn handle_screenshot(&mut self, ctx: &egui::Context) {
        if self.screenshot.is_none() {
            return;
        }
        self.frame_count += 1;
        let scan_ready = !self.scanning && self.scan_rx.is_none();
        if self.frame_count >= 120 && scan_ready && !self.screenshot_requested {
            let wait = std::env::var("LILT_SCREENSHOT_WAIT")
                .ok()
                .and_then(|value| value.parse::<f32>().ok())
                .unwrap_or(0.0);
            if self.screenshot_started.elapsed().as_secs_f32() >= wait {
                self.screenshot_requested = true;
                ctx.send_viewport_cmd(egui::ViewportCommand::Screenshot(egui::UserData::default()));
                ctx.request_repaint();
            }
        }
        if self.screenshot_requested {
            let image = ctx.input(|input| {
                input.events.iter().find_map(|event| {
                    if let egui::Event::Screenshot { image, .. } = event {
                        Some(image.clone())
                    } else {
                        None
                    }
                })
            });
            if let Some(image) = image {
                if let Some(path) = &self.screenshot {
                    if let Some(parent) = path.parent() {
                        let _ = std::fs::create_dir_all(parent);
                    }
                    let width = image.size[0] as u32;
                    let height = image.size[1] as u32;
                    let mut bytes = Vec::with_capacity(image.pixels.len() * 4);
                    for pixel in &image.pixels {
                        bytes.extend_from_slice(&[pixel.r(), pixel.g(), pixel.b(), pixel.a()]);
                    }
                    if let Some(buffer) = image::RgbaImage::from_raw(width, height, bytes) {
                        let _ = buffer.save(path);
                        self.screenshot_saved = true;
                    }
                }
                self.close_after_screenshot = true;
                ctx.send_viewport_cmd(egui::ViewportCommand::Close);
            }
        }
        ctx.request_repaint_after(Duration::from_millis(16));
    }
}

/// Consumes the pending gapless index only when Rodio reports that the
/// queued source actually started. Without this guard the UI can advance on
/// every frame while the old track is still playing.
fn take_pending_transition(pending: &mut Option<usize>, started: bool) -> Option<usize> {
    if started {
        pending.take()
    } else {
        None
    }
}

fn is_replace(policy: ConflictPolicy) -> bool {
    matches!(policy, ConflictPolicy::Replace)
}

fn dedupe_songs(keys: Vec<String>) -> Vec<String> {
    let mut seen = HashSet::new();
    keys.into_iter()
        .filter(|key| seen.insert(key.clone()))
        .collect()
}

fn sanitize_file(name: &str) -> String {
    let cleaned: String = name
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || c == ' ' || c == '-' || c == '_' {
                c
            } else if c.is_alphanumeric() {
                c
            } else {
                '_'
            }
        })
        .collect();
    let trimmed = cleaned.trim();
    if trimmed.is_empty() {
        "playlist".to_owned()
    } else {
        trimmed.to_owned()
    }
}

pub fn filter_and_sort(
    songs: &[Song],
    query: &str,
    sort: SortMode,
    desc: bool,
    playlist: Option<&[String]>,
    folder: Option<&Path>,
) -> Vec<usize> {
    let query = query.trim().to_lowercase();
    let allowed: Option<HashSet<&str>> =
        playlist.map(|keys| keys.iter().map(|k| k.as_str()).collect());
    let mut indices: Vec<usize> = songs
        .iter()
        .enumerate()
        .filter(|(_, song)| {
            if let Some(folder) = folder {
                if !path_under(&song.path, folder) {
                    return false;
                }
            }
            if let Some(allowed) = &allowed {
                if !allowed.contains(song.key.as_str()) {
                    return false;
                }
            }
            if query.is_empty() {
                return true;
            }
            song.display_title().to_lowercase().contains(&query)
                || song.display_artist().to_lowercase().contains(&query)
                || song.display_album().to_lowercase().contains(&query)
                || song.genre.to_lowercase().contains(&query)
                || song
                    .folder()
                    .to_string_lossy()
                    .to_lowercase()
                    .contains(&query)
        })
        .map(|(index, _)| index)
        .collect();
    indices.sort_by(|a, b| {
        let sa = &songs[*a];
        let sb = &songs[*b];
        let ordering = match sort {
            SortMode::Title => sa
                .display_title()
                .to_lowercase()
                .cmp(&sb.display_title().to_lowercase()),
            SortMode::Artist => sa
                .display_artist()
                .to_lowercase()
                .cmp(&sb.display_artist().to_lowercase())
                .then(
                    sa.display_album()
                        .to_lowercase()
                        .cmp(&sb.display_album().to_lowercase()),
                )
                .then(sa.track.cmp(&sb.track)),
            SortMode::Album => sa
                .display_album()
                .to_lowercase()
                .cmp(&sb.display_album().to_lowercase())
                .then(sa.disc.cmp(&sb.disc))
                .then(sa.track.cmp(&sb.track))
                .then(
                    sa.display_title()
                        .to_lowercase()
                        .cmp(&sb.display_title().to_lowercase()),
                ),
            SortMode::Duration => sa
                .duration
                .partial_cmp(&sb.duration)
                .unwrap_or(std::cmp::Ordering::Equal),
            SortMode::DateAdded => sa.added.cmp(&sb.added),
            SortMode::LastPlayed => sa
                .last_played
                .unwrap_or(0)
                .cmp(&sb.last_played.unwrap_or(0)),
            SortMode::Plays => sa.play_count.cmp(&sb.play_count),
            SortMode::Track => sa.disc.cmp(&sb.disc).then(sa.track.cmp(&sb.track)).then(
                sa.display_title()
                    .to_lowercase()
                    .cmp(&sb.display_title().to_lowercase()),
            ),
            SortMode::Filename => sa
                .path
                .file_name()
                .map(|n| n.to_string_lossy().to_lowercase())
                .cmp(
                    &sb.path
                        .file_name()
                        .map(|n| n.to_string_lossy().to_lowercase()),
                ),
        };
        if desc {
            ordering.reverse()
        } else {
            ordering
        }
    });
    indices
}

#[derive(Clone, Debug, Default)]
pub struct SearchResults {
    pub songs: Vec<usize>,
    pub albums: Vec<usize>,
    pub artists: Vec<usize>,
    pub folders: Vec<PathBuf>,
    pub playlists: Vec<u64>,
}

impl eframe::App for LiltApp {
    fn clear_color(&self, _visuals: &egui::Visuals) -> [f32; 4] {
        // Windows desktop lyrics are layered windows: unlocked uses a constant
        // alpha and locked keys pure black out of the window. Other platforms
        // use the regular transparent-window path.
        #[cfg(target_os = "windows")]
        {
            [0.0, 0.0, 0.0, 1.0]
        }
        #[cfg(not(target_os = "windows"))]
        {
            [0.0, 0.0, 0.0, 0.0]
        }
    }

    fn update(&mut self, ctx: &egui::Context, frame: &mut eframe::Frame) {
        self.update_desktop(ctx, frame);
        self.handle_companion_actions(ctx);
        self.update_theme(ctx);
        self.poll_scan();
        self.poll_lyrics_fetch();
        self.update_sleep_timer();
        self.update_play_fade();
        self.update_playback();
        self.handle_shortcuts(ctx);
        self.load_current_lyrics();
        if let Some(toast) = &self.toast {
            if toast.born.elapsed() > Duration::from_secs(3) {
                self.toast = None;
            }
        }
        crate::ui::render(self, ctx);
        self.update_companions(ctx);
        self.maybe_save();
        self.sync_hidden_window_state();
        self.handle_screenshot(ctx);
        // Keep one delayed frame scheduled while a settings/lyric edit is waiting
        // for `maybe_save`, even when playback is paused and nothing else repaints.
        if self.dirty
            || self.playing
            || self.scanning
            || self.sleep_deadline.is_some()
            || self.scan_rx.is_some()
            || !self.lyrics_inflight.is_empty()
            || self.ai_models_loading
            || self.ai_testing
            || (self.desktop.is_none() && self.screenshot.is_none() && self.desktop_attempts < 3)
        {
            ctx.request_repaint_after(Duration::from_millis(100));
        }
        if self.close_after_screenshot {
            ctx.request_repaint();
        }
    }
}

impl Drop for LiltApp {
    fn drop(&mut self) {
        let _ = library::save_state(&self.state);
        crate::console::shutdown();
    }
}

fn missing_file_entry(state: &Persisted, key: &str) -> MissingFile {
    let extra = state.songs.get(key);
    let file_name = Path::new(key)
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or(key);
    MissingFile {
        key: key.to_owned(),
        title: extra
            .and_then(|extra| extra.title.clone())
            .unwrap_or_else(|| file_name.to_owned()),
        path: key.to_owned(),
        playlists: Vec::new(),
        queues: Vec::new(),
        favorite: extra.map(|extra| extra.favorite).unwrap_or(false),
        lyrics: extra.map(|extra| extra.lyrics.is_some()).unwrap_or(false),
        bookmarks: extra.map(|extra| extra.bookmarks.len()).unwrap_or(0),
        plays: extra.map(|extra| extra.play_count).unwrap_or(0),
    }
}

fn apply_relink(
    state: &mut Persisted,
    songs: &mut Vec<Song>,
    current_key: &mut Option<String>,
    selected: &mut HashSet<String>,
    old_key: &str,
    song: Song,
) {
    let new_key = song.key.clone();
    replace_missing_reference(state, old_key, &new_key);
    if let Some(extra) = state.songs.remove(old_key) {
        state.songs.entry(new_key.clone()).or_insert(extra);
    }
    if current_key.as_deref() == Some(old_key) {
        *current_key = Some(new_key.clone());
    }
    if selected.remove(old_key) {
        selected.insert(new_key.clone());
    }
    songs.retain(|existing| existing.key != old_key && existing.key != new_key);
    songs.push(song);
}

/// Rewrites playlist and queue references without touching the scanned songs.
pub fn replace_missing_reference(state: &mut Persisted, old_key: &str, new_key: &str) -> bool {
    let mut any = false;
    for playlist in &mut state.playlists {
        if replace_key(&mut playlist.songs, old_key, new_key) {
            playlist.updated = now_secs();
            any = true;
        }
    }
    for queue in &mut state.queues {
        let current = queue.songs.get(queue.index).cloned();
        if !replace_key(&mut queue.songs, old_key, new_key) {
            continue;
        }
        any = true;
        queue.updated = now_secs();
        let target = match current {
            Some(key) if key == old_key => new_key.to_owned(),
            Some(key) => key,
            None => continue,
        };
        if let Some(index) = queue.songs.iter().position(|key| key == &target) {
            queue.index = index;
        }
        if queue.index >= queue.songs.len() {
            queue.index = 0;
        }
    }
    any
}

fn replace_key(keys: &mut Vec<String>, old_key: &str, new_key: &str) -> bool {
    if !keys.iter().any(|key| key == old_key) {
        return false;
    }
    let mut seen = HashSet::new();
    let mut out = Vec::with_capacity(keys.len());
    for key in keys.drain(..) {
        let key = if key == old_key {
            new_key.to_owned()
        } else {
            key
        };
        if seen.insert(key.clone()) {
            out.push(key);
        }
    }
    *keys = out;
    true
}

fn ab_loop_target(ab: Option<AbLoop>, position: f64) -> Option<f64> {
    let ab = ab?;
    let end = ab.b?;
    if position >= end {
        Some(ab.a)
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn queue_dedupe_keeps_first_order() {
        let keys = vec![
            "a".to_owned(),
            "b".to_owned(),
            "a".to_owned(),
            "c".to_owned(),
            "b".to_owned(),
        ];
        assert_eq!(dedupe_songs(keys), vec!["a", "b", "c"]);
    }

    #[test]
    fn pending_transition_waits_for_the_queued_source() {
        let mut pending = Some(7);
        assert_eq!(take_pending_transition(&mut pending, false), None);
        assert_eq!(pending, Some(7));
        assert_eq!(take_pending_transition(&mut pending, true), Some(7));
        assert_eq!(pending, None);
    }

    #[test]
    fn max_queue_matches_target() {
        assert_eq!(MAX_QUEUES, 20);
    }

    #[test]
    fn ab_loop_waits_for_b_and_jumps_at_the_end() {
        assert_eq!(ab_loop_target(None, 5.0), None);
        assert_eq!(
            ab_loop_target(Some(AbLoop { a: 10.0, b: None }), 12.0),
            None
        );
        assert_eq!(
            ab_loop_target(
                Some(AbLoop {
                    a: 10.0,
                    b: Some(20.0)
                }),
                19.9
            ),
            None
        );
        assert_eq!(
            ab_loop_target(
                Some(AbLoop {
                    a: 10.0,
                    b: Some(20.0)
                }),
                20.0
            ),
            Some(10.0)
        );
    }

    #[test]
    fn relinking_rewrites_playlists_and_queues_without_duplicates() {
        let mut state = Persisted::default();
        state.playlists.push(Playlist {
            id: 1,
            name: "Moved".to_owned(),
            songs: vec![
                "old.mp3".to_owned(),
                "keep.mp3".to_owned(),
                "old.mp3".to_owned(),
            ],
            created: 1,
            updated: 2,
        });
        state.queues.push(Queue {
            id: 2,
            name: "Evening".to_owned(),
            songs: vec!["keep.mp3".to_owned(), "old.mp3".to_owned()],
            index: 1,
            position: 12.0,
            updated: 3,
        });
        assert!(replace_missing_reference(&mut state, "old.mp3", "new.mp3"));
        assert_eq!(state.playlists[0].songs, vec!["new.mp3", "keep.mp3"]);
        assert_eq!(state.queues[0].songs, vec!["keep.mp3", "new.mp3"]);
        assert_eq!(state.queues[0].index, 1);
        assert!(!replace_missing_reference(&mut state, "old.mp3", "new.mp3"));
    }

    #[test]
    fn relinking_a_real_file_moves_references_and_song_state() {
        let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("Starter Music")
            .join("Aurora Drift")
            .join("01 - Glass Horizon.wav");
        let Some(song) = library::read_song(&path, &HashMap::new()) else {
            return;
        };
        let new_key = song.key.clone();
        let old_key = "z:\\gone\\missing.mp3".to_owned();
        let mut state = Persisted::default();
        state.playlists.push(Playlist {
            id: 1,
            name: "Moved".to_owned(),
            songs: vec![old_key.clone(), "keep.mp3".to_owned()],
            created: 1,
            updated: 1,
        });
        state.queues.push(Queue {
            id: 2,
            name: "Evening".to_owned(),
            songs: vec![old_key.clone(), "keep.mp3".to_owned()],
            index: 1,
            position: 4.0,
            updated: 1,
        });
        state.songs.insert(
            old_key.clone(),
            SongExtra {
                favorite: true,
                play_count: 5,
                ..SongExtra::default()
            },
        );
        let mut songs = Vec::new();
        let mut current = Some(old_key.clone());
        let mut selected = HashSet::from([old_key.clone()]);
        apply_relink(
            &mut state,
            &mut songs,
            &mut current,
            &mut selected,
            &old_key,
            song,
        );
        assert_eq!(songs.len(), 1);
        assert_eq!(songs[0].key, new_key);
        assert_eq!(
            state.playlists[0].songs,
            vec![new_key.clone(), "keep.mp3".to_owned()]
        );
        assert_eq!(
            state.queues[0].songs,
            vec![new_key.clone(), "keep.mp3".to_owned()]
        );
        assert_eq!(state.queues[0].index, 1);
        assert!(state
            .songs
            .get(&new_key)
            .map(|extra| extra.favorite)
            .unwrap_or(false));
        assert!(!state.songs.contains_key(&old_key));
        assert_eq!(current.as_deref(), Some(new_key.as_str()));
        assert!(selected.contains(&new_key));
    }
}
