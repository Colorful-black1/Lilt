use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

pub const EQ_BANDS: usize = 10;
pub const MAX_QUEUES: usize = 20;
/// `MusicService.X()` compares the current position with `0x2710` ms.
pub const PREVIOUS_REPLAY_SECONDS: f64 = 10.0;
/// `MusicService.p()` fallback is the string `"30"` (10/30/50/90 array).
pub const DEFAULT_PLAY_COUNT_PERCENT: u32 = 30;
pub const APP_NAME: &str = "Lilt";
pub const APP_VERSION: &str = env!("CARGO_PKG_VERSION");

/// `k_b_fdid` / `k_b_fdod` in `res/xml/default_preference.xml`:
/// `msbp_minValue=0`, `msbp_maxValue=4000`, `msbp_interval=250`, unit `ms`,
/// default `0`.
pub const FADE_MAX_MS: u32 = 4000;
pub const FADE_STEP_MS: u32 = 250;

/// Fade-in curve of `ub.run` (started by `ac.e0`), as a fraction of the
/// player's full volume. The target works in integer volume units 0…100 and
/// sleeps 100 ms per step; `vb`/`ub` measure volume with `q60.E()`.
/// For an odd duration the first sleep is 300 ms (`0x12c`), not 100 ms.
pub fn fade_in_level(duration_ms: u32, elapsed_ms: u32) -> f32 {
    if duration_ms == 0 {
        return 1.0;
    }
    let steps = (duration_ms / 100).max(1);
    let first_ms = if duration_ms % 2 == 1 { 300 } else { 100 };
    let index = if elapsed_ms < first_ms {
        0
    } else {
        ((elapsed_ms - first_ms) / 100).min(steps)
    };
    let ramp = index as f32 * 100.0 / steps as f32;
    ((ramp / 10.0).powi(2) / 100.0).clamp(0.0, 1.0)
}

/// Fade-out curve of `vb.run` (type 2, the pause path of `MusicService.W`).
/// `start` is the player volume when the fade began, 0…1. The first step
/// jumps a third into the table, then the level falls on the same squared
/// curve as the fade-in.
pub fn fade_out_level(start: f32, duration_ms: u32, elapsed_ms: u32) -> f32 {
    if duration_ms == 0 {
        return 0.0;
    }
    let steps = (duration_ms / 100).max(1);
    let index = if elapsed_ms < 100 {
        0
    } else {
        (steps / 3 + 1 + (elapsed_ms - 100) / 100).min(steps)
    };
    let start100 = start.clamp(0.0, 1.0) * 100.0;
    let ramp = start100 * index as f32 / steps as f32;
    ((start100 - (ramp / 10.0).powi(2)) / 100.0).clamp(0.0, 1.0)
}

pub fn now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

pub fn normalize_key(path: &std::path::Path) -> String {
    let text = path.to_string_lossy().replace('/', "\\");
    text.to_lowercase()
}

pub fn canonical_clean(path: &Path) -> PathBuf {
    let canonical = path.canonicalize().unwrap_or_else(|_| path.to_path_buf());
    #[cfg(windows)]
    {
        let text = canonical.to_string_lossy();
        if let Some(rest) = text.strip_prefix(r"\\?\") {
            return PathBuf::from(rest);
        }
    }
    canonical
}

pub fn format_duration(seconds: f64) -> String {
    if !seconds.is_finite() || seconds < 0.0 {
        return "0:00".to_owned();
    }
    let total = seconds.round() as u64;
    let h = total / 3600;
    let m = (total % 3600) / 60;
    let s = total % 60;
    if h > 0 {
        format!("{h}:{m:02}:{s:02}")
    } else {
        format!("{m}:{s:02}")
    }
}

pub fn format_total_duration(seconds: f64) -> String {
    let total = seconds.max(0.0).round() as u64;
    let h = total / 3600;
    let m = (total % 3600) / 60;
    if h > 0 {
        format!("{h} hr {m} min")
    } else {
        format!("{m} min")
    }
}

pub fn format_date(ts: Option<u64>) -> String {
    let Some(ts) = ts else {
        return "Never".to_owned();
    };
    let secs = now_secs().saturating_sub(ts);
    if secs < 60 {
        "Just now".to_owned()
    } else if secs < 3600 {
        format!("{} min ago", secs / 60)
    } else if secs < 86_400 {
        format!("{} hr ago", secs / 3600)
    } else if secs < 86_400 * 7 {
        format!("{} days ago", secs / 86_400)
    } else {
        let days = (ts / 86_400) as i64;
        let z = days + 719_468;
        let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
        let doe = z - era * 146_097;
        let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
        let y = yoe + era * 400;
        let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        let mp = (5 * doy + 2) / 153;
        let d = doy - (153 * mp + 2) / 5 + 1;
        let m = if mp < 10 { mp + 3 } else { mp - 9 };
        let y = if m <= 2 { y + 1 } else { y };
        format!("{y:04}-{m:02}-{d:02}")
    }
}

