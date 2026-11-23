use crate::model::*;
use lofty::file::{AudioFile, TaggedFileExt};
use lofty::probe::Probe;
use lofty::tag::{Accessor, ItemKey};
use std::collections::HashMap;
use std::fs::{self, File, OpenOptions};
use std::io::{BufWriter, Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::time::UNIX_EPOCH;
use walkdir::WalkDir;

pub const AUDIO_EXTENSIONS: &[&str] = &[
    "mp3", "flac", "ogg", "opus", "wav", "m4a", "aac", "wma", "aiff", "aif", "alac", "mka",
];

pub fn data_dir() -> PathBuf {
    if let Ok(value) = std::env::var("LILT_DATA_DIR") {
        if !value.trim().is_empty() {
            return PathBuf::from(value);
        }
    }
    if cfg!(windows) {
        if let Some(dir) = std::env::var_os("APPDATA") {
            return PathBuf::from(dir).join("Lilt");
        }
    }
    if let Some(dir) = std::env::var_os("XDG_DATA_HOME") {
        return PathBuf::from(dir).join("lilt");
    }
    if let Some(dir) = std::env::var_os("HOME") {
        return PathBuf::from(dir).join(".local/share/lilt");
    }
    std::env::current_dir()
        .unwrap_or_else(|_| PathBuf::from("."))
        .join("lilt-data")
}

pub fn state_path() -> PathBuf {
    data_dir().join("state.json")
}

pub fn load_state() -> Persisted {
    let path = state_path();
    if let Ok(text) = fs::read_to_string(&path) {
        if let Ok(state) = serde_json::from_str::<Persisted>(&text) {
            return state;
        }
        let backup = path.with_extension("json.bak");
        let _ = fs::copy(&path, backup);
    }
    Persisted::default()
}

pub fn save_state(state: &Persisted) -> std::io::Result<()> {
    let dir = data_dir();
    fs::create_dir_all(&dir)?;
    let path = state_path();
    let tmp = path.with_extension("json.tmp");
    let text = serde_json::to_string_pretty(state).unwrap_or_else(|_| "{}".to_owned());
    {
        let mut file = File::create(&tmp)?;
        file.write_all(text.as_bytes())?;
        file.sync_all()?;
    }
    if path.exists() {
        let _ = fs::remove_file(&path);
    }
    fs::rename(&tmp, &path)?;
    Ok(())
}

pub fn is_audio(path: &Path) -> bool {
    path.extension()
        .and_then(|e| e.to_str())
        .map(|e| {
            let lower = e.to_lowercase();
            AUDIO_EXTENSIONS.iter().any(|known| *known == lower)
        })
        .unwrap_or(false)
}

fn is_hidden(path: &Path) -> bool {
    path.file_name()
        .and_then(|name| name.to_str())
        .map(|name| name.starts_with('.') || name == "@eaDir")
        .unwrap_or(false)
}

#[derive(Clone, Debug, Default)]
pub struct ScanStats {
    pub files_seen: usize,
    pub songs_found: usize,
    pub skipped: usize,
}

pub fn scan_library<F>(
    roots: &[PathBuf],
    excluded: &[PathBuf],
    included: &[PathBuf],
    only_included: bool,
    settings: &Settings,
    extras: &HashMap<String, SongExtra>,
    mut progress: F,
) -> (Vec<Song>, ScanStats)
where
    F: FnMut(usize, &Path),
{
    let mut songs = Vec::new();
    let mut stats = ScanStats::default();
    let mut seen_keys = std::collections::HashSet::new();

    for root in roots {
        if !root.is_dir() {
            continue;
        }
        let walker = WalkDir::new(root)
            .follow_links(false)
            .into_iter()
            .filter_entry(|entry| {
                if entry.depth() == 0 {
                    return true;
                }
                if entry.file_type().is_dir() {
                    if is_hidden(entry.path()) && !settings.scan_hidden {
                        return false;
                    }
                    if excluded.iter().any(|ex| path_under(entry.path(), ex)) {
                        return false;
                    }
                }
                true
            });

        for entry in walker.flatten() {
            if !entry.file_type().is_file() {
                continue;
            }
            let path = entry.path();
            if !is_audio(path) {
                continue;
            }
            if excluded.iter().any(|ex| path_under(path, ex)) {
                stats.skipped += 1;
                continue;
            }
            if only_included {
                if included.is_empty() || !included.iter().any(|inc| path_under(path, inc)) {
                    stats.skipped += 1;
                    continue;
                }
            }
            stats.files_seen += 1;
            progress(stats.files_seen, path);

            let Some(song) = read_song(path, extras) else {
                stats.skipped += 1;
                continue;
            };
            if !settings.scan_small_files
                && settings.min_duration_seconds > 0
                && song.duration < settings.min_duration_seconds as f64
            {
                stats.skipped += 1;
                continue;
            }
            if seen_keys.insert(song.key.clone()) {
                stats.songs_found += 1;
                songs.push(song);
            }
        }
    }

    songs.sort_by(|a, b| {
        a.artist
            .to_lowercase()
            .cmp(&b.artist.to_lowercase())
            .then(a.album.to_lowercase().cmp(&b.album.to_lowercase()))
            .then(a.track.cmp(&b.track))
            .then(a.title.to_lowercase().cmp(&b.title.to_lowercase()))
    });
    (songs, stats)
}

fn read_song(path: &Path, extras: &HashMap<String, SongExtra>) -> Option<Song> {
    let canonical = canonical_clean(path);
    let key = normalize_key(&canonical);
    let meta = fs::metadata(&canonical).ok()?;
    let modified = meta
        .modified()
        .ok()
        .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let added = meta
        .created()
        .ok()
        .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
        .map(|d| d.as_secs())
        .unwrap_or(modified);

    let ext = canonical
        .extension()
        .and_then(|e| e.to_str())
        .map(|e| e.to_uppercase())
        .unwrap_or_else(|| "AUDIO".to_owned());

    let mut title = String::new();
    let mut artist = String::new();
    let mut album = String::new();
    let mut album_artist = String::new();
    let mut genre = String::new();
    let mut year = String::new();
    let mut track = 0u32;
    let mut disc = 0u32;
    let mut duration = 0.0f64;
    let mut sample_rate = 0u32;
    let mut channels = 0u8;
    let mut bitrate = 0u32;
    let mut replay_gain: Option<f32> = None;

    let wav_info = if ext == "WAV" {
        read_wav_info(&canonical).unwrap_or_default()
    } else {
        WavInfo::default()
    };

    if let Ok(tagged) = Probe::open(&canonical).and_then(|probe| probe.read()) {
        let props = tagged.properties();
        duration = props.duration().as_secs_f64();
        sample_rate = props.sample_rate().unwrap_or(0);
        channels = props.channels().unwrap_or(0);
        bitrate = props
            .audio_bitrate()
            .or_else(|| props.overall_bitrate())
            .unwrap_or(0);
        for tag in tagged.tags() {
            if title.is_empty() {
                if let Some(value) = tag.title() {
                    title = value.trim().to_owned();
                }
            }
            if artist.is_empty() {
                if let Some(value) = tag.artist() {
                    artist = value.trim().to_owned();
                }
            }
            if album.is_empty() {
                if let Some(value) = tag.album() {
                    album = value.trim().to_owned();
                }
            }
            if genre.is_empty() {
                if let Some(value) = tag.genre() {
                    genre = value.trim().to_owned();
                }
            }
            if year.is_empty() {
                if let Some(value) = tag.year() {
                    year = value.to_string();
                }
            }
            if track == 0 {
                track = tag.track().unwrap_or(0);
            }
            if disc == 0 {
                disc = tag.disk().unwrap_or(0);
            }
            if album_artist.is_empty() {
                if let Some(value) = tag.get_string(&ItemKey::AlbumArtist) {
                    album_artist = value.trim().to_owned();
                }
            }
            if replay_gain.is_none() {
                if let Some(value) = tag.get_string(&ItemKey::ReplayGainTrackGain) {
                    replay_gain = parse_gain(value);
                }
            }
        }
    }

    if wav_info.title.is_some() && title.is_empty() {
        title = wav_info.title.clone().unwrap_or_default();
    }
    if wav_info.artist.is_some() && artist.is_empty() {
        artist = wav_info.artist.clone().unwrap_or_default();
    }
    if wav_info.album.is_some() && album.is_empty() {
        album = wav_info.album.clone().unwrap_or_default();
    }
    if wav_info.genre.is_some() && genre.is_empty() {
        genre = wav_info.genre.clone().unwrap_or_default();
    }
    if wav_info.year.is_some() && year.is_empty() {
        year = wav_info.year.clone().unwrap_or_default();
    }
    if wav_info.track > 0 && track == 0 {
        track = wav_info.track;
    }
    if duration <= 0.0 {
        duration = wav_info.duration;
    }
    if sample_rate == 0 {
        sample_rate = wav_info.sample_rate;
    }
    if channels == 0 {
        channels = wav_info.channels;
    }
    if bitrate == 0 && duration > 0.0 {
        bitrate = ((meta.len() as f64 * 8.0 / duration / 1000.0) as u32).max(1);
    }

    let stem = canonical
        .file_stem()
        .and_then(|s| s.to_str())
        .unwrap_or("Untitled");
    let (fallback_track, fallback_title) = parse_track_prefix(stem);
    if track == 0 {
        track = fallback_track;
    }
    if title.is_empty() {
        title = fallback_title;
    }
    if artist.is_empty() {
        artist = canonical
            .parent()
            .and_then(|p| p.parent())
            .and_then(|p| p.file_name())
            .and_then(|n| n.to_str())
            .map(|n| {
                let lower = n.to_lowercase();
                if lower == "music" || lower == "starter music" || lower == "singles" {
                    "Unknown artist".to_owned()
                } else {
                    n.to_owned()
                }
            })
            .unwrap_or_else(|| "Unknown artist".to_owned());
    }
    if album.is_empty() {
        album = canonical
            .parent()
            .and_then(|p| p.file_name())
            .and_then(|n| n.to_str())
            .unwrap_or("Unknown album")
            .to_owned();
    }

    let extra = extras.get(&key).cloned().unwrap_or_default();
    if let Some(value) = extra.title.as_deref() {
        if !value.trim().is_empty() {
            title = value.to_owned();
        }
    }
    if let Some(value) = extra.artist.as_deref() {
        if !value.trim().is_empty() {
            artist = value.to_owned();
        }
    }
    if let Some(value) = extra.album.as_deref() {
        if !value.trim().is_empty() {
            album = value.to_owned();
        }
    }
    if let Some(value) = extra.genre.as_deref() {
        genre = value.to_owned();
    }
    if let Some(value) = extra.year.as_deref() {
        year = value.to_owned();
    }
    if let Some(value) = extra.track {
        track = value;
    }
    if extra.replay_gain.is_some() {
        replay_gain = extra.replay_gain;
    }

    Some(Song {
        key,
        path: canonical,
        title,
        artist,
        album,
        album_artist,
        genre,
        year,
        track,
        disc,
        duration,
        sample_rate,
        channels,
        bitrate,
        format: ext,
        size: meta.len(),
        modified,
        added,
        favorite: extra.favorite,
        play_count: extra.play_count,
        last_played: extra.last_played,
        last_position: extra.last_position,
        lyrics_override: extra.lyrics,
        cover_path: extra.cover.map(PathBuf::from),
        replay_gain,
        bookmarks: extra.bookmarks,
    })
}

fn parse_gain(text: &str) -> Option<f32> {
    let cleaned = text
        .trim()
        .trim_end_matches("dB")
        .trim_end_matches("db")
        .trim();
    cleaned.parse::<f32>().ok()
}

fn parse_track_prefix(stem: &str) -> (u32, String) {
    let mut chars = stem.char_indices().peekable();
    let mut digits = String::new();
    while let Some((_, ch)) = chars.peek().copied() {
        if ch.is_ascii_digit() {
            digits.push(ch);
            chars.next();
        } else {
            break;
        }
    }
    if digits.is_empty() {
        return (0, clean_file_title(stem));
    }
    let mut rest = &stem[digits.len()..];
    rest = rest.trim_start_matches([' ', '-', '_', '.', ')']);
    let title = clean_file_title(rest);
    if title.is_empty() {
        (digits.parse().unwrap_or(0), clean_file_title(stem))
    } else {
        (digits.parse().unwrap_or(0), title)
    }
}

fn clean_file_title(text: &str) -> String {
    let mut out = text.replace('_', " ").trim().to_owned();
    while out.contains("  ") {
        out = out.replace("  ", " ");
    }
    out
}

#[derive(Clone, Debug, Default)]
struct WavInfo {
    title: Option<String>,
    artist: Option<String>,
    album: Option<String>,
    genre: Option<String>,
    year: Option<String>,
    track: u32,
    duration: f64,
    sample_rate: u32,
    channels: u8,
    data_len: u32,
    byte_rate: u32,
    block_align: u16,
}

fn read_wav_info(path: &Path) -> Option<WavInfo> {
    let mut file = File::open(path).ok()?;
    let mut header = [0u8; 12];
    file.read_exact(&mut header).ok()?;
    if &header[0..4] != b"RIFF" || &header[8..12] != b"WAVE" {
        return None;
    }
    let mut info = WavInfo::default();
    let mut fmt_set = false;
    loop {
        let mut chunk_header = [0u8; 8];
        if file.read_exact(&mut chunk_header).is_err() {
            break;
        }
        let id = &chunk_header[0..4];
        let size = u32::from_le_bytes([
            chunk_header[4],
            chunk_header[5],
            chunk_header[6],
            chunk_header[7],
        ]);
        let start = file.stream_position().ok()?;
        if id == b"fmt " {
            let mut fmt = vec![0u8; size.min(64) as usize];
            file.read_exact(&mut fmt).ok()?;
            if fmt.len() >= 16 {
                info.channels = u16::from_le_bytes([fmt[2], fmt[3]]) as u8;
                info.sample_rate = u32::from_le_bytes([fmt[4], fmt[5], fmt[6], fmt[7]]);
                info.byte_rate = u32::from_le_bytes([fmt[8], fmt[9], fmt[10], fmt[11]]);
                info.block_align = u16::from_le_bytes([fmt[12], fmt[13]]);
                fmt_set = true;
            }
            file.seek(SeekFrom::Start(start + size as u64 + (size % 2) as u64))
                .ok()?;
        } else if id == b"LIST" {
            let mut list_type = [0u8; 4];
            if size >= 4 {
                file.read_exact(&mut list_type).ok()?;
                if &list_type == b"INFO" {
                    let mut remaining = size - 4;
                    while remaining >= 8 {
                        let mut sub_header = [0u8; 8];
                        file.read_exact(&mut sub_header).ok()?;
                        let sub_id = &sub_header[0..4];
                        let sub_size = u32::from_le_bytes([
                            sub_header[4],
                            sub_header[5],
                            sub_header[6],
                            sub_header[7],
                        ]);
                        let mut data = vec![0u8; sub_size.min(remaining) as usize];
                        file.read_exact(&mut data).ok()?;
                        let text = String::from_utf8_lossy(&data)
                            .trim_end_matches('\0')
                            .trim()
                            .to_owned();
                        if !text.is_empty() {
                            match sub_id {
                                b"INAM" => info.title = Some(text),
                                b"IART" => info.artist = Some(text),
                                b"IPRD" => info.album = Some(text),
                                b"IGNR" => info.genre = Some(text),
                                b"ICRD" => info.year = Some(text),
                                b"ITRK" => info.track = text.parse().unwrap_or(0),
                                _ => {}
                            }
                        }
                        let consumed = 8 + sub_size + (sub_size % 2);
                        remaining = remaining.saturating_sub(consumed);
                        let _ =
                            file.seek(SeekFrom::Start(start + 4 + (size - 4 - remaining) as u64));
                    }
                } else {
                    file.seek(SeekFrom::Start(start + size as u64 + (size % 2) as u64))
                        .ok()?;
                }
            }
        } else if id == b"data" {
            info.data_len = size;
            if fmt_set && info.byte_rate > 0 {
                info.duration = size as f64 / info.byte_rate as f64;
            } else if fmt_set && info.sample_rate > 0 && info.block_align > 0 {
                info.duration = size as f64 / (info.sample_rate as f64 * info.block_align as f64);
            }
            file.seek(SeekFrom::Start(start + size as u64 + (size % 2) as u64))
                .ok()?;
        } else {
            if size == 0 {
                break;
            }
            file.seek(SeekFrom::Start(start + size as u64 + (size % 2) as u64))
                .ok()?;
        }
    }
    Some(info)
}

pub fn read_cover(path: &Path) -> Option<Vec<u8>> {
    let tagged = Probe::open(path).ok()?.read().ok()?;
    for tag in tagged.tags() {
        if let Some(picture) = tag.pictures().first() {
            return Some(picture.data().to_vec());
        }
    }
    None
}

pub fn read_embedded_lyrics(path: &Path) -> Option<String> {
    let tagged = Probe::open(path).ok()?.read().ok()?;
    for tag in tagged.tags() {
        if let Some(text) = tag.get_string(&ItemKey::Lyrics) {
            if !text.trim().is_empty() {
                return Some(text.to_owned());
            }
        }
    }
    None
}

pub fn sidecar_lyrics_path(song_path: &Path) -> PathBuf {
    song_path.with_extension("lrc")
}

pub fn load_lyrics(song: &Song) -> Option<String> {
    if let Some(text) = &song.lyrics_override {
        if !text.trim().is_empty() {
            return Some(text.clone());
        }
    }
    if let Some(text) = read_embedded_lyrics(&song.path) {
        return Some(text);
    }
    let sidecar = sidecar_lyrics_path(&song.path);
    if let Ok(text) = fs::read_to_string(sidecar) {
        if !text.trim().is_empty() {
            return Some(text);
        }
    }
    None
}

pub fn save_lyrics(song: &Song, text: &str) -> std::io::Result<()> {
    let path = sidecar_lyrics_path(&song.path);
    let mut file = File::create(path)?;
    file.write_all(text.as_bytes())?;
    Ok(())
}

pub fn parse_lrc(text: &str) -> Vec<(f64, String)> {
    let mut lines = Vec::new();
    for raw in text.lines() {
        let mut rest = raw.trim();
        if rest.is_empty() {
            continue;
        }
        let mut times = Vec::new();
        while rest.starts_with('[') {
            let Some(end) = rest.find(']') else { break };
            let tag = &rest[1..end];
            rest = rest[end + 1..].trim_start();
            if let Some((min, sec)) = tag.split_once(':') {
                let min = min.trim().parse::<f64>().unwrap_or(-1.0);
                let sec = sec.trim().replace(',', ".");
                let sec = sec.parse::<f64>().unwrap_or(-1.0);
                if min >= 0.0 && sec >= 0.0 {
                    times.push(min * 60.0 + sec);
                }
            }
        }
        let line = rest.trim();
        if !line.is_empty() {
            if times.is_empty() {
                times.push(0.0);
            }
            for time in times {
                lines.push((time, line.to_owned()));
            }
        }
    }
    lines.sort_by(|a, b| a.0.partial_cmp(&b.0).unwrap_or(std::cmp::Ordering::Equal));
    lines
}

/// Largest per-song lyric shift accepted from the UI, in milliseconds.
pub const LYRICS_OFFSET_LIMIT_MS: i64 = 60_000;

pub fn clamp_lyrics_offset(offset_ms: i64) -> i64 {
    offset_ms.clamp(-LYRICS_OFFSET_LIMIT_MS, LYRICS_OFFSET_LIMIT_MS)
}

/// Position used to pick the highlighted line. A positive offset delays the
/// lyrics, so line `t` lights up at `t + offset`.
pub fn lyrics_position(position: f64, offset_ms: i64) -> f64 {
    (position - offset_ms as f64 / 1000.0).max(0.0)
}

/// Song position to seek to when the user clicks a lyric line.
pub fn lyrics_seek_time(line_time: f64, offset_ms: i64) -> f64 {
    (line_time + offset_ms as f64 / 1000.0).max(0.0)
}

pub fn export_m3u(path: &Path, songs: &[Song]) -> std::io::Result<()> {
    let file = File::create(path)?;
    let mut out = BufWriter::new(file);
    writeln!(out, "#EXTM3U")?;
    for song in songs {
        writeln!(
            out,
            "#EXTINF:{},{} - {}",
            song.duration.round() as i64,
            song.display_artist(),
            song.display_title()
        )?;
        writeln!(out, "{}", song.path.to_string_lossy())?;
    }
    out.flush()
}

pub fn parse_m3u(path: &Path) -> Vec<PathBuf> {
    let Ok(text) = fs::read_to_string(path) else {
        return Vec::new();
    };
    let base = path.parent().unwrap_or(Path::new(""));
    let mut paths = Vec::new();
    for raw in text.lines() {
        let mut line = raw.trim().trim_start_matches('\u{feff}').trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        if let Some(rest) = line.strip_prefix("file://") {
            line = rest;
            #[cfg(windows)]
            {
                line = line.trim_start_matches('/');
            }
        }
        let candidate = PathBuf::from(line);
        let resolved = if candidate.is_absolute() {
            candidate
        } else {
            base.join(candidate)
        };
        let canonical = resolved.canonicalize().unwrap_or(resolved);
        paths.push(canonical);
    }
    paths
}

pub fn export_csv(path: &Path, songs: &[Song]) -> std::io::Result<()> {
    let file = File::create(path)?;
    let mut out = BufWriter::new(file);
    writeln!(
        out,
        "title,artist,album,genre,year,plays,last_played,duration_seconds,format,path"
    )?;
    for song in songs {
        writeln!(
            out,
            "{},{},{},{},{},{},{},{:.1},{},{}",
            csv_escape(song.display_title()),
            csv_escape(song.display_artist()),
            csv_escape(song.display_album()),
            csv_escape(&song.genre),
            csv_escape(&song.year),
            song.play_count,
            song.last_played.unwrap_or(0),
            song.duration,
            song.format,
            csv_escape(&song.path.to_string_lossy())
        )?;
    }
    out.flush()
}

fn csv_escape(text: &str) -> String {
    if text.contains([',', '"', '\n']) {
        format!("\"{}\"", text.replace('"', "\"\""))
    } else {
        text.to_owned()
    }
}

pub fn bundled_demo_dir() -> Option<PathBuf> {
    let mut candidates: Vec<PathBuf> = Vec::new();
    candidates.push(PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("Starter Music"));
    candidates.push(PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("demo_library"));
    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            candidates.push(dir.join("Starter Music"));
            candidates.push(dir.join("../Starter Music"));
            candidates.push(dir.join("../../Starter Music"));
            candidates.push(dir.join("demo_library"));
            candidates.push(dir.join("../demo_library"));
            candidates.push(dir.join("../../demo_library"));
        }
    }
    if let Ok(cwd) = std::env::current_dir() {
        candidates.push(cwd.join("Starter Music"));
        candidates.push(cwd.join("demo_library"));
    }
    for candidate in candidates {
        if candidate.is_dir() && candidate.join("Aurora Drift").is_dir() {
            return Some(canonical_clean(&candidate));
        }
    }
    None
}

