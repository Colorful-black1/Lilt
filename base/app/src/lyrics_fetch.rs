//! Online lyrics: LRCLIB lookup, match scoring, a small persistent cache, and
//! the manual AI fallback. Network requests only run on the worker thread so
//! playback and the UI never wait for them.

use crate::model::now_secs;
use eframe::egui;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::fs;
use std::io::Read;
use std::path::PathBuf;
use std::sync::mpsc::{channel, Receiver, Sender};
use std::sync::{Arc, OnceLock};
use std::thread;
use std::time::{Duration, Instant};
use url::Url;

const LRCLIB_SEARCH: &str = "https://lrclib.net/api/search";
const USER_AGENT: &str = "Lilt/0.1.0 (https://github.com/Colorful-black1/Lilt)";
const LRCLIB_MIN_INTERVAL: Duration = Duration::from_millis(350);
const MAX_LRCLIB_BODY: u64 = 2 * 1024 * 1024;
const MAX_AI_BODY: u64 = 1024 * 1024;
const LRCLIB_NOT_FOUND_TTL: u64 = 7 * 24 * 60 * 60;
const LRCLIB_INSTRUMENTAL_TTL: u64 = 30 * 24 * 60 * 60;
const LRCLIB_AMBIGUOUS_TTL: u64 = 24 * 60 * 60;
const LRCLIB_ERROR_TTL: u64 = 10 * 60;

// ---------------------------------------------------------------------------
// Provider presets (same set and endpoints as Tool_box's 星轨链路)
// ---------------------------------------------------------------------------

pub struct AiProviderPreset {
    pub id: &'static str,
    pub name: &'static str,
    pub base_url: &'static str,
    pub models: &'static [&'static str],
}

pub static AI_PROVIDERS: &[AiProviderPreset] = &[
    AiProviderPreset {
        id: "deepseek",
        name: "DeepSeek",
        base_url: "https://api.deepseek.com/v1",
        models: &[
            "deepseek-v4-pro",
            "deepseek-v4-flash",
            "deepseek-v4-flash-vision-exp",
        ],
    },
    AiProviderPreset {
        id: "glm",
        name: "智谱 GLM",
        base_url: "https://open.bigmodel.cn/api/paas/v4",
        models: &["glm-5.3", "glm-5.3-flash", "glm-5.2", "glm-4.7-flash"],
    },
    AiProviderPreset {
        id: "mimo",
        name: "小米 MiMo",
        base_url: "https://api.xiaomimimo.com/v1",
        models: &["mimo-v2.5-pro", "mimo-v2.5"],
    },
    AiProviderPreset {
        id: "openai",
        name: "OpenAI GPT",
        base_url: "https://api.openai.com/v1",
        models: &["gpt-5.1", "gpt-5", "gpt-4.1", "gpt-4o", "gpt-4o-mini"],
    },
    AiProviderPreset {
        id: "opencode-go",
        name: "OpenCode Go",
        base_url: "https://opencode.ai/zen/go/v1",
        models: &[
            "glm-5.3",
            "glm-5.3-flash",
            "glm-5.2",
            "grok-4.6",
            "grok-4.7",
            "gpt-6-luna",
            "gpt-5.6-luna",
            "claude-haiku-5-5",
            "kimi-k3",
            "kimi-k2.7-code",
            "minimax-m3",
            "minimax-m2.7",
            "mimo-v2.6-pro",
            "qwen3.7-max",
            "qwen3.8-max",
            "deepseek-v4-pro",
            "deepseek-v4.1-flash",
        ],
    },
    AiProviderPreset {
        id: "opencode-zen",
        name: "OpenCode Zen",
        base_url: "https://opencode.ai/zen/v1",
        models: &[
            "deepseek-v4.1-flash-free",
            "glm-5.3-flash-free",
            "mimo-v2.6-flash-free",
            "minimax-m3.1-flash-free",
            "longcat-2.5-free",
            "nemotron-3.5-lightning-free",
            "nemotron-3.5-super-vl-free",
            "big-pickle",
        ],
    },
    AiProviderPreset {
        id: "kimi",
        name: "Kimi 月之暗面",
        base_url: "https://api.moonshot.cn/v1",
        models: &[
            "kimi-k2.5",
            "kimi-k2-turbo-preview",
            "moonshot-v1-128k",
            "moonshot-v1-32k",
        ],
    },
    AiProviderPreset {
        id: "qwen",
        name: "通义千问 Qwen",
        base_url: "https://dashscope.aliyuncs.com/compatible-mode/v1",
        models: &[
            "qwen3-max",
            "qwen3.5-plus",
            "qwen3.5-turbo",
            "qwen-plus",
            "qwen-turbo",
        ],
    },
    AiProviderPreset {
        id: "siliconflow",
        name: "SiliconFlow 硅基流动",
        base_url: "https://api.siliconflow.cn/v1",
        models: &[
            "deepseek-ai/DeepSeek-V3.2",
            "deepseek-ai/DeepSeek-R1",
            "Qwen/Qwen3-235B-A22B-Instruct-2507",
            "moonshotai/Kimi-K2-Instruct-0905",
        ],
    },
    AiProviderPreset {
        id: "custom",
        name: "自定义（OpenAI 兼容）",
        base_url: "",
        models: &[],
    },
];

pub fn find_ai_provider(id: &str) -> &'static AiProviderPreset {
    AI_PROVIDERS
        .iter()
        .find(|preset| preset.id == id)
        .unwrap_or(&AI_PROVIDERS[0])
}

#[derive(Clone, Debug)]
pub struct AiConfig {
    pub provider: String,
    pub base_url: String,
    pub model: String,
    pub api_key: String,
}

// ---------------------------------------------------------------------------
// LRCLIB records and match scoring
// ---------------------------------------------------------------------------

#[derive(Clone, Debug, Deserialize, Serialize, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct LrclibRecord {
    #[serde(deserialize_with = "null_as_default")]
    pub id: u64,
    #[serde(deserialize_with = "null_as_default")]
    pub track_name: String,
    #[serde(deserialize_with = "null_as_default")]
    pub artist_name: String,
    #[serde(deserialize_with = "null_as_default")]
    pub album_name: String,
    #[serde(deserialize_with = "null_as_default")]
    pub duration: f64,
    #[serde(deserialize_with = "null_as_default")]
    pub instrumental: bool,
    #[serde(deserialize_with = "null_as_default")]
    pub has_word_sync: bool,
    pub plain_lyrics: Option<String>,
    pub synced_lyrics: Option<String>,
}