pub fn initials(text: &str) -> String {
    let mut out = String::new();
    for word in text.split_whitespace() {
        if let Some(ch) = word.chars().next() {
            out.extend(ch.to_uppercase());
        }
        if out.chars().count() >= 2 {
            break;
        }
    }
    if out.is_empty() {
        "♪".to_owned()
    } else {
        out
    }
}

pub fn hex_to_rgb(hex: &str) -> Option<(u8, u8, u8)> {
    let hex = hex.trim_start_matches('#');
    if hex.len() != 6 {
        return None;
    }
    Some((
        u8::from_str_radix(&hex[0..2], 16).ok()?,
        u8::from_str_radix(&hex[2..4], 16).ok()?,
        u8::from_str_radix(&hex[4..6], 16).ok()?,
    ))
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum ThemeMode {
    Light,
    Dark,
    #[default]
    Black,
    DayNight,
    System,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum Accent {
    #[default]
    Ember,
    Sage,
    Rose,
    Cobalt,
    Gold,
}

impl Accent {
    pub const ALL: [Accent; 5] = [
        Accent::Ember,
        Accent::Sage,
        Accent::Rose,
        Accent::Cobalt,
        Accent::Gold,
    ];
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum Language {
    #[default]
    En,
    Zh,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum RepeatMode {
    #[default]
    Off,
    All,
    One,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum ConflictPolicy {
    #[default]
    Replace,
    Merge,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum DeviceProfile {
    #[default]
    Speakers,
    SpeakersHeadphones,
    #[serde(alias = "headphones")]
    HeadsetEarphones,
    Bluetooth,
    Usb,
}

impl DeviceProfile {
    /// `EqualizerActivity2.l0()` builds five built-in rows (device types 1–5)
    /// before any saved Bluetooth/USB devices.
    pub const ALL: [DeviceProfile; 5] = [
        DeviceProfile::Speakers,
        DeviceProfile::SpeakersHeadphones,
        DeviceProfile::HeadsetEarphones,
        DeviceProfile::Bluetooth,
        DeviceProfile::Usb,
    ];

    pub fn id(self) -> &'static str {
        match self {
            DeviceProfile::Speakers => "speakers",
            DeviceProfile::SpeakersHeadphones => "speakers_headphones",
            DeviceProfile::HeadsetEarphones => "headset_earphones",
            DeviceProfile::Bluetooth => "bluetooth",
            DeviceProfile::Usb => "usb",
        }
    }

    pub fn label_en(self) -> &'static str {
        match self {
            DeviceProfile::Speakers => "Built-in speakers",
            DeviceProfile::SpeakersHeadphones => "Speakers/Headphones",
            DeviceProfile::HeadsetEarphones => "Headset/Earphones",
            DeviceProfile::Bluetooth => "Bluetooth",
            DeviceProfile::Usb => "USB headset",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum SortMode {
    #[default]
    Title,
    Artist,
    Album,
    Duration,
    DateAdded,
    LastPlayed,
    Plays,
    Track,
    Filename,
}

impl SortMode {
    pub const ALL: [SortMode; 9] = [
        SortMode::Title,
        SortMode::Artist,
        SortMode::Album,
        SortMode::Duration,
        SortMode::DateAdded,
        SortMode::LastPlayed,
        SortMode::Plays,
        SortMode::Track,
        SortMode::Filename,
    ];
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct Bookmark {
    pub position: f64,
    pub note: String,
    pub created: u64,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default)]
pub struct SongExtra {
    pub title: Option<String>,
    pub artist: Option<String>,
    pub album: Option<String>,
    pub genre: Option<String>,
    pub year: Option<String>,
    pub track: Option<u32>,
    pub cover: Option<String>,
    pub lyrics: Option<String>,
    /// Positive values delay the lyrics; a line at `t` is highlighted at `t + offset`.
    pub lyrics_offset_ms: i64,
    pub favorite: bool,
    pub play_count: u32,
    pub last_played: Option<u64>,
    pub last_position: f64,
    pub bookmarks: Vec<Bookmark>,
    pub replay_gain: Option<f32>,
}

impl Default for SongExtra {
    fn default() -> Self {
        Self {
            title: None,
            artist: None,
            album: None,
            genre: None,
            year: None,
            track: None,
            cover: None,
            lyrics: None,
            lyrics_offset_ms: 0,
            favorite: false,
            play_count: 0,
            last_played: None,
            last_position: 0.0,
            bookmarks: Vec::new(),
            replay_gain: None,
        }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default)]
pub struct Playlist {
    pub id: u64,
    pub name: String,
    pub songs: Vec<String>,
    pub created: u64,
    pub updated: u64,
}

impl Default for Playlist {
    fn default() -> Self {
        Self {
            id: 0,
            name: String::new(),
            songs: Vec::new(),
            created: now_secs(),
            updated: now_secs(),
        }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default)]
pub struct Queue {
    pub id: u64,
    pub name: String,
    pub songs: Vec<String>,
    pub index: usize,
    pub position: f64,
    pub updated: u64,
}

impl Default for Queue {
    fn default() -> Self {
        Self {
            id: 0,
            name: String::new(),
            songs: Vec::new(),
            index: 0,
            position: 0.0,
            updated: now_secs(),
        }
    }
}

impl Queue {
    pub fn label(&self) -> &str {
        if self.name.trim().is_empty() {
            "Untitled queue"
        } else {
            &self.name
        }
    }
}

/// The 10-band table from `c01.a(int)` (`c01.java` line 59, method `a(int)`):
/// 31.5, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000 Hz.
pub const EQ_FREQUENCIES: [f32; EQ_BANDS] = [
    31.5, 63.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0,
];

/// `c01.d(int)` gives Q = 1.0 for every one of the ten bands.
pub const EQ_BAND_Q: f32 = 1.0;
/// `s40.G0()` reads `c50.l()` and falls back to `{-1500, 1500}` millibels.
pub const EQ_GAIN_MIN_DB: f32 = -15.0;
pub const EQ_GAIN_MAX_DB: f32 = 15.0;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum PreampMode {
    /// `k_i_prmd_` = 0, the default.
    #[default]
    Off,
    /// `k_i_prmd_` = -1, `k_i_hdrmg_` 1..=30 dB, default 6 dB.
    Headroom,
    /// `k_i_prmd_` = +1, `k_i_vbg_` 1..=9 dB, default 2 dB.
    Boost,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default)]
pub struct EqProfile {
    pub enabled: bool,
    pub preamp_mode: PreampMode,
    pub headroom_db: f32,
    pub boost_db: f32,
    /// Bass boost strength, 0..1 (the target stores 0..1000, shown as 0..100 %).
    pub bass: f32,
    /// Virtualizer strength, 0..1 (the target stores 0..1000, shown as 0..100 %).
    #[serde(alias = "surround")]
    pub virtualizer: f32,
    /// Balance, 0..200, 100 = centre (`w40.K0`).
    pub balance: f32,
    pub gains: [f32; EQ_BANDS],
    pub preset: String,
    pub revision: u64,
}

impl Default for EqProfile {
    fn default() -> Self {
        Self {
            enabled: true,
            preamp_mode: PreampMode::Off,
            headroom_db: 6.0,
            boost_db: 2.0,
            bass: 0.0,
            virtualizer: 0.0,
            balance: 100.0,
            gains: [0.0; EQ_BANDS],
            preset: "Flat".to_owned(),
            revision: 1,
        }
    }
}

impl EqProfile {
    pub fn touch(&mut self) {
        self.revision = self.revision.wrapping_add(1);
    }

    /// The effective preamp in dB, from `w40.L0()` and `w40.G0(int)`.
    pub fn preamp_db(&self) -> f32 {
        match self.preamp_mode {
            PreampMode::Off => 0.0,
            PreampMode::Headroom => -self.headroom_db.clamp(1.0, 30.0),
            PreampMode::Boost => self.boost_db.clamp(1.0, 9.0),
        }
    }

    /// The peak used by the target's headroom suggestion (`EqualizerActivity2.o0()`).
    /// Bass contributes up to +15 dB below 200 Hz; the mid band is lowered 2 dB,
    /// the treble band 4 dB, and the suggestion keeps 6 dB of the range free.
    pub fn suggested_headroom_db(&self) -> Option<f32> {
        let bass_db = self.bass.clamp(0.0, 1.0) * 15.0;
        let mut peak = f32::NEG_INFINITY;
        for (band, gain) in self.gains.iter().enumerate() {
            let freq = EQ_FREQUENCIES[band];
            let adjusted = if freq < 200.0 {
                gain + bass_db
            } else if freq < 3200.0 {
                gain - 2.0
            } else {
                gain - 4.0
            };
            peak = peak.max(adjusted);
        }
        if peak > 6.0 {
            Some((peak - 6.0).clamp(0.0, 30.0))
        } else {
            None
        }
    }

    /// The 22 ten-band presets in `c50.<clinit>` (10-band list stored at
    /// code unit `0x03b6`). Values are millibels / 100 = dB.
    pub fn apply_preset(&mut self, name: &str) {
        self.preset = name.to_owned();
        self.gains = match name {
            "Bass" => [6.0, 6.0, 4.49, 2.29, 0.70, -3.0, -2.4, -1.5, -1.5, -1.5],
            "Mid" => [-1.5, -1.5, -0.60, 1.10, 3.0, 6.0, 4.0, 0.90, -0.10, -1.5],
            "Treble" => [-1.5, -1.5, -1.5, -1.5, -2.10, -2.80, -0.60, 3.10, 4.50, 6.0],
            "Bass and Treble" => [6.0, 6.0, 4.50, 1.50, -0.20, -3.0, -1.20, 1.70, 4.0, 6.0],
            "Full Bass" => [10.0, 10.0, 8.0, 4.30, 0.50, -6.0, -5.40, -4.40, -4.0, -3.0],
            "Full Mid" => [-3.0, -3.0, -1.20, 2.20, 5.10, 9.80, 6.80, 1.80, -0.10, -3.0],
            "Full Treble" => [
                -3.0, -3.0, -3.50, -4.50, -5.10, -5.70, -1.80, 4.70, 6.80, 10.0,
            ],
            "Reduced Bass" => [-10.0, -10.0, -8.0, -4.40, -2.80, 0.0, 0.0, 0.0, 0.0, 0.0],
            "Reduced Mid" => [
                0.0, 0.0, -1.60, -4.60, -6.60, -10.0, -7.80, -4.30, -2.60, 0.0,
            ],
            "Reduced Treble" => [0.0, 0.0, 0.0, 0.0, 0.0, -0.10, -1.80, -4.70, -6.80, -10.0],
            "No Bass" => [-15.0, -15.0, -12.80, -8.80, -5.50, 0.0, 0.0, 0.0, 0.0, 0.0],
            "No Mid" => [
                0.0, 0.0, -3.30, -9.10, -11.30, -15.0, -12.60, -8.60, -5.20, 0.0,
            ],
            "No Treble" => [0.0, 0.0, 0.0, 0.0, 0.0, -0.30, -3.60, -9.20, -12.0, -15.0],
            "Classic" => [0.0, 0.0, 0.0, 0.0, 0.0, -0.10, -1.20, -3.0, -3.50, -4.10],
            "Dance" => [5.80, 3.20, 2.10, 0.0, 0.0, -2.70, -2.0, -2.20, -0.60, 0.10],
            "Live" => [-4.50, -3.80, 2.20, 2.20, 2.20, 2.20, 2.10, 1.50, 1.50, 1.50],
            "Speaker" => [-9.0, -8.90, -6.30, -1.40, 0.20, 3.0, 3.0, 3.0, 3.0, 3.0],
            "Pop" => [1.50, 4.50, 5.80, 3.0, 1.50, 0.0, 0.0, 0.0, 1.50, 3.0],
            "Rock" => [5.80, 3.20, 1.30, -3.0, -2.30, 2.30, 3.60, 5.80, 5.80, 5.80],
            "Soft" => [
                5.50, 2.50, 0.90, -2.60, -5.0, -10.60, -12.40, -13.30, -10.60, -6.70,
            ],
            "Techno" => [5.80, 5.80, 2.80, -2.50, -1.70, -0.20, 2.50, 6.60, 7.0, 7.75],
            _ => [0.0; EQ_BANDS],
        };
        self.touch();
    }
}

/// The preset names from `d50.a..v`, decoded by the target's one-letter
/// Caesar shift. Order matches `c50.<clinit>`.
pub const EQ_PRESETS: [&str; 22] = [
    "Flat",
    "Bass",
    "Mid",
    "Treble",
    "Bass and Treble",
    "Full Bass",
    "Full Mid",
    "Full Treble",
    "Reduced Bass",
    "Reduced Mid",
    "Reduced Treble",
    "No Bass",
    "No Mid",
    "No Treble",
    "Classic",
    "Dance",
    "Live",
    "Speaker",
    "Pop",
    "Rock",
    "Soft",
    "Techno",
];

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default)]
pub struct Settings {
    pub theme: ThemeMode,
    pub accent: Accent,
    pub language: Language,
    pub folder_hierarchical: bool,
    pub scan_on_start: bool,
    pub scan_hidden: bool,
    pub only_included: bool,
    pub previous_always: bool,
    pub shuffle_scope_all: bool,
    pub gapless: bool,
    /// `k_i_cfd` (crossfade ms) is subtracted from the play-count target.
    pub crossfade_seconds: f32,
    pub min_play_percent: u32,
    pub queue_conflict: ConflictPolicy,
    pub resume_other_queues: bool,
    pub jump_queues: bool,
    pub eq_enabled: bool,
    pub eq_device: DeviceProfile,
    pub eq_per_device: bool,
    pub normalize: bool,
    /// `k_i_slptrh_l`, default false.
    pub sleep_wait_till_end: bool,
    /// `k_i_slptrh_gfls`, default false.
    pub sleep_fade: bool,
    /// `k_jia_slptrh_hm`, newest last, at most five (default 1:30 and 0:15).
    pub sleep_history_hm: Vec<(u32, u32)>,
    /// `k_jia_slptrh_n`, newest last, at most five (default 10 and 5).
    pub sleep_history_n: Vec<u32>,
    /// `k_i_dstt`, default 06:00 (360 minutes).
    pub day_start_minutes: u32,
    /// `k_i_dedt`, default 17:59 (1079 minutes).
    pub day_end_minutes: u32,
    pub volume: f32,
    pub playback_speed: f32,
    /// Base size of lyric lines in the now-playing panel, 10–24 px.
    pub lyrics_font_size: f32,
    /// Lyric text opacity in percent, 30–100.
    pub lyrics_opacity: u8,
    /// `k_b_fdid`: "Fade-in volume when play", default 0, 0…4000 ms, step 250.
    pub fade_in_ms: u32,
    /// `k_b_fdod`: "Fade-out volume when pause", default 0, 0…4000 ms, step 250.
    pub fade_out_ms: u32,
    /// Exact name of the chosen sound output; empty means the system default.
    pub output_device: String,
    pub show_cover_in_lists: bool,
    pub sound_check: bool,
    pub scan_small_files: bool,
    pub min_duration_seconds: u32,
    /// Shows the developer console window. Off by default; release builds are
    /// GUI-subsystem executables and do not open a terminal unless this is on.
    pub developer_mode: bool,
    /// The window close button keeps Lilt in the notification area.
    pub close_to_tray: bool,
    /// Minimizing the window also hides it from the taskbar.
    pub minimize_to_tray: bool,
    /// Register the system-wide Ctrl+Alt shortcuts while Lilt is running.
    pub global_hotkeys: bool,
    /// Always-on-top desktop lyric window.
    pub desktop_lyrics: bool,
    /// Hides desktop lyric controls, makes the window click-through and stops dragging.
    pub desktop_lyrics_locked: bool,
    /// Active line size for the desktop lyric window, 18-56 px.
    pub desktop_lyrics_font_size: f32,
    /// Compact always-on-top player window.
    pub mini_player: bool,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            // `in.krosbits.musicolet.b.a(Context)` 0x001e writes THMR_BT = "2".
            theme: ThemeMode::Black,
            accent: Accent::Ember,
            language: Language::En,
            // `b.a` 0x0016 writes key_folder_structure = "1" (hierarchical).
            folder_hierarchical: true,
            // `k_b_atsc` default true.
            scan_on_start: true,
            // `k_b_schdfl` / `k_b_scnmfl`: false.
            scan_hidden: false,
            only_included: false,
            // `PP_PBTNBH` default "1" = restart after 10 s.
            previous_always: false,
            // `k_i_sflm_ob` default 0 = shuffle all songs in the queue.
            shuffle_scope_all: true,
            // `k_b_glp` default true.
            gapless: true,
            // `k_i_cfd` default 0 ms.
            crossfade_seconds: 0.0,
            // `MINPBR` default "30".
            min_play_percent: DEFAULT_PLAY_COUNT_PERCENT,
            // `k_i_hwqwsnaex` default 0 = replace.
            queue_conflict: ConflictPolicy::Replace,
            // `R_RFLP` default true.
            resume_other_queues: true,
            // `RQM` default 2 = jump to the next queue.
            jump_queues: true,
            eq_enabled: true,
            eq_device: DeviceProfile::Speakers,
            eq_per_device: true,
            normalize: false,
            // `k_i_slptrh_l` / `k_i_slptrh_gfls` default false.
            sleep_wait_till_end: false,
            sleep_fade: false,
            sleep_history_hm: vec![(1, 30), (0, 15)],
            sleep_history_n: vec![10, 5],
            day_start_minutes: 360,
            day_end_minutes: 1079,
            volume: 0.8,
            // `k_f_plyspd4` default 10000 (1.00×).
            playback_speed: 1.0,
            lyrics_font_size: 13.0,
            lyrics_opacity: 100,
            // `k_b_fdid` / `k_b_fdod` default 0 ms.
            fade_in_ms: 0,
            fade_out_ms: 0,
            output_device: String::new(),
            show_cover_in_lists: true,
            sound_check: true,
            scan_small_files: false,
            min_duration_seconds: 0,
            developer_mode: false,
            close_to_tray: true,
            minimize_to_tray: false,
            global_hotkeys: true,
            desktop_lyrics: false,
            desktop_lyrics_locked: false,
            desktop_lyrics_font_size: 34.0,
            mini_player: false,
        }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default)]
pub struct Persisted {
    pub version: u32,
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
    pub starter_library: bool,
    pub recent_searches: Vec<String>,
    pub first_run: bool,
}

impl Default for Persisted {
    fn default() -> Self {
        Self {
            version: 1,
            settings: Settings::default(),
            library_roots: Vec::new(),
            excluded: Vec::new(),
            included: Vec::new(),
            playlists: Vec::new(),
            queues: Vec::new(),
            active_queue: 0,
            songs: HashMap::new(),
            eq_profiles: HashMap::new(),
            next_id: 1,
            starter_library: false,
            recent_searches: Vec::new(),
            first_run: true,
        }
    }
}

impl Persisted {
    pub fn alloc_id(&mut self) -> u64 {
        let id = self.next_id.max(1);
        self.next_id = id + 1;
        id
    }

    pub fn extra(&self, key: &str) -> SongExtra {
        self.songs.get(key).cloned().unwrap_or_default()
    }

    pub fn extra_mut(&mut self, key: &str) -> &mut SongExtra {
        self.songs.entry(key.to_owned()).or_default()
    }

    pub fn active_queue(&self) -> Option<&Queue> {
        self.queues.iter().find(|q| q.id == self.active_queue)
    }

    pub fn active_queue_mut(&mut self) -> Option<&mut Queue> {
        self.queues.iter_mut().find(|q| q.id == self.active_queue)
    }

    pub fn queue_by_id(&self, id: u64) -> Option<&Queue> {
        self.queues.iter().find(|q| q.id == id)
    }

    pub fn queue_by_name(&self, name: &str) -> Option<&Queue> {
        self.queues.iter().find(|q| q.name == name)
    }

    pub fn playlist_by_id(&self, id: u64) -> Option<&Playlist> {
        self.playlists.iter().find(|p| p.id == id)
    }

    pub fn eq_profile(&self, device: DeviceProfile) -> EqProfile {
        self.eq_profiles
            .get(device.id())
            .cloned()
            .unwrap_or_default()
    }

    pub fn set_eq_profile(&mut self, device: DeviceProfile, profile: EqProfile) {
        self.eq_profiles.insert(device.id().to_owned(), profile);
    }
}

#[derive(Clone, Debug)]
pub struct Song {
    pub key: String,
    pub path: PathBuf,
    pub title: String,
    pub artist: String,
    pub album: String,
    pub album_artist: String,
    pub genre: String,
    pub year: String,
    pub track: u32,
    pub disc: u32,
    pub duration: f64,
    pub sample_rate: u32,
    pub channels: u8,
    pub bitrate: u32,
    pub format: String,
    pub size: u64,
    pub modified: u64,
    pub added: u64,
    pub favorite: bool,
    pub play_count: u32,
    pub last_played: Option<u64>,
    pub last_position: f64,
    pub lyrics_override: Option<String>,
    pub cover_path: Option<PathBuf>,
    pub replay_gain: Option<f32>,
    pub bookmarks: Vec<Bookmark>,
}

impl Song {
    pub fn exists(&self) -> bool {
        self.path.is_file()
    }

    pub fn display_title(&self) -> &str {
        if self.title.trim().is_empty() {
            self.path
                .file_stem()
                .and_then(|s| s.to_str())
                .unwrap_or("Untitled")
        } else {
            &self.title
        }
    }

    pub fn display_artist(&self) -> &str {
        if self.artist.trim().is_empty() {
            "Unknown artist"
        } else {
            &self.artist
        }
    }

    pub fn display_album(&self) -> &str {
        if self.album.trim().is_empty() {
            "Unknown album"
        } else {
            &self.album
        }
    }

    pub fn folder(&self) -> &std::path::Path {
        self.path.parent().unwrap_or(std::path::Path::new(""))
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Page {
    Folders,
    Playlists,
    Songs,
    Albums,
    Artists,
    Queues,
    MostPlayed,
    Recent,
    History,
    Bookmarks,
    Equalizer,
    Settings,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum DetailPage {
    Album(String),
    Artist(String),
    Playlist(u64),
    Queue(u64),
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
pub enum HomeTab {
    #[default]
    Folders,
    Playlists,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
pub enum FolderLayout {
    #[default]
    Grid,
    List,
}

#[derive(Clone, Debug, Default)]
pub struct FolderInfo {
    pub path: PathBuf,
    pub name: String,
    pub direct: Vec<usize>,
    pub children: Vec<PathBuf>,
    pub total_songs: usize,
    pub total_duration: f64,
}

#[derive(Clone, Debug, Default)]
pub struct FolderIndex {
    pub folders: HashMap<PathBuf, FolderInfo>,
    pub roots: Vec<PathBuf>,
}

fn path_has_prefix(path: &std::path::Path, prefix: &std::path::Path) -> bool {
    #[cfg(windows)]
    {
        let p = path.to_string_lossy().replace('/', "\\").to_lowercase();
        let q = prefix
            .to_string_lossy()
            .replace('/', "\\")
            .trim_end_matches('\\')
            .to_lowercase();
        p == q || p.starts_with(&(q + "\\"))
    }
    #[cfg(not(windows))]
    {
        path.starts_with(prefix)
    }
}

pub fn path_under(path: &std::path::Path, prefix: &std::path::Path) -> bool {
    path_has_prefix(path, prefix)
}

impl FolderIndex {
    pub fn build(songs: &[Song], roots: &[PathBuf]) -> Self {
        let mut index = FolderIndex {
            folders: HashMap::new(),
            roots: roots.to_vec(),
        };
        let canon_roots: Vec<PathBuf> = roots.iter().map(|r| canonical_clean(r)).collect();

        for (song_index, song) in songs.iter().enumerate() {
            let Some(root) = canon_roots
                .iter()
                .filter(|root| path_under(&song.path, root))
                .max_by_key(|root| root.components().count())
            else {
                continue;
            };
            let mut dir = song
                .path
                .parent()
                .map(|p| p.to_path_buf())
                .unwrap_or_else(|| root.clone());
            if !path_under(&dir, root) {
                continue;
            }

            let mut first = true;
            loop {
                let entry = index.folders.entry(dir.clone()).or_insert_with(|| {
                    let name = if dir == *root {
                        root.file_name()
                            .map(|s| s.to_string_lossy().to_string())
                            .unwrap_or_else(|| dir.to_string_lossy().to_string())
                    } else {
                        dir.file_name()
                            .map(|s| s.to_string_lossy().to_string())
                            .unwrap_or_else(|| dir.to_string_lossy().to_string())
                    };
                    FolderInfo {
                        path: dir.clone(),
                        name,
                        ..Default::default()
                    }
                });
                entry.total_songs += 1;
                entry.total_duration += song.duration;
                if first {
                    entry.direct.push(song_index);
                    first = false;
                }

                if dir == *root {
                    break;
                }
                let Some(parent) = dir.parent().map(|p| p.to_path_buf()) else {
                    break;
                };
                if !path_under(&parent, root) {
                    break;
                }
                let parent_entry = index.folders.entry(parent.clone()).or_insert_with(|| {
                    let name = if parent == *root {
                        root.file_name()
                            .map(|s| s.to_string_lossy().to_string())
                            .unwrap_or_else(|| parent.to_string_lossy().to_string())
                    } else {
                        parent
                            .file_name()
                            .map(|s| s.to_string_lossy().to_string())
                            .unwrap_or_else(|| parent.to_string_lossy().to_string())
                    };
                    FolderInfo {
                        path: parent.clone(),
                        name,
                        ..Default::default()
                    }
                });
                if !parent_entry.children.contains(&dir) {
                    parent_entry.children.push(dir.clone());
                }
                dir = parent;
            }
        }

        for info in index.folders.values_mut() {
            info.children.sort_by_key(|p| {
                p.file_name()
                    .map(|s| s.to_string_lossy().to_lowercase())
                    .unwrap_or_default()
            });
        }
        index
    }

    pub fn get(&self, path: &std::path::Path) -> Option<&FolderInfo> {
        self.folders.get(path)
    }

    pub fn root_info(&self, path: &std::path::Path) -> Option<&FolderInfo> {
        self.folders.get(path)
    }
}

pub fn chars_for_index(text: &str) -> char {
    text.chars()
        .find(|c| c.is_alphanumeric())
        .map(|c| c.to_ascii_uppercase())
        .unwrap_or('#')
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn durations_format_cleanly() {
        assert_eq!(format_duration(0.0), "0:00");
        assert_eq!(format_duration(65.4), "1:05");
        assert_eq!(format_duration(3661.0), "1:01:01");
    }

    #[test]
    fn eq_presets_match_target_tables() {
        let mut eq = EqProfile::default();
        eq.apply_preset("Bass");
        assert_eq!(
            eq.gains,
            [6.0, 6.0, 4.49, 2.29, 0.70, -3.0, -2.4, -1.5, -1.5, -1.5]
        );
        eq.apply_preset("No Bass");
        assert_eq!(eq.gains[0], -15.0);
        eq.apply_preset("Flat");
        assert_eq!(eq.gains, [0.0; EQ_BANDS]);
        assert_eq!(
            EQ_FREQUENCIES,
            [31.5, 63.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0]
        );
        assert_eq!(EQ_BAND_Q, 1.0);
    }

    #[test]
    fn defaults_match_the_target() {
        let settings = Settings::default();
        assert_eq!(settings.theme, ThemeMode::Black);
        assert!(settings.folder_hierarchical);
        assert!(!settings.previous_always);
        assert_eq!(settings.min_play_percent, 30);
        assert_eq!(settings.queue_conflict, ConflictPolicy::Replace);
        assert!(settings.jump_queues);
        assert!(settings.gapless);
        assert_eq!(settings.fade_in_ms, 0);
        assert_eq!(settings.fade_out_ms, 0);
        assert_eq!(settings.lyrics_font_size, 13.0);
        assert_eq!(settings.lyrics_opacity, 100);
        assert!(settings.close_to_tray);
        assert!(!settings.minimize_to_tray);
        assert!(settings.global_hotkeys);
        assert!(!settings.desktop_lyrics);
        assert!(!settings.desktop_lyrics_locked);
        assert_eq!(settings.desktop_lyrics_font_size, 34.0);
        assert!(!settings.mini_player);
        assert_eq!(settings.sleep_history_hm, vec![(1, 30), (0, 15)]);
        assert_eq!(settings.sleep_history_n, vec![10, 5]);

        let eq = EqProfile::default();
        assert_eq!(eq.preamp_mode, PreampMode::Off);
        assert_eq!(eq.headroom_db, 6.0);
        assert_eq!(eq.boost_db, 2.0);
        assert_eq!(eq.balance, 100.0);
    }

    #[test]
    fn old_song_json_defaults_lyrics_offset() {
        let extra: SongExtra = serde_json::from_str(r#"{"favorite":true,"play_count":3}"#)
            .expect("old SongExtra JSON should load");
        assert_eq!(extra.lyrics_offset_ms, 0);
        assert!(extra.favorite);
        assert_eq!(extra.play_count, 3);
    }

    #[test]
    fn initials_are_short() {
        assert_eq!(initials("Aurora Drift"), "AD");
        assert_eq!(initials(""), "♪");
    }

    #[test]
    fn queue_names_are_case_sensitive() {
        let mut persisted = Persisted::default();
        persisted.queues.push(Queue {
            id: 1,
            name: "Road".to_owned(),
            ..Queue::default()
        });
        persisted.queues.push(Queue {
            id: 2,
            name: "road".to_owned(),
            ..Queue::default()
        });
        assert_eq!(persisted.queue_by_name("road").map(|q| q.id), Some(2));
        assert_eq!(persisted.queue_by_name("Road").map(|q| q.id), Some(1));
        assert!(persisted.queue_by_name("ROAD").is_none());
    }

    #[test]
    fn fade_curves_match_the_target_threads() {
        // `ub.run`: quadratic curve, 0 at the start and 100% at the end.
        assert_eq!(fade_in_level(0, 0), 1.0);
        assert_eq!(fade_in_level(1000, 0), 0.0);
        assert!((fade_in_level(1000, 600) - 0.25).abs() < 0.001);
        assert!((fade_in_level(1000, 1100) - 1.0).abs() < 0.001);
        // Odd durations get the target's 300 ms first sleep (751 ms here).
        assert_eq!(fade_in_level(751, 299), 0.0);
        assert_eq!(fade_in_level(751, 300), 0.0);
        assert!((fade_in_level(751, 400) - 0.0204).abs() < 0.001);
        // `vb.run` type 2: 1/3 jump after the first 100 ms, then squared.
        assert_eq!(fade_out_level(1.0, 1000, 0), 1.0);
        assert!((fade_out_level(1.0, 1000, 100) - 0.84).abs() < 0.001);
        assert!((fade_out_level(1.0, 1000, 500) - 0.36).abs() < 0.001);
        assert_eq!(fade_out_level(1.0, 1000, 1000), 0.0);
    }

    #[test]
    fn developer_mode_defaults_off_and_survives_old_state_files() {
        assert!(!Settings::default().developer_mode);
        let parsed: Persisted = serde_json::from_str("{}").unwrap();
        assert!(!parsed.settings.developer_mode);
        let parsed: Persisted =
            serde_json::from_str(r#"{"settings":{"developer_mode":true}}"#).unwrap();
        assert!(parsed.settings.developer_mode);
    }

    #[test]
    fn desktop_settings_default_and_load_from_old_state_files() {
        let parsed: Persisted = serde_json::from_str("{}").unwrap();
        assert!(parsed.settings.close_to_tray);
        assert!(!parsed.settings.minimize_to_tray);
        assert!(parsed.settings.global_hotkeys);
        assert!(!parsed.settings.desktop_lyrics);
        assert!(!parsed.settings.mini_player);
        assert_eq!(parsed.settings.desktop_lyrics_font_size, 34.0);
        let parsed: Persisted = serde_json::from_str(
            r#"{"settings":{"close_to_tray":false,"minimize_to_tray":true,"global_hotkeys":false,"desktop_lyrics":true,"desktop_lyrics_locked":true,"desktop_lyrics_font_size":42,"mini_player":true}}"#,
        )
        .unwrap();
        assert!(!parsed.settings.close_to_tray);
        assert!(parsed.settings.minimize_to_tray);
        assert!(!parsed.settings.global_hotkeys);
        assert!(parsed.settings.desktop_lyrics);
        assert!(parsed.settings.desktop_lyrics_locked);
        assert_eq!(parsed.settings.desktop_lyrics_font_size, 42.0);
        assert!(parsed.settings.mini_player);
    }
}