pub fn ensure_starter_library(state: &mut Persisted) -> PathBuf {
    let bundled = bundled_demo_dir();
    let dir = if let Some(bundled) = &bundled {
        bundled.clone()
    } else {
        let dir = data_dir().join("Starter Music");
        let _ = fs::create_dir_all(&dir);
        generate_starter_library(&dir);
        let _ = fs::write(dir.join(".lilt_starter_v1"), b"Lilt starter library\n");
        dir
    };
    if state.library_roots.is_empty() {
        state
            .library_roots
            .push(canonical_clean(&dir).to_string_lossy().to_string());
    }
    state.starter_library = true;
    state.first_run = false;
    canonical_clean(&dir)
}

struct TrackSpec {
    album: &'static str,
    artist: &'static str,
    title: &'static str,
    genre: &'static str,
    year: &'static str,
    track: u32,
    seconds: f32,
    root: f32,
}

fn starter_tracks() -> Vec<TrackSpec> {
    vec![
        TrackSpec {
            album: "Aurora Drift",
            artist: "Lumen Field",
            title: "Glass Horizon",
            genre: "Ambient",
            year: "2021",
            track: 1,
            seconds: 48.0,
            root: 174.61,
        },
        TrackSpec {
            album: "Aurora Drift",
            artist: "Lumen Field",
            title: "Soft Static",
            genre: "Ambient",
            year: "2021",
            track: 2,
            seconds: 52.0,
            root: 196.0,
        },
        TrackSpec {
            album: "Aurora Drift",
            artist: "Lumen Field",
            title: "Northlight",
            genre: "Ambient",
            year: "2021",
            track: 3,
            seconds: 44.0,
            root: 220.0,
        },
        TrackSpec {
            album: "Paper Satellites",
            artist: "Mira Vale",
            title: "Slow Orbit",
            genre: "Dream pop",
            year: "2023",
            track: 1,
            seconds: 51.0,
            root: 146.83,
        },
        TrackSpec {
            album: "Paper Satellites",
            artist: "Mira Vale",
            title: "Paper Sky",
            genre: "Dream pop",
            year: "2023",
            track: 2,
            seconds: 46.0,
            root: 164.81,
        },
        TrackSpec {
            album: "Paper Satellites",
            artist: "Mira Vale",
            title: "Cloudline",
            genre: "Dream pop",
            year: "2023",
            track: 3,
            seconds: 49.0,
            root: 174.61,
        },
        TrackSpec {
            album: "Night Transit",
            artist: "Low Meridian",
            title: "Last Train",
            genre: "Electronic",
            year: "2022",
            track: 1,
            seconds: 55.0,
            root: 110.0,
        },
        TrackSpec {
            album: "Night Transit",
            artist: "Low Meridian",
            title: "Platform Four",
            genre: "Electronic",
            year: "2022",
            track: 2,
            seconds: 47.0,
            root: 130.81,
        },
        TrackSpec {
            album: "Field Notes",
            artist: "Sable Coast",
            title: "Moss & Morning",
            genre: "Folk",
            year: "2024",
            track: 1,
            seconds: 42.0,
            root: 196.0,
        },
        TrackSpec {
            album: "Field Notes",
            artist: "Sable Coast",
            title: "Quiet Engine",
            genre: "Folk",
            year: "2024",
            track: 2,
            seconds: 45.0,
            root: 220.0,
        },
    ]
}