/// LRCLIB occasionally returns `null` for fields documented as numbers or
/// strings. A single null must not discard an otherwise usable search result.
fn null_as_default<'de, D, T>(deserializer: D) -> Result<T, D::Error>
where
    D: serde::Deserializer<'de>,
    T: Deserialize<'de> + Default,
{
    Ok(Option::<T>::deserialize(deserializer)?.unwrap_or_default())
}

impl LrclibRecord {
    pub fn text(&self) -> Option<&str> {
        self.synced_lyrics
            .as_deref()
            .filter(|text| !text.trim().is_empty())
            .or_else(|| {
                self.plain_lyrics
                    .as_deref()
                    .filter(|text| !text.trim().is_empty())
            })
    }

    pub fn is_synced(&self) -> bool {
        self.synced_lyrics
            .as_deref()
            .map(|text| text.contains('[') && text.contains(']'))
            .unwrap_or(false)
    }
}

#[derive(Clone, Debug)]
pub struct LyricsCandidate {
    pub record: LrclibRecord,
    pub score: f32,
    pub duration_diff: f64,
    pub title_exact: bool,
    pub artist_exact: bool,
}

impl LyricsCandidate {
    pub fn duration_label(&self) -> String {
        if self.duration_diff.is_finite() {
            format!("{:.0}s", self.record.duration)
        } else {
            "?".to_owned()
        }
    }
}

#[derive(Clone, Debug)]
pub enum MatchOutcome {
    Best(LyricsCandidate),
    Ambiguous(Vec<LyricsCandidate>),
    Instrumental,
    None,
}

fn strip_brackets(input: &str) -> String {
    let mut out = String::with_capacity(input.len());
    let mut depth = 0i32;
    for ch in input.chars() {
        match ch {
            '(' | '[' | '{' | '（' | '【' | '｛' => depth += 1,
            ')' | ']' | '}' | '）' | '】' | '｝' => depth = (depth - 1).max(0),
            _ if depth == 0 => out.push(ch),
            _ => {}
        }
    }
    out
}

/// Lowercase, drop punctuation and bracketed suffixes, collapse whitespace.
pub fn normalize_match_text(input: &str) -> String {
    let mut text = strip_brackets(input).to_lowercase();
    for marker in [" - remastered", " - remaster", " remastered", " remaster"] {
        if let Some(index) = text.find(marker) {
            text.truncate(index);
        }
    }
    for suffix in [
        " - remastered",
        " - remaster",
        " - live",
        " - demo",
        " - edit",
        " remastered",
        " remaster",
        " live version",
        " album version",
        " live",
    ] {
        if text.ends_with(suffix) {
            text.truncate(text.len() - suffix.len());
        }
    }
    for marker in [" feat.", " feat ", " ft.", " ft ", " featuring "] {
        if let Some(index) = text.find(marker) {
            text.truncate(index);
        }
    }
    let mut out = String::with_capacity(text.len());
    let mut last_space = false;
    for ch in text.chars() {
        if ch.is_alphanumeric() || ch == '\'' {
            out.push(ch);
            last_space = false;
        } else if !last_space && !out.is_empty() {
            out.push(' ');
            last_space = true;
        }
    }
    out.trim().to_owned()
}

fn artist_tokens(input: &str) -> Vec<String> {
    let mut expanded = input.to_lowercase();
    for marker in [" feat.", " feat ", " ft.", " ft ", " featuring "] {
        expanded = expanded.replace(marker, ";");
    }
    expanded
        .split([';', '/', '&', ',', '、', '|'])
        .map(normalize_match_text)
        .filter(|token| !token.is_empty() && token != "the")
        .collect()
}

fn artist_exact(left: &str, right: &str) -> bool {
    let left_tokens = artist_tokens(left);
    let right_tokens = artist_tokens(right);
    if left_tokens.is_empty() || right_tokens.is_empty() {
        return false;
    }
    right_tokens.iter().any(|token| {
        left_tokens.iter().any(|other| {
            other == token
                || (token.chars().count() >= 4
                    && other.chars().count() >= 4
                    && (other.contains(token) || token.contains(other)))
        })
    })
}

fn title_exact(left: &str, right: &str) -> bool {
    let left = normalize_match_text(left);
    let right = normalize_match_text(right);
    !left.is_empty()
        && !right.is_empty()
        && (left == right
            || (left.chars().count().min(right.chars().count()) >= 6
                && (left.contains(&right) || right.contains(&left))))
}

pub fn rank_candidates(
    records: Vec<LrclibRecord>,
    title: &str,
    artist: &str,
    duration: f64,
) -> Vec<LyricsCandidate> {
    let artist_unknown =
        artist.trim().is_empty() || normalize_match_text(artist) == "unknown artist";
    let mut ranked = Vec::new();
    for record in records {
        if record.text().is_none() && !record.instrumental {
            continue;
        }
        let title_same = title_exact(title, &record.track_name);
        if !title_same {
            continue;
        }
        let artist_same = !artist_unknown && artist_exact(artist, &record.artist_name);
        let diff = if duration > 1.0 && record.duration > 1.0 {
            (duration - record.duration).abs()
        } else {
            f64::INFINITY
        };
        let mut score = 0.55f32;
        if artist_same {
            score += 0.25;
        } else if artist_unknown {
            score += 0.15;
        }
        if diff <= 2.5 {
            score += 0.20;
        } else if diff <= 5.0 {
            score += 0.08;
        }
        if record.is_synced() {
            score += 0.03;
        }
        if record.instrumental {
            score -= 0.25;
        }
        if !duration.is_finite() || duration <= 1.0 {
            score += 0.10; // unknown duration: do not punish the match twice
        }
        ranked.push(LyricsCandidate {
            record,
            score: score.clamp(0.0, 1.0),
            duration_diff: diff,
            title_exact: title_same,
            artist_exact: artist_same || artist_unknown,
        });
    }
    ranked.sort_by(|left, right| {
        right
            .score
            .partial_cmp(&left.score)
            .unwrap_or(std::cmp::Ordering::Equal)
            .then_with(|| {
                left.duration_diff
                    .partial_cmp(&right.duration_diff)
                    .unwrap_or(std::cmp::Ordering::Equal)
            })
    });
    ranked
}

