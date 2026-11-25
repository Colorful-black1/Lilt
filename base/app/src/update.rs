//! Manual update check against the project's GitHub releases.
//!
//! Only public release metadata is requested. The download is written to the
//! user's Downloads folder; the running executable is never replaced.

use serde::Deserialize;
use std::fs::{self, File};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::mpsc::Sender;
use std::time::{Duration, Instant};

const REPO: &str = "Colorful-black1/Lilt";
const API_URL: &str = "https://api.github.com/repos/Colorful-black1/Lilt/releases/latest";
pub const RELEASES_URL: &str = "https://github.com/Colorful-black1/Lilt/releases";

#[derive(Clone, Debug)]
pub enum UpdateStatus {
    Idle,
    Checking,
    UpToDate {
        latest: String,
    },
    Available {
        version: String,
        release_url: String,
        notes: String,
    },
    Downloading {
        version: String,
        received: u64,
        total: Option<u64>,
    },
    Downloaded {
        version: String,
        path: PathBuf,
        release_url: String,
    },
    NoRelease,
    Error(String),
}

impl UpdateStatus {
    pub fn active(&self) -> bool {
        matches!(
            self,
            UpdateStatus::Checking | UpdateStatus::Downloading { .. }
        )
    }

    pub fn done(&self) -> bool {
        matches!(
            self,
            UpdateStatus::UpToDate { .. }
                | UpdateStatus::Available { .. }
                | UpdateStatus::Downloaded { .. }
                | UpdateStatus::NoRelease
                | UpdateStatus::Error(_)
        )
    }
}

#[derive(Debug, Deserialize)]
struct Release {
    tag_name: String,
    html_url: String,
    #[serde(default)]
    body: String,
    #[serde(default)]
    assets: Vec<Asset>,
}

#[derive(Debug, Deserialize)]
struct Asset {
    name: String,
    browser_download_url: String,
}

pub fn start_check(tx: Sender<UpdateStatus>) {
    let _ = tx.send(UpdateStatus::Checking);
    let result = check_and_download(&tx);
    if let Err(message) = result {
        let _ = tx.send(UpdateStatus::Error(message));
    }
}

fn check_and_download(tx: &Sender<UpdateStatus>) -> Result<(), String> {
    let agent = http_agent()?;
    let response = match agent
        .get(API_URL)
        .set("Accept", "application/vnd.github+json")
        .set("X-GitHub-Api-Version", "2022-11-28")
        .timeout(Duration::from_secs(15))
        .call()
    {
        Ok(response) => response,
        Err(ureq::Error::Status(404, _)) => {
            let _ = tx.send(UpdateStatus::NoRelease);
            return Ok(());
        }
        Err(ureq::Error::Status(status, _)) => {
            return Err(format!("GitHub returned HTTP {status}."));
        }
        Err(ureq::Error::Transport(transport)) => {
            return Err(format!("Could not reach GitHub: {transport}"));
        }
    };

    let mut body = String::new();
    response
        .into_reader()
        .take(1024 * 1024)
        .read_to_string(&mut body)
        .map_err(|error| format!("Could not read the release response: {error}"))?;
    let release: Release = serde_json::from_str(&body)
        .map_err(|error| format!("Could not parse the release response: {error}"))?;

    let latest = release
        .tag_name
        .trim()
        .trim_start_matches(['v', 'V'])
        .to_owned();
    let current = env!("CARGO_PKG_VERSION");
    if !version_is_newer(&latest, current) {
        let _ = tx.send(UpdateStatus::UpToDate {
            latest: latest.clone(),
        });
        return Ok(());
    }

    let asset = select_windows_asset(&release.assets);
    let Some(asset) = asset else {
        let _ = tx.send(UpdateStatus::Available {
            version: latest,
            release_url: release.html_url,
            notes: release.body,
        });
        return Ok(());
    };

    let dir = download_dir()?;
    fs::create_dir_all(&dir)
        .map_err(|error| format!("Could not create the download folder: {error}"))?;
    let file_name = asset
        .name
        .rsplit(['/', '\\'])
        .next()
        .filter(|name| !name.is_empty())
        .unwrap_or("Lilt-update.zip");
    let final_path = dir.join(file_name);
    let part_path = final_path.with_extension("part");
    let _ = fs::remove_file(&part_path);

    let response = agent
        .get(&asset.browser_download_url)
        .set("Accept", "application/octet-stream")
        .timeout(Duration::from_secs(90))
        .call()
        .map_err(|error| format!("Could not download the update: {error}"))?;
    let content_length = response
        .header("Content-Length")
        .and_then(|value| value.trim().parse::<u64>().ok());
    let mut reader = response.into_reader();
    let mut file = File::create(&part_path).map_err(|error| {
        format!(
            "Could not create the download file {}: {error}",
            part_path.display()
        )
    })?;
    let mut buffer = [0u8; 64 * 1024];
    let mut received = 0u64;
    let mut last_sent = Instant::now();
    loop {
        let read = reader
            .read(&mut buffer)
            .map_err(|error| format!("Could not read the download: {error}"))?;
        if read == 0 {
            break;
        }
        file.write_all(&buffer[..read])
            .map_err(|error| format!("Could not write the download: {error}"))?;
        received += read as u64;
        if last_sent.elapsed() >= Duration::from_millis(150) {
            let _ = tx.send(UpdateStatus::Downloading {
                version: latest.clone(),
                received,
                total: content_length,
            });
            last_sent = Instant::now();
        }
    }
    file.flush()
        .map_err(|error| format!("Could not flush the download: {error}"))?;
    drop(file);
    if final_path.exists() {
        let _ = fs::remove_file(&final_path);
    }
    fs::rename(&part_path, &final_path)
        .map_err(|error| format!("Could not finish the download: {error}"))?;
    let _ = tx.send(UpdateStatus::Downloaded {
        version: latest,
        path: final_path,
        release_url: release.html_url,
    });
    Ok(())
}