pub fn generate_starter_library(dir: &Path) {
    let tracks = starter_tracks();
    for spec in &tracks {
        let album_dir = dir.join(spec.album);
        let _ = fs::create_dir_all(&album_dir);
        let filename = format!("{:02} - {}.wav", spec.track, spec.title);
        let path = album_dir.join(filename);
        if path.exists() {
            continue;
        }
        let samples = synthesize(spec);
        let _ = write_wav(&path, &samples, 16000, &spec);
    }
    let lrc = "The window hums a quiet tone\nA room of light, a world our own\n\
               The city softens into blue\nAnd every hour leads to you\n\n\
               Glass horizon, open wide\nNothing here needs to hide\n\
               A steady pulse beneath the floor\nA little less, a little more\n\n\
               Morning gathers at the edge\nA promise kept, a quiet pledge\n";
    let _ = fs::write(dir.join("Aurora Drift").join("01 - Glass Horizon.lrc"), lrc);
    let readme = "Lilt starter library\n\nThese ten short instrumental pieces are generated locally so the player has something to scan and play on first launch. Replace this folder with your own music in Settings.\n";
    let _ = fs::write(dir.join("ABOUT.txt"), readme);
}

fn synthesize(spec: &TrackSpec) -> Vec<i16> {
    let rate = 16000u32;
    let total = (spec.seconds * rate as f32) as usize;
    let mut samples = Vec::with_capacity(total);
    let root = spec.root as f32;
    let chord = [1.0f32, 1.25, 1.5, 2.0];
    let beat = 0.62f32;
    let arp = [0usize, 1, 2, 3, 2, 1, 2, 3];
    let mut noise_state: u32 = 0x9e37_79b9 ^ (spec.track * 7919);
    for i in 0..total {
        let t = i as f32 / rate as f32;
        let beat_index = (t / beat) as usize;
        let beat_t = t - beat_index as f32 * beat;
        let note = arp[beat_index % arp.len()];
        let freq = root * chord[note] * 2.0;
        let env = (beat_t / 0.06).min(1.0) * (-3.2 * beat_t).exp();
        let pad = (std::f32::consts::TAU * root * 0.5 * t).sin() * 0.10
            + (std::f32::consts::TAU * root * 0.75 * t + 0.4).sin() * 0.05;
        let lead = (std::f32::consts::TAU * freq * t).sin() * 0.17
            + (std::f32::consts::TAU * freq * 2.003 * t).sin() * 0.045;
        noise_state = noise_state
            .wrapping_mul(1_664_525)
            .wrapping_add(1_013_904_223);
        let noise = ((noise_state >> 8) as f32 / 16_777_216.0 - 0.5) * 0.006;
        let fade_in = (t / 1.2).min(1.0);
        let fade_out = ((spec.seconds - t) / 2.0).clamp(0.0, 1.0);
        let value = (pad + lead * env + noise) * fade_in * fade_out * 0.9;
        let sample = (value.clamp(-1.0, 1.0) * 26_000.0) as i16;
        samples.push(sample);
    }
    samples
}