pub fn choose_match(mut ranked: Vec<LyricsCandidate>) -> MatchOutcome {
    if ranked.is_empty() {
        return MatchOutcome::None;
    }
    let has_non_instrumental = ranked
        .iter()
        .any(|candidate| !candidate.record.instrumental);
    if !has_non_instrumental {
        return MatchOutcome::Instrumental;
    }
    ranked.retain(|candidate| !candidate.record.instrumental);
    let top = ranked[0].clone();
    let exact_enough = top.title_exact
        && top.artist_exact
        && (top.duration_diff <= 2.5 || !top.duration_diff.is_finite());
    if exact_enough && top.score >= 0.90 {
        return MatchOutcome::Best(top);
    }
    let ambiguous: Vec<LyricsCandidate> = ranked
        .into_iter()
        .filter(|candidate| candidate.score >= 0.62)
        .collect();
    if ambiguous.is_empty() {
        MatchOutcome::None
    } else {
        MatchOutcome::Ambiguous(ambiguous)
    }
}

// ---------------------------------------------------------------------------
// Fetch cache
// ---------------------------------------------------------------------------

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CachedStatus {
    NotFound,
    Instrumental,
    Ambiguous,
    Error,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default)]
pub struct FetchEntry {
    pub status: CachedStatus,
    pub last_attempt: u64,
    pub next_retry: u64,
    pub candidates: usize,
    pub error: Option<String>,
}

impl Default for FetchEntry {
    fn default() -> Self {
        Self {
            status: CachedStatus::Error,
            last_attempt: 0,
            next_retry: 0,
            candidates: 0,
            error: None,
        }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default)]
pub struct FetchCache {
    #[serde(skip)]
    pub path: PathBuf,
    pub entries: HashMap<String, FetchEntry>,
}

impl Default for FetchCache {
    fn default() -> Self {
        Self {
            path: cache_path(),
            entries: HashMap::new(),
        }
    }
}

fn cache_path() -> PathBuf {
    crate::library::data_dir().join("lyrics-fetch.json")
}

impl FetchCache {
    pub fn load() -> Self {
        let path = cache_path();
        let mut cache = Self::default();
        if let Ok(text) = fs::read_to_string(&path) {
            if let Ok(mut parsed) = serde_json::from_str::<FetchCache>(&text) {
                parsed.path = path;
                cache = parsed;
            }
        }
        cache.prune(now_secs());
        cache
    }

    pub fn save(&self) {
        if let Some(parent) = self.path.parent() {
            let _ = fs::create_dir_all(parent);
        }
        if let Ok(text) = serde_json::to_string_pretty(self) {
            let _ = fs::write(&self.path, text);
        }
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn clear(&mut self) {
        self.entries.clear();
        let _ = fs::remove_file(&self.path);
    }

    pub fn prune(&mut self, now: u64) {
        let cutoff = now.saturating_sub(90 * 24 * 60 * 60);
        self.entries.retain(|_, entry| entry.last_attempt >= cutoff);
    }

    pub fn fresh(&self, key: &str, now: u64) -> Option<&FetchEntry> {
        self.entries.get(key).filter(|entry| entry.next_retry > now)
    }

    pub fn remove(&mut self, key: &str) {
        self.entries.remove(key);
    }

    pub fn record(
        &mut self,
        key: &str,
        status: CachedStatus,
        candidates: usize,
        error: Option<String>,
    ) {
        let now = now_secs();
        let ttl = match status {
            CachedStatus::NotFound => LRCLIB_NOT_FOUND_TTL,
            CachedStatus::Instrumental => LRCLIB_INSTRUMENTAL_TTL,
            CachedStatus::Ambiguous => LRCLIB_AMBIGUOUS_TTL,
            CachedStatus::Error => LRCLIB_ERROR_TTL,
        };
        self.entries.insert(
            key.to_owned(),
            FetchEntry {
                status,
                last_attempt: now,
                next_retry: now.saturating_add(ttl),
                candidates,
                error,
            },
        );
        self.save();
    }
}

// ---------------------------------------------------------------------------
// HTTP helpers
// ---------------------------------------------------------------------------

#[derive(Debug)]
enum FetchError {
    Network(String),
    Http(u16, String),
    RateLimited(u64),
    Invalid(String),
}

impl std::fmt::Display for FetchError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            FetchError::Network(message) => write!(f, "{message}"),
            FetchError::Http(code, body) => {
                if body.trim().is_empty() {
                    write!(f, "HTTP {code}")
                } else {
                    write!(f, "HTTP {code}: {}", truncate(body, 180))
                }
            }
            FetchError::RateLimited(seconds) => {
                write!(f, "rate limited, retry after {seconds}s")
            }
            FetchError::Invalid(message) => write!(f, "{message}"),
        }
    }
}

fn truncate(text: &str, limit: usize) -> String {
    let mut out = text.replace(['\r', '\n'], " ");
    if out.chars().count() > limit {
        out = out.chars().take(limit).collect::<String>() + "…";
    }
    out.trim().to_owned()
}

fn read_limited(response: ureq::Response, limit: u64) -> Result<String, FetchError> {
    let mut text = String::new();
    response
        .into_reader()
        .take(limit)
        .read_to_string(&mut text)
        .map_err(|error| FetchError::Network(error.to_string()))?;
    Ok(text)
}

/// ureq's shortcut functions use rustls, which needs a C toolchain. Use the
/// Windows/macOS native TLS backend instead; it also follows the system root
/// store, which matters for users behind a corporate proxy.
fn http_agent() -> Result<&'static ureq::Agent, FetchError> {
    static AGENT: OnceLock<Option<ureq::Agent>> = OnceLock::new();
    let slot = AGENT.get_or_init(|| {
        let tls = ureq::native_tls::TlsConnector::new().ok()?;
        Some(
            ureq::AgentBuilder::new()
                .tls_connector(Arc::new(tls))
                .timeout_connect(Duration::from_secs(10))
                .timeout_read(Duration::from_secs(30))
                .build(),
        )
    });
    slot.as_ref()
        .ok_or_else(|| FetchError::Network("无法初始化系统 TLS".to_owned()))
}

