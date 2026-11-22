//! Developer console handling.
//!
//! Release builds are linked as Windows GUI applications, so double-clicking
//! `lilt.exe` does not open a terminal. Developer mode opens a separate console
//! window that tails a log file; closing that window cannot stop the player.

use std::fs::OpenOptions;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::Child;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;

static ENABLED: AtomicBool = AtomicBool::new(false);
static CHILD: Mutex<Option<Child>> = Mutex::new(None);

fn log_path() -> PathBuf {
    crate::library::data_dir().join("developer.log")
}

fn is_enabled() -> bool {
    ENABLED.load(Ordering::Relaxed)
}

/// Append one line to the developer log. No-op unless developer mode is on.
pub fn log(text: impl AsRef<str>) {
    if !is_enabled() {
        return;
    }
    let path = log_path();
    if let Some(parent) = path.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    if let Ok(mut file) = OpenOptions::new().create(true).append(true).open(&path) {
        let _ = writeln!(file, "{}", text.as_ref());
    }
}

fn stop_child() {
    if let Ok(mut guard) = CHILD.lock() {
        if let Some(mut child) = guard.take() {
            let _ = child.kill();
            let _ = child.wait();
        }
    }
}

fn write_banner(path: &Path) {
    if let Some(parent) = path.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    if let Ok(mut file) = std::fs::File::create(path) {
        let _ = writeln!(
            file,
            "Lilt {} developer console",
            crate::model::APP_VERSION
        );
        let _ = writeln!(
            file,
            "Data directory: {}",
            crate::library::data_dir().display()
        );
        let _ = writeln!(file, "Closing this window will not stop playback.");
        let _ = writeln!(file);
    }
}

#[cfg(target_os = "windows")]
fn spawn_console(log: &Path) -> Option<Child> {
    use std::os::windows::process::CommandExt;

    const CREATE_NEW_CONSOLE: u32 = 0x0000_0010;

    let script = "$Host.UI.RawUI.WindowTitle = 'Lilt developer console'; \
                  while (-not (Test-Path -LiteralPath $env:LILT_DEV_LOG)) { Start-Sleep -Milliseconds 200 }; \
                  Get-Content -LiteralPath $env:LILT_DEV_LOG -Wait -Encoding UTF8";

    std::process::Command::new("powershell.exe")
        .args(["-NoLogo", "-NoProfile", "-NoExit", "-Command", script])
        .env("LILT_DEV_LOG", log)
        .creation_flags(CREATE_NEW_CONSOLE)
        .spawn()
        .ok()
}

#[cfg(not(target_os = "windows"))]
fn spawn_console(_log: &Path) -> Option<Child> {
    None
}

/// Turn the developer console on or off. Safe to call repeatedly.
pub fn set_enabled(enabled: bool) {
    if enabled {
        stop_child();
        let path = log_path();
        write_banner(&path);
        ENABLED.store(true, Ordering::SeqCst);
        if let Ok(mut guard) = CHILD.lock() {
            *guard = spawn_console(&path);
        }
    } else {
        ENABLED.store(false, Ordering::SeqCst);
        stop_child();
    }
}

/// Stop the console tail process when the application exits.
pub fn shutdown() {
    ENABLED.store(false, Ordering::SeqCst);
    stop_child();
}

/// Attach to the terminal that launched a CLI-style invocation, so flags such
/// as `--list-audio` still print where the user can see them.
pub fn attach_parent_for_cli() {
    #[cfg(target_os = "windows")]
    unsafe {
        use std::ffi::c_void;

        type Bool = i32;
        type Dword = u32;

        const ATTACH_PARENT_PROCESS: Dword = 0xFFFF_FFFF;

        #[link(name = "kernel32")]
        extern "system" {
            fn AttachConsole(process_id: Dword) -> Bool;
            fn AllocConsole() -> Bool;
            fn GetConsoleWindow() -> *mut c_void;
        }

        if GetConsoleWindow().is_null() && AttachConsole(ATTACH_PARENT_PROCESS) == 0 {
            let _ = AllocConsole();
        }
    }
}