fn write_wav(
    path: &Path,
    samples: &[i16],
    sample_rate: u32,
    spec: &TrackSpec,
) -> std::io::Result<()> {
    let data_len = (samples.len() * 2) as u32;
    let mut info = Vec::new();
    push_info(&mut info, b"INAM", spec.title);
    push_info(&mut info, b"IART", spec.artist);
    push_info(&mut info, b"IPRD", spec.album);
    push_info(&mut info, b"IGNR", spec.genre);
    push_info(&mut info, b"ICRD", spec.year);
    push_info(&mut info, b"ITRK", &spec.track.to_string());
    let mut list = Vec::new();
    list.extend_from_slice(b"INFO");
    list.extend_from_slice(&info);
    let list_len = list.len() as u32;

    let riff_len = 4 + (8 + 16) + (8 + list_len) + (8 + data_len);
    let mut file = File::create(path)?;
    file.write_all(b"RIFF")?;
    file.write_all(&riff_len.to_le_bytes())?;
    file.write_all(b"WAVE")?;
    file.write_all(b"fmt ")?;
    file.write_all(&16u32.to_le_bytes())?;
    file.write_all(&1u16.to_le_bytes())?;
    file.write_all(&1u16.to_le_bytes())?;
    file.write_all(&sample_rate.to_le_bytes())?;
    file.write_all(&(sample_rate * 2).to_le_bytes())?;
    file.write_all(&2u16.to_le_bytes())?;
    file.write_all(&16u16.to_le_bytes())?;
    file.write_all(b"LIST")?;
    file.write_all(&list_len.to_le_bytes())?;
    file.write_all(&list)?;
    file.write_all(b"data")?;
    file.write_all(&data_len.to_le_bytes())?;
    let mut buf = Vec::with_capacity(samples.len() * 2);
    for sample in samples {
        buf.extend_from_slice(&sample.to_le_bytes());
    }
    file.write_all(&buf)?;
    Ok(())
}