fn request_text(
    method: &str,
    url: &str,
    headers: &[(&str, &str)],
    body: Option<&str>,
    limit: u64,
    timeout: Duration,
) -> Result<String, FetchError> {
    let agent = http_agent()?;
    let request = if method == "POST" {
        agent.post(url)
    } else {
        agent.get(url)
    };
    let mut request = request.timeout(timeout);
    for (name, value) in headers {
        request = request.set(name, value);
    }
    let result = match body {
        Some(body) => request.send_string(body),
        None => request.call(),
    };
    match result {
        Ok(response) => {
            if response.status() != 200 {
                let status = response.status();
                let text = read_limited(response, 1024).unwrap_or_default();
                return Err(FetchError::Http(status, text));
            }
            read_limited(response, limit)
        }
        Err(ureq::Error::Status(status, response)) => {
            if status == 429 {
                let seconds = response
                    .header("Retry-After")
                    .and_then(|value| value.trim().parse::<u64>().ok())
                    .unwrap_or(30);
                return Err(FetchError::RateLimited(seconds));
            }
            let body = read_limited(response, 1024).unwrap_or_default();
            Err(FetchError::Http(status, body))
        }
        Err(ureq::Error::Transport(transport)) => Err(FetchError::Network(transport.to_string())),
    }
}

fn lrclib_headers() -> [(&'static str, &'static str); 2] {
    [("User-Agent", USER_AGENT), ("Accept", "application/json")]
}

fn lrclib_search_once(
    track: Option<&str>,
    artist: Option<&str>,
    album: Option<&str>,
    query: Option<&str>,
) -> Result<Vec<LrclibRecord>, FetchError> {
    let mut url =
        Url::parse(LRCLIB_SEARCH).map_err(|error| FetchError::Invalid(error.to_string()))?;
    {
        let mut pairs = url.query_pairs_mut();
        if let Some(query) = query {
            pairs.append_pair("q", query);
        } else {
            let track = track.unwrap_or("").trim();
            if track.is_empty() {
                return Ok(Vec::new());
            }
            pairs.append_pair("track_name", track);
            if let Some(artist) = artist.map(str::trim).filter(|value| !value.is_empty()) {
                pairs.append_pair("artist_name", artist);
            }
            if let Some(album) = album.map(str::trim).filter(|value| !value.is_empty()) {
                pairs.append_pair("album_name", album);
            }
        }
    }
    let text = request_text(
        "GET",
        url.as_str(),
        &lrclib_headers(),
        None,
        MAX_LRCLIB_BODY,
        Duration::from_secs(20),
    )?;
    serde_json::from_str::<Vec<LrclibRecord>>(&text)
        .map_err(|error| FetchError::Invalid(format!("invalid LRCLIB response: {error}")))
}

fn lrclib_search(
    title: &str,
    artist: &str,
    album: &str,
    _duration: f64,
) -> Result<Vec<LrclibRecord>, FetchError> {
    let title = title.trim();
    if title.is_empty() {
        return Ok(Vec::new());
    }
    let artist = artist.trim();
    let artist = if artist.eq_ignore_ascii_case("unknown artist") {
        ""
    } else {
        artist
    };
    let album = album.trim();
    let mut records = lrclib_search_once(
        Some(title),
        if artist.is_empty() {
            None
        } else {
            Some(artist)
        },
        if album.is_empty() { None } else { Some(album) },
        None,
    )?;
    if records.is_empty() {
        let query = if artist.is_empty() {
            title.to_owned()
        } else {
            format!("{title} {artist}")
        };
        records = lrclib_search_once(None, None, None, Some(&query))?;
    }
    Ok(records)
}

// ---------------------------------------------------------------------------
// AI provider client
// ---------------------------------------------------------------------------

fn is_loopback(url: &Url) -> bool {
    match url.host_str() {
        Some("localhost" | "::1") => true,
        Some(host) if host.starts_with("127.") => true,
        _ => false,
    }
}

/// Mirrors Tool_box: strip `/chat/completions` and add `/v1` when the path has
/// no version segment. HTTP is only accepted for loopback endpoints.
pub fn normalize_base_url(input: &str) -> Result<String, String> {
    let trimmed = input.trim().trim_end_matches('/');
    if trimmed.is_empty() {
        return Err("接口地址不能为空".to_owned());
    }
    let mut value = trimmed.to_owned();
    if value.to_ascii_lowercase().ends_with("/chat/completions") {
        value.truncate(value.len() - "/chat/completions".len());
        value = value.trim_end_matches('/').to_owned();
    }
    let mut url = Url::parse(&value).map_err(|error| format!("接口地址无效：{error}"))?;
    if !matches!(url.scheme(), "https" | "http") {
        return Err("接口地址必须使用 HTTPS（本机地址可用 HTTP）".to_owned());
    }
    if url.scheme() == "http" && !is_loopback(&url) {
        return Err("远程接口必须使用 HTTPS；HTTP 只允许 localhost/127.0.0.1".to_owned());
    }
    url.set_query(None);
    url.set_fragment(None);
    let path = url.path().trim_end_matches('/').to_owned();
    let has_version = path.split('/').any(|segment| {
        let mut chars = segment.chars();
        matches!(chars.next(), Some('v' | 'V'))
            && chars.next().map(|ch| ch.is_ascii_digit()).unwrap_or(false)
    });
    if !has_version {
        url.set_path(&format!("{path}/v1"));
    }
    Ok(url.to_string().trim_end_matches('/').to_owned())
}

fn is_opencode_go(provider: &str, base_url: &str) -> bool {
    if provider == "opencode-go" {
        return true;
    }
    Url::parse(base_url)
        .map(|url| url.host_str() == Some("opencode.ai") && url.path().contains("/zen/go"))
        .unwrap_or(false)
}

fn provider_headers(config: &AiConfig, session: &str) -> Vec<(&'static str, String)> {
    let mut headers = vec![
        ("User-Agent", USER_AGENT.to_owned()),
        ("Accept", "application/json".to_owned()),
        ("Authorization", format!("Bearer {}", config.api_key)),
    ];
    if is_opencode_go(&config.provider, &config.base_url) {
        headers.push(("x-opencode-session", session.to_owned()));
    }
    headers
}