fn http_agent() -> Result<ureq::Agent, String> {
    let tls = ureq::native_tls::TlsConnector::new()
        .map_err(|error| format!("Could not initialize system TLS: {error}"))?;
    Ok(ureq::AgentBuilder::new()
        .tls_connector(std::sync::Arc::new(tls))
        .timeout_connect(Duration::from_secs(10))
        .timeout_read(Duration::from_secs(90))
        .user_agent(&format!(
            "Lilt/{} (+https://github.com/{REPO})",
            env!("CARGO_PKG_VERSION")
        ))
        .build())
}

fn select_windows_asset(assets: &[Asset]) -> Option<&Asset> {
    assets
        .iter()
        .find(|asset| asset.name.to_ascii_lowercase().ends_with(".zip"))
        .or_else(|| {
            assets
                .iter()
                .find(|asset| asset.name.to_ascii_lowercase().contains("portable"))
        })
}

fn download_dir() -> Result<PathBuf, String> {
    let mut candidates = Vec::new();
    if let Some(home) = std::env::var_os("USERPROFILE").or_else(|| std::env::var_os("HOME")) {
        candidates.push(PathBuf::from(home).join("Downloads").join("Lilt Updates"));
    }
    candidates.push(crate::library::data_dir().join("updates"));
    candidates.push(std::env::temp_dir().join("Lilt Updates"));
    for candidate in candidates {
        if fs::create_dir_all(&candidate).is_err() {
            continue;
        }
        let probe = candidate.join(".lilt-write-test");
        let writable = File::create(&probe).is_ok();
        let _ = fs::remove_file(&probe);
        if writable {
            return Ok(candidate);
        }
    }
    Err("No writable folder was found for the update download.".to_owned())
}

pub fn open_folder(path: &Path) {
    let target = path.parent().unwrap_or(path);
    open_path(target);
}

pub fn open_url(url: &str) {
    open_path(Path::new(url));
}

fn open_path(target: &Path) {
    #[cfg(target_os = "windows")]
    {
        let _ = std::process::Command::new("cmd")
            .args(["/C", "start", ""])
            .arg(target)
            .spawn();
    }
    #[cfg(target_os = "macos")]
    {
        let _ = std::process::Command::new("open").arg(target).spawn();
    }
    #[cfg(all(unix, not(target_os = "macos")))]
    {
        let _ = std::process::Command::new("xdg-open").arg(target).spawn();
    }
}

pub fn version_is_newer(latest: &str, current: &str) -> bool {
    let Some(latest) = version_parts(latest) else {
        return false;
    };
    let Some(current) = version_parts(current) else {
        return false;
    };
    let length = latest.len().max(current.len());
    for index in 0..length {
        let a = latest.get(index).copied().unwrap_or(0);
        let b = current.get(index).copied().unwrap_or(0);
        if a != b {
            return a > b;
        }
    }
    false
}

fn version_parts(value: &str) -> Option<Vec<u64>> {
    let value = value.trim().trim_start_matches(['v', 'V']);
    let core = value.split(['-', '+']).next()?.trim();
    if core.is_empty() {
        return None;
    }
    let parts: Vec<u64> = core
        .split('.')
        .map(|part| part.parse::<u64>().ok())
        .collect::<Option<Vec<_>>>()?;
    Some(parts)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn compares_numeric_versions() {
        assert!(version_is_newer("0.2.0", "0.1.0"));
        assert!(version_is_newer("v0.2.1", "0.2.0"));
        assert!(!version_is_newer("0.2.0", "0.2.0"));
        assert!(!version_is_newer("0.1.9", "0.2.0"));
        assert!(version_is_newer("1.0", "0.99.99"));
        assert!(!version_is_newer("not-a-version", "0.1.0"));
    }

    #[test]
    fn strips_release_prefixes() {
        assert_eq!(version_parts("v1.2.3"), Some(vec![1, 2, 3]));
        assert_eq!(version_parts("1.2.3-beta"), Some(vec![1, 2, 3]));
    }
}