fn push_info(out: &mut Vec<u8>, id: &[u8; 4], value: &str) {
    let data = value.as_bytes();
    out.extend_from_slice(id);
    out.extend_from_slice(&(data.len() as u32).to_le_bytes());
    out.extend_from_slice(data);
    if data.len() % 2 == 1 {
        out.push(0);
    }
}

pub fn unique_path_name(dir: &Path, stem: &str, ext: &str) -> PathBuf {
    let mut candidate = dir.join(format!("{stem}.{ext}"));
    let mut index = 2;
    while candidate.exists() {
        candidate = dir.join(format!("{stem} {index}.{ext}"));
        index += 1;
    }
    candidate
}

pub fn open_writer(path: &Path) -> std::io::Result<File> {
    OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(path)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lrc_timestamps_are_parsed() {
        let lines = parse_lrc("[00:12.50]Hello\n[00:20.00]World\n[ar:Someone]\n");
        assert_eq!(lines.len(), 2);
        assert!((lines[0].0 - 12.5).abs() < 0.01);
        assert_eq!(lines[1].1, "World");
    }

    #[test]
    fn lyrics_offsets_shift_highlight_and_seek() {
        // A line tagged at 10 s lights up at 10 s with no offset.
        assert!((lyrics_position(10.0, 0) - 10.0).abs() < 0.001);
        assert!((lyrics_seek_time(10.0, 0) - 10.0).abs() < 0.001);
        // Positive offsets delay the lyrics by that much.
        assert!((lyrics_position(10.0, 500) - 9.5).abs() < 0.001);
        assert!((lyrics_seek_time(10.0, 500) - 10.5).abs() < 0.001);
        // Negative offsets advance them.
        assert!((lyrics_position(10.0, -500) - 10.5).abs() < 0.001);
        assert!((lyrics_seek_time(10.0, -500) - 9.5).abs() < 0.001);
    }

    #[test]
    fn lyrics_offsets_clamp_and_never_go_negative() {
        assert_eq!(clamp_lyrics_offset(90_000), 60_000);
        assert_eq!(clamp_lyrics_offset(-90_000), -60_000);
        assert_eq!(clamp_lyrics_offset(250), 250);
        assert_eq!(lyrics_position(1.0, 5_000), 0.0);
        assert_eq!(lyrics_seek_time(1.0, -5_000), 0.0);
    }

    #[test]
    fn m3u_round_trip_keeps_order() {
        let stamp = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_nanos())
            .unwrap_or(0);
        let dir = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("target")
            .join(format!("lilt-test-{}-{stamp}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let files = ["a.mp3", "b.flac", "c.ogg"];
        for name in files {
            std::fs::write(dir.join(name), b"x").unwrap();
        }
        let m3u = dir.join("list.m3u");
        let text = "#EXTM3U\n#EXTINF:12,Artist - A\n a.mp3\n#EXTINF:13,Artist - B\nb.flac\n#EXTINF:14,Artist - C\nc.ogg\n"
            .replace("\n a", "\na");
        std::fs::write(&m3u, text).unwrap();
        let paths = parse_m3u(&m3u);
        let names: Vec<String> = paths
            .iter()
            .map(|p| p.file_name().unwrap().to_string_lossy().to_string())
            .collect();
        assert_eq!(names, vec!["a.mp3", "b.flac", "c.ogg"]);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn track_prefix_is_stripped() {
        assert_eq!(
            parse_track_prefix("03 - Glass Horizon"),
            (3, "Glass Horizon".to_owned())
        );
        assert_eq!(parse_track_prefix("Intro"), (0, "Intro".to_owned()));
    }
}