fn endpoint(base_url: &str, path: &str) -> String {
    format!(
        "{}/{}",
        base_url.trim_end_matches('/'),
        path.trim_start_matches('/')
    )
}

fn session_headers(config: &AiConfig, session: &str) -> Vec<(&'static str, String)> {
    provider_headers(config, session)
}

fn ai_request(
    config: &AiConfig,
    session: &str,
    path: &str,
    body: Option<&str>,
) -> Result<String, String> {
    let base = normalize_base_url(&config.base_url)?;
    if config.model.trim().is_empty() {
        return Err("请先选择或填写模型".to_owned());
    }
    let url = endpoint(&base, path);
    let headers = session_headers(config, session);
    let header_refs: Vec<(&str, &str)> = headers
        .iter()
        .map(|(name, value)| (*name, value.as_str()))
        .collect();
    let method = if body.is_some() { "POST" } else { "GET" };
    let mut all_headers = header_refs;
    if body.is_some() {
        all_headers.push(("Content-Type", "application/json"));
    }
    let started = Instant::now();
    let result = request_text(
        method,
        &url,
        &all_headers,
        body,
        MAX_AI_BODY,
        Duration::from_secs(90),
    )
    .map_err(|error| match error {
        FetchError::RateLimited(seconds) => {
            format!("AI 请求频率受限，请 {seconds} 秒后重试")
        }
        other => other.to_string(),
    })
    .map_err(|message| message.replace(&config.api_key, "***"));
    let elapsed = started.elapsed().as_millis();
    match &result {
        Ok(text) => crate::console::log(format!(
            "AI {method} {url} [{} / {}] ok: {} bytes in {elapsed} ms",
            config.provider,
            config.model,
            text.len()
        )),
        Err(error) => crate::console::log(format!(
            "AI {method} {url} [{} / {}] failed in {elapsed} ms: {error}",
            config.provider, config.model
        )),
    }
    result
}

pub fn fetch_models(config: &AiConfig, session: &str) -> Result<Vec<String>, String> {
    let text = ai_request(config, session, "models", None)?;
    let value: serde_json::Value =
        serde_json::from_str(&text).map_err(|error| format!("模型列表不是合法 JSON：{error}"))?;
    let mut ids = Vec::new();
    let array = value
        .as_array()
        .cloned()
        .or_else(|| {
            value
                .get("data")
                .and_then(|value| value.as_array())
                .cloned()
        })
        .or_else(|| {
            value
                .get("models")
                .and_then(|value| value.as_array())
                .cloned()
        })
        .unwrap_or_default();
    for item in array {
        let id = if let Some(text) = item.as_str() {
            Some(text.to_owned())
        } else {
            item.get("id")
                .or_else(|| item.get("name"))
                .or_else(|| item.get("model"))
                .and_then(|value| value.as_str())
                .map(str::to_owned)
        };
        if let Some(id) = id {
            let id = id.trim().trim_start_matches("models/").to_owned();
            if !id.is_empty() && !ids.contains(&id) {
                ids.push(id);
            }
        }
        if ids.len() >= 300 {
            break;
        }
    }
    ids.sort();
    Ok(ids)
}

fn chat_content(value: &serde_json::Value) -> Option<String> {
    let choice = value.get("choices")?.as_array()?.first()?;
    if let Some(text) = choice.get("text").and_then(|value| value.as_str()) {
        if !text.trim().is_empty() {
            return Some(text.to_owned());
        }
    }
    let content = choice.get("message")?.get("content")?;
    if let Some(text) = content.as_str() {
        return (!text.trim().is_empty()).then(|| text.to_owned());
    }
    if let Some(parts) = content.as_array() {
        let mut out = String::new();
        for part in parts {
            if let Some(text) = part.get("text").and_then(|value| value.as_str()) {
                out.push_str(text);
            } else if let Some(text) = part.as_str() {
                out.push_str(text);
            }
        }
        if !out.trim().is_empty() {
            return Some(out);
        }
    }
    None
}

fn complete(config: &AiConfig, session: &str, system: &str, user: &str) -> Result<String, String> {
    let body = serde_json::json!({
        "model": config.model,
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": user}
        ],
        "temperature": 0,
        "stream": false
    })
    .to_string();
    let text = ai_request(config, session, "chat/completions", Some(&body))
        .map_err(|error| error.replace(&config.api_key, "***"))?;
    let value: serde_json::Value =
        serde_json::from_str(&text).map_err(|error| format!("AI 响应不是合法 JSON：{error}"))?;
    if let Some(message) = value.get("error") {
        let message = message
            .get("message")
            .and_then(|value| value.as_str())
            .unwrap_or("AI 请求失败");
        return Err(message.to_owned());
    }
    chat_content(&value).ok_or_else(|| "AI 未返回内容".to_owned())
}

pub fn test_ai(config: &AiConfig, session: &str) -> Result<String, String> {
    // Listing models proves authentication but not generation. Always send a
    // tiny chat request too, otherwise a broken chat endpoint still reports a
    // successful connection.
    let models = fetch_models(config, session);
    let text = complete(
        config,
        session,
        "You are a connection test. Reply with exactly OK.",
        "OK",
    )?;
    let first_line = text.lines().next().unwrap_or("").trim();
    if first_line.is_empty() {
        return Err("AI 未返回内容".to_owned());
    }
    Ok(match models {
        Ok(models) => format!("连接成功 · {} 个模型 · {first_line}", models.len()),
        Err(_) => format!("连接成功 · {first_line}"),
    })
}

pub fn clean_ai_text(raw: &str) -> Option<String> {
    let mut text = raw.replace("\r\n", "\n").trim().to_owned();
    if text.starts_with("```") {
        text = text.lines().skip(1).collect::<Vec<_>>().join("\n");
        if let Some(index) = text.rfind("```") {
            text.truncate(index);
        }
    }
    let lower = text.to_lowercase();
    let short = text.lines().filter(|line| !line.trim().is_empty()).count() <= 3;
    if short
        && (lower.contains("not_found")
            || lower.contains("not found")
            || lower.contains("无法找到")
            || lower.contains("找不到")
            || lower.contains("cannot find")
            || lower.contains("unable to find"))
    {
        return None;
    }
    let mut lines: Vec<&str> = text.lines().collect();
    if let Some(first) = lines.first() {
        let first_lower = first.to_lowercase();
        if first.trim().ends_with(':')
            && (first_lower.contains("lyric") || first_lower.contains("歌词"))
        {
            lines.remove(0);
        }
    }
    let cleaned = lines
        .into_iter()
        .filter(|line| {
            let line = line.trim();
            !(line.starts_with('[') && line.ends_with(']') && !line.contains(':'))
        })
        .collect::<Vec<_>>()
        .join("\n");
    let cleaned = cleaned.trim().to_owned();
    (!cleaned.is_empty()).then_some(cleaned)
}

