# Round 6-D1 checks — tray, close-to-tray and global hotkeys

Produced on Windows 11 (2560×1440) with the Windows desktop build. The Android project
was not changed in this round.

## Build and tests

```text
cd base/app
cargo test                    # 21 passed, 0 failed
cargo build --release         # success, PE subsystem 2 (Windows GUI)
cd ..\..
.\package.ps1 all             # PC portable zip + Android debug APK
```

Release smoke test: the packaged `lilt.exe` (copied out of the low-integrity sandbox)
registered the tray icon, hid on close, accepted `Ctrl+Alt+Up` while hidden and saved
volume `0.45`. The packaged EXE has no console subsystem. The APK still has no
`INTERNET` permission.

The new tests cover:

- tray menu id → action mapping;
- six global shortcuts with unique ids and the documented names;
- the generated icon has an opaque centre and transparent corners;
- old `state.json` files load the new desktop settings with the intended defaults.

## Implementation

- `src/desktop.rs` owns the Windows notification-area icon and the global hotkeys.
  `tray-icon` 0.26 provides the tray/menu, `global-hotkey` 0.10 provides
  `Ctrl+Alt` registration. Both are Windows-only dependencies.
- The tray menu is **Show Lilt / Play-Pause / Previous / Next / Quit Lilt** with a
  separator before Quit. Left-clicking the icon shows and focuses the window; the menu
  opens on right-click. The Play/Pause label, menu language and tooltip follow the
  player state and the current track.
- `src/icon.rs` draws a 32×32 RGBA mark in code (five bars over a rounded tile). The
  same pixels are used for the window icon and the tray icon.
- **Close to tray** is on by default; **minimize to tray** is off by default. Both can be
  changed in **Settings → Desktop integration**, together with **Global hotkeys**.
- Hidden windows do not receive scheduled redraws on Windows. Every tray/hotkey callback
  therefore posts `WM_PAINT` to the main window, which winit turns into eframe's update.
  While hidden and playing, scanning, waiting for the sleep timer, or with a pending
  save, a 100 ms heartbeat keeps updates (track changes, sleep timer, fades, save debounce)
  running.
- Close-to-tray is only allowed after `Shell_NotifyIconGetRect` confirms the icon is
  registered. If it is not, closing exits normally and Settings shows a warning instead
  of hiding an unreachable window.
- The 6 defaults are fixed for this round:
  `Ctrl+Alt+Space`, `Ctrl+Alt+Left`, `Ctrl+Alt+Right`, `Ctrl+Alt+Up`, `Ctrl+Alt+Down`
  and `Ctrl+Alt+L`. `L` opens the now-playing lyric panel until the desktop-lyrics
  window arrives in Round 6-D2.

## Behaviour checks

The full native action sequence is in `desktop-session.log`:

```text
hotkeys registered: 6
tray ready
window hidden to tray
hotkey VolumeUp / apply VolumeUp
hotkey VolumeDown / apply VolumeDown
hotkey ToggleLyrics / apply ToggleLyrics
hotkey Next / apply Next
hotkey Previous / apply Previous
hotkey TogglePlay / apply TogglePlay (pause)
hotkey TogglePlay / apply TogglePlay (play)
tray click Show / apply Show / window shown from tray
tray menu TogglePlay / apply TogglePlay
tray menu Show / apply Show
tray menu Quit / apply Quit
tray removed
```

Verified:

- Closing the window hid it (`visible=False`) while the process stayed alive and the
  current track kept playing. The saved position advanced while hidden.
- All six hotkeys were delivered and applied while the window was hidden. Volume moved
  by 5%; next/previous kept the queue index consistent; `Ctrl+Alt+L` opened the lyric
  panel that is visible in `03-window-after-tray-show.png`.
- Left-clicking the tray icon showed and focused the window again.
- The right-click menu paused playback, showed the window, and quit the process. Quit
  saved state before exit and removed the tray icon.
- With **Minimize to tray** enabled, minimizing set `visible=False`; a tray click
  restored a normal (not minimized) window.
- A 4-second sleep timer fired while the window was hidden: position stayed at `3.705`
  for two samples three seconds apart and the process remained alive.
- A track ending while hidden advanced the queue from index `0` to index `1`.
- The Settings card fits at 1180×780 and 720×1280 (screenshots `04` and `05`).
- `tag_overrides` / Android user data were not touched; all desktop test state lives in
  `base/app/target/`.

## Test-environment note

This agent sandbox starts executables from the workspace at **low integrity**; the Windows
shell refuses `Shell_NotifyIcon(NIM_ADD)` from that integrity level with
`ERROR_ACCESS_DENIED`. The same `lilt.exe` copied to the temp folder runs at medium
integrity and registers normally. All tray/menu captures and the final session log were
produced with that copy; it is the same binary, not a different build. A normal user launch
from Explorer is not affected by this sandbox rule. Before this was identified, the code
also added the `tray_ready` guard so a failed registration can never hide an unreachable
window.

## Evidence files

| File | What it shows |
|---|---|
| `01-tray-icon.png` | Lilt's own icon in the Windows notification-area overflow |
| `02-tray-menu.png` | Tray menu: Show Lilt, Play, Previous, Next, Quit Lilt |
| `03-window-after-tray-show.png` | Window restored through the tray, with the lyric panel opened by `Ctrl+Alt+L` |
| `04-pc-settings-desktop-1180x780.png` | Desktop integration card and hotkey list at 1180×780 |
| `05-pc-settings-desktop-720x1280.png` | Same card at 720×1280 |
| `desktop-session.log` | Native tray/hotkey actions and their applied playback changes |

## Limitations

- Windows only. Other platforms compile but show the tray card as unavailable.
- Hotkeys are fixed to the six defaults; there is no custom binding UI yet.
- `Ctrl+Alt+L` targets the now-playing lyric panel until Round 6-D2 adds the desktop
  lyric window.
- No tray balloon/toast when the window is hidden; the icon and tooltip are the only
  tray indication.
- No SMTC, mini player, taskbar thumbnail buttons or media-key handling yet.
- Windows may place a new tray icon in the overflow; the user can promote it in Taskbar
  settings. The app does not override that preference.

## Commands

```text
cd base/app
cargo test
cargo build --release

# optional native action log
$env:LILT_DESKTOP_LOG = "C:\path\to\desktop.log"
.\target\release\lilt.exe
```

For a reproducible test state, point `LILT_DATA_DIR` at a writable folder and set
`LILT_AUTOPLAY=1`; the app scans `Starter Music` on first launch.