const AI_LYRICS_SYSTEM: &str = "You prepare a draft LRC file for a user who owns a local copy of the recording. \
Write the lyric lines you know from your own knowledge, each prefixed with an approximate [mm:ss.xx] timestamp. \
The user will review and edit the draft before saving, so approximate timing is acceptable; never return NOT_FOUND \
just because the exact timing is uncertain. Return only the LRC lines. Do not add a title, translation, notes or \
explanation. If you do not know this song at all, return exactly NOT_FOUND.";

fn looks_like_refusal(text: &str) -> bool {
    let lower = text.to_lowercase();
    let short = text.lines().filter(|line| !line.trim().is_empty()).count() <= 3;
    short
        && [
            "i can't provide",
            "i cannot provide",
            "i can't help with",
            "i cannot help with",
            "unable to provide",
            "can't provide lyrics",
            "cannot provide lyrics",
            "cannot reproduce",
            "can't reproduce",
            "copyright",
            "版权",
            "无法提供",
            "不能提供",
            "抱歉，我",
        ]
        .iter()
        .any(|marker| lower.contains(marker))
}

pub fn fetch_ai_lyrics(
    config: &AiConfig,
    session: &str,
    title: &str,
    artist: &str,
    album: &str,
    duration: f64,
) -> Result<Option<String>, String> {
    let minutes = if duration.is_finite() && duration > 0.0 {
        format!(
            "{:.0}:{:02}",
            (duration / 60.0).floor(),
            duration as u64 % 60
        )
    } else {
        "unknown".to_owned()
    };
    let user = format!(
        "Title: {title}\nArtist: {artist}\nAlbum: {album}\nDuration: {minutes}\n\n\
         The user owns a local copy. Return draft LRC lyrics, or NOT_FOUND."
    );
    let text = complete(config, session, AI_LYRICS_SYSTEM, &user)?;
    let Some(cleaned) = clean_ai_text(&text) else {
        return Ok(None);
    };
    if looks_like_refusal(&cleaned) {
        return Err("AI 供应商拒绝提供歌词（可能受版权限制）".to_owned());
    }
    Ok(Some(cleaned))
}

#[derive(Clone, Debug, PartialEq)]
pub struct LrcCheck {
    pub lines: usize,
    pub timed: bool,
    pub last_time: f64,
    pub warnings: Vec<String>,
}

pub fn inspect_lyrics(text: &str, duration: f64) -> LrcCheck {
    let parsed = crate::library::parse_lrc(text);
    let lines = parsed
        .iter()
        .filter(|(_, line)| !line.trim().is_empty())
        .count();
    let timed = parsed.iter().any(|(time, _)| *time > 0.0);
    let last_time = parsed.last().map(|(time, _)| *time).unwrap_or(0.0);
    let mut warnings = Vec::new();
    if lines < 4 {
        warnings.push("歌词行数很少".to_owned());
    }
    if !timed {
        warnings.push("没有时间轴，只能按纯文本显示".to_owned());
    }
    if duration > 1.0 && last_time > duration + 15.0 {
        warnings.push("时间轴超出歌曲时长".to_owned());
    }
    LrcCheck {
        lines,
        timed,
        last_time,
        warnings,
    }
}

// ---------------------------------------------------------------------------
// Worker thread
// ---------------------------------------------------------------------------

#[derive(Clone, Debug)]
pub enum FetchCommand {
    Focus(String),
    Lrclib {
        key: String,
        title: String,
        artist: String,
        album: String,
        duration: f64,
    },
    Ai {
        key: String,
        config: AiConfig,
        title: String,
        artist: String,
        album: String,
        duration: f64,
    },
    AiModels {
        config: AiConfig,
    },
    AiTest {
        config: AiConfig,
    },
}

#[derive(Clone, Debug)]
pub enum FetchEvent {
    Lrclib {
        key: String,
        result: Result<Vec<LrclibRecord>, String>,
    },
    AiLyrics {
        key: String,
        result: Result<Option<String>, String>,
    },
    AiModels {
        result: Result<Vec<String>, String>,
    },
    AiTest {
        result: Result<String, String>,
    },
    Skipped {
        key: String,
    },
}

pub struct LyricsFetcher {
    tx: Sender<FetchCommand>,
    rx: Receiver<FetchEvent>,
}

impl LyricsFetcher {
    pub fn new(ctx: egui::Context) -> Self {
        let (tx, command_rx) = channel();
        let (event_tx, rx) = channel();
        thread::spawn(move || worker(command_rx, event_tx, ctx));
        Self { tx, rx }
    }

    pub fn send(&self, command: FetchCommand) {
        let _ = self.tx.send(command);
    }

    pub fn try_recv(&self) -> Option<FetchEvent> {
        self.rx.try_recv().ok()
    }
}

fn worker(command_rx: Receiver<FetchCommand>, event_tx: Sender<FetchEvent>, ctx: egui::Context) {
    let session = format!(
        "lilt-{}-{}-{}",
        std::process::id(),
        now_secs(),
        Instant::now().elapsed().as_nanos()
    );
    let mut focus = String::new();
    let mut last_lrclib = Instant::now()
        .checked_sub(LRCLIB_MIN_INTERVAL)
        .unwrap_or_else(Instant::now);
    for command in command_rx {
        match command {
            FetchCommand::Focus(key) => {
                focus = key;
            }
            FetchCommand::Lrclib {
                key,
                title,
                artist,
                album,
                duration,
            } => {
                if key != focus {
                    let _ = event_tx.send(FetchEvent::Skipped { key });
                    ctx.request_repaint();
                    continue;
                }
                let wait = last_lrclib
                    .checked_add(LRCLIB_MIN_INTERVAL)
                    .unwrap_or_else(Instant::now)
                    .saturating_duration_since(Instant::now());
                if !wait.is_zero() {
                    thread::sleep(wait);
                }
                let mut result = lrclib_search(&title, &artist, &album, duration);
                last_lrclib = Instant::now();
                if let Err(FetchError::RateLimited(seconds)) = result {
                    thread::sleep(Duration::from_secs(seconds.clamp(1, 120)));
                    result = lrclib_search(&title, &artist, &album, duration);
                    last_lrclib = Instant::now();
                }
                let result = result.map_err(|error| error.to_string());
                let _ = event_tx.send(FetchEvent::Lrclib { key, result });
                ctx.request_repaint();
            }
            FetchCommand::Ai {
                key,
                config,
                title,
                artist,
                album,
                duration,
            } => {
                let result = fetch_ai_lyrics(&config, &session, &title, &artist, &album, duration);
                let _ = event_tx.send(FetchEvent::AiLyrics { key, result });
                ctx.request_repaint();
            }
            FetchCommand::AiModels { config } => {
                let result = fetch_models(&config, &session);
                let _ = event_tx.send(FetchEvent::AiModels { result });
                ctx.request_repaint();
            }
            FetchCommand::AiTest { config } => {
                let result = test_ai(&config, &session);
                let _ = event_tx.send(FetchEvent::AiTest { result });
                ctx.request_repaint();
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use std::net::TcpListener;

    fn record(
        title: &str,
        artist: &str,
        duration: f64,
        synced: Option<&str>,
        instrumental: bool,
    ) -> LrclibRecord {
        LrclibRecord {
            id: 1,
            track_name: title.to_owned(),
            artist_name: artist.to_owned(),
            album_name: "Album".to_owned(),
            duration,
            instrumental,
            has_word_sync: false,
            plain_lyrics: None,
            synced_lyrics: synced.map(str::to_owned),
        }
    }

    #[test]
    fn normalizes_titles_and_artists() {
        assert_eq!(
            normalize_match_text("Hotel California - Remastered 2011"),
            "hotel california"
        );
        assert_eq!(normalize_match_text("晴天 (Live)"), "晴天");
        assert!(artist_exact("Eagles; Eagles", "Eagles"));
        assert!(title_exact("Hotel California", "Hotel California (Live)"));
        assert!(!title_exact("Hello", "Hello Goodbye"));
    }

    #[test]
    fn auto_matches_exact_title_artist_and_duration() {
        let ranked = rank_candidates(
            vec![
                record(
                    "晴天",
                    "周杰伦",
                    270.0,
                    Some("[00:29.30] 故事的小黃花"),
                    false,
                ),
                record("晴天", "其他歌手", 270.0, Some("[00:29.30] x"), false),
            ],
            "晴天",
            "周杰伦",
            270.0,
        );
        match choose_match(ranked) {
            MatchOutcome::Best(candidate) => {
                assert_eq!(candidate.record.artist_name, "周杰伦");
                assert!(candidate.score >= 0.9);
            }
            other => panic!("expected best, got {other:?}"),
        }
    }

    #[test]
    fn live_versions_are_ambiguous_not_auto() {
        let ranked = rank_candidates(
            vec![record(
                "晴天",
                "周杰伦",
                299.0,
                Some("[00:29.36] 故事的小黃花"),
                false,
            )],
            "晴天",
            "周杰伦",
            270.0,
        );
        assert!(matches!(choose_match(ranked), MatchOutcome::Ambiguous(_)));
    }

    #[test]
    fn unknown_duration_can_still_auto_match() {
        let ranked = rank_candidates(
            vec![record(
                "晴天",
                "周杰伦",
                0.0,
                Some("[00:29.30] 故事的小黃花"),
                false,
            )],
            "晴天",
            "周杰伦",
            0.0,
        );
        assert!(matches!(choose_match(ranked), MatchOutcome::Best(_)));
    }

    #[test]
    fn instrumental_results_are_marked() {
        let ranked = rank_candidates(
            vec![record("晴天", "周杰伦", 270.0, None, true)],
            "晴天",
            "周杰伦",
            270.0,
        );
        assert!(matches!(choose_match(ranked), MatchOutcome::Instrumental));
    }

    #[test]
    fn cleans_markdown_fences_and_markers() {
        let raw = "```lrc\n[Verse 1]\n[00:01.00] Line one\n[00:02.00] Line two\n```";
        let cleaned = clean_ai_text(raw).unwrap();
        assert_eq!(cleaned, "[00:01.00] Line one\n[00:02.00] Line two");
        assert!(clean_ai_text("NOT_FOUND").is_none());
        assert!(clean_ai_text("找不到可靠歌词").is_none());
    }

    #[test]
    fn inspects_timed_and_plain_lyrics() {
        let timed = inspect_lyrics(
            "[00:01.00] a\n[00:02.00] b\n[00:03.00] c\n[00:04.00] d",
            10.0,
        );
        assert!(timed.timed);
        assert_eq!(timed.lines, 4);
        let plain = inspect_lyrics("a\nb\nc\nd", 10.0);
        assert!(!plain.timed);
        assert!(plain.warnings.iter().any(|w| w.contains("时间轴")));
    }

    #[test]
    fn normalizes_base_urls() {
        assert_eq!(
            normalize_base_url("https://api.deepseek.com").unwrap(),
            "https://api.deepseek.com/v1"
        );
        assert_eq!(
            normalize_base_url("https://api.deepseek.com/v1/chat/completions").unwrap(),
            "https://api.deepseek.com/v1"
        );
        assert_eq!(
            normalize_base_url("https://open.bigmodel.cn/api/paas/v4").unwrap(),
            "https://open.bigmodel.cn/api/paas/v4"
        );
        assert!(normalize_base_url("http://example.com/v1").is_err());
        assert!(normalize_base_url("http://127.0.0.1:8080/v1").is_ok());
    }

    #[test]
    fn opencode_go_requires_session_header() {
        let config = AiConfig {
            provider: "opencode-go".to_owned(),
            base_url: "https://opencode.ai/zen/go/v1".to_owned(),
            model: "glm-5.3".to_owned(),
            api_key: "secret".to_owned(),
        };
        let headers = provider_headers(&config, "session-1");
        assert!(headers
            .iter()
            .any(|(name, value)| *name == "x-opencode-session" && value == "session-1"));
        assert!(headers.iter().any(|(name, _)| *name == "User-Agent"));
    }

    #[test]
    fn cache_ttl_and_pruning() {
        let mut cache = FetchCache {
            path: PathBuf::from("unused"),
            entries: HashMap::new(),
        };
        let now = now_secs();
        cache.entries.insert(
            "a".to_owned(),
            FetchEntry {
                status: CachedStatus::NotFound,
                last_attempt: now,
                next_retry: now + 10,
                candidates: 0,
                error: None,
            },
        );
        assert!(cache.fresh("a", now).is_some());
        assert!(cache.fresh("a", now + 11).is_none());
        cache.prune(now + 100 * 24 * 60 * 60);
        assert!(cache.entries.is_empty());
    }

    #[test]
    #[ignore = "live network smoke test"]
    fn live_lrclib_smoke() {
        let records = lrclib_search("晴天", "周杰伦", "叶惠美", 270.0).unwrap();
        assert!(!records.is_empty());
        let ranked = rank_candidates(records, "晴天", "周杰伦", 270.0);
        assert!(matches!(choose_match(ranked), MatchOutcome::Best(_)));
    }

    fn spawn_http_server(body: String) -> (u16, Receiver<String>) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let (tx, rx) = channel();
        thread::spawn(move || {
            if let Ok((mut stream, _)) = listener.accept() {
                let mut data = Vec::new();
                let mut buffer = [0u8; 8192];
                loop {
                    let read = match stream.read(&mut buffer) {
                        Ok(0) | Err(_) => break,
                        Ok(read) => read,
                    };
                    data.extend_from_slice(&buffer[..read]);
                    let text = String::from_utf8_lossy(&data);
                    if let Some(header_end) = text.find("\r\n\r\n") {
                        let content_length = text[..header_end]
                            .lines()
                            .find_map(|line| {
                                line.strip_prefix("Content-Length: ")
                                    .or_else(|| line.strip_prefix("content-length: "))
                                    .and_then(|value| value.trim().parse::<usize>().ok())
                            })
                            .unwrap_or(0);
                        if data.len() >= header_end + 4 + content_length {
                            break;
                        }
                    }
                }
                let request = String::from_utf8_lossy(&data).to_string();
                let _ = tx.send(request);
                let response = format!(
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                    body.len(),
                    body
                );
                let _ = stream.write_all(response.as_bytes());
            }
        });
        (port, rx)
    }

    fn test_config(port: u16) -> AiConfig {
        AiConfig {
            provider: "custom".to_owned(),
            base_url: format!("http://127.0.0.1:{port}/v1"),
            model: "test-model".to_owned(),
            api_key: "sk-test".to_owned(),
        }
    }

    #[test]
    fn fetches_models_from_an_openai_compatible_endpoint() {
        let (port, request) = spawn_http_server(
            r#"{"data":[{"id":"model-b"},{"id":"model-a"},{"id":"model-a"}]}"#.to_owned(),
        );
        let models = fetch_models(&test_config(port), "session").unwrap();
        assert_eq!(models, vec!["model-a".to_owned(), "model-b".to_owned()]);
        let request = request.recv_timeout(Duration::from_secs(3)).unwrap();
        assert!(request.starts_with("GET /v1/models "), "{request}");
        assert!(
            request.contains("authorization: Bearer sk-test")
                || request.contains("Authorization: Bearer sk-test")
        );
    }

    #[test]
    fn fetches_ai_lyrics_from_an_openai_compatible_endpoint() {
        let (port, request) = spawn_http_server(
            r#"{"choices":[{"message":{"content":"```lrc\n[00:01.00] Line one\n[00:02.00] Line two\n```"}}]}"#
                .to_owned(),
        );
        let config = test_config(port);
        let text = fetch_ai_lyrics(
            &config,
            "session",
            "Test Song",
            "Test Artist",
            "Test Album",
            120.0,
        )
        .unwrap()
        .unwrap();
        assert!(text.contains("[00:01.00] Line one"));
        assert!(!text.contains("```"));
        let request = request.recv_timeout(Duration::from_secs(3)).unwrap();
        assert!(
            request.starts_with("POST /v1/chat/completions "),
            "{request}"
        );
        assert!(request.contains("Test Song"));
        assert!(request.contains("\"temperature\":0"), "{request}");
        assert!(request.to_lowercase().contains("draft lrc"), "{request}");
        assert!(
            !request.to_lowercase().contains("use web search"),
            "{request}"
        );
    }

    #[test]
    fn treats_a_short_refusal_as_an_error() {
        let (port, _request) = spawn_http_server(
            r#"{"choices":[{"message":{"content":"I'm sorry, I cannot provide the lyrics because of copyright."}}]}"#
                .to_owned(),
        );
        let error = fetch_ai_lyrics(
            &test_config(port),
            "session",
            "Test Song",
            "Test Artist",
            "Test Album",
            120.0,
        )
        .unwrap_err();
        assert!(error.contains("拒绝"), "{error}");
    }

    #[test]
    fn tolerates_null_lrclib_fields() {
        let raw = r#"[{"id":1,"trackName":null,"artistName":null,"albumName":null,"duration":null,"instrumental":null,"hasWordSync":null,"plainLyrics":null,"syncedLyrics":"[00:01.00] Line"}]"#;
        let records: Vec<LrclibRecord> = serde_json::from_str(raw).unwrap();
        assert_eq!(records.len(), 1);
        assert_eq!(records[0].duration, 0.0);
        assert_eq!(records[0].track_name, "");
        assert!(!records[0].instrumental);
    }

    #[test]
    fn opencode_go_sends_the_session_header() {
        let (port, request) = spawn_http_server(r#"{"data":[{"id":"m"}]}"#.to_owned());
        let mut config = test_config(port);
        config.provider = "opencode-go".to_owned();
        config.base_url = format!("http://127.0.0.1:{port}/v1");
        let _ = fetch_models(&config, "stable-session");
        let request = request.recv_timeout(Duration::from_secs(3)).unwrap();
        assert!(
            request.contains("x-opencode-session: stable-session")
                || request.contains("X-OpenCode-Session: stable-session"),
            "{request}"
        );
    }
}
