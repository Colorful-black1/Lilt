# Round 6-D2 checks — desktop lyrics, mini player and SMTC

Produced on Windows 11 (2560×1440) with the Windows desktop build. The Android project
was not changed in this round.

## Build and tests

```text
cd base/app
cargo test                    # 23 passed, 0 failed
cargo build --release         # success

cd ..\..
.\package.ps1 all             # PC portable zip + Android debug APK
```

The new desktop checks cover:

- desktop lyric line selection with a per-song offset and blank-line skipping;
- the seek-time conversion used when a lyric line is clicked;
- tray menu id mappings for the new desktop lyric, lock and mini player items;
- SMTC timeline conversion from seconds to 100 ns ticks;
- six unique global shortcut ids and the documented key names.

## Implementation

- Desktop lyrics and the mini player are deferred egui viewports
  (`src/ui/companion.rs`). The main window writes a small playback snapshot; the
  companion windows enqueue actions such as play/pause, previous, next, seek, shuffle,
  repeat, lock, close and open-main. Every playback change still runs on the main UI
  thread.
- The lyric overlay uses the same per-song offset, font size and opacity settings as the
  now-playing panel. It shows previous/current/next lines, a title/artist strip and a
  progress line. The displayed line count shrinks automatically when the window is small
  or the font is large.
- Desktop lyrics and the mini player use a native rounded window region so the outer
  margin and corners do not show as a black rectangle. The lyric window also uses a
  layered-window alpha (246/255, about 96% opaque), which keeps it readable while
  remaining visibly translucent. Applying the native region and layer is repeated when
  the window is recreated or resized.
- The lock button removes the header, disables dragging and makes the window
  click-through with `WS_EX_TRANSPARENT`. The tray menu and Settings both provide
  unlock. `Ctrl+Alt+L` continues to toggle the window, including while the main window is
  hidden in the tray.
- The mini player is always-on-top and has a draggable header, expand/close buttons,
  cover art, title/artist/album, previous/play/next, shuffle/repeat, elapsed time and a
  seek bar. The cover is the same cached texture as the main player when available.
- `src/desktop.rs` now owns the Windows SMTC integration as well. It uses
  `SystemMediaTransportControls` on the main window handle with `windows` 0.62, publishes
  title/artist/album, playback status, timeline, shuffle and repeat mode, and maps the
  flyout's play/pause/previous/next buttons onto the same `DesktopAction` queue as the
  tray and hotkeys.
- Settings gained desktop lyric, lock, desktop lyric size (18–56 px) and mini player
  controls. The main player bar has a mini-player button; the now-playing lyric header has
  a Desktop button; the tray menu has desktop lyric, lock/unlock and mini-player entries.

## Behaviour checks

Native action sequence (`desktop-session.log`):

```text
SMTC enabled
companion ToggleLyricsLock / desktop lyrics locked
tray menu ToggleLyricsLock / apply / desktop lyrics unlocked
SMTC button Play / apply Play
SMTC button Next / apply Next
companion TogglePlay
companion Next
companion Previous
hotkey ToggleDesktopLyrics / desktop lyrics hidden
hotkey ToggleDesktopLyrics / desktop lyrics shown
window hidden to tray
tray click Show / apply Show / window shown from tray
```

Verified:

- Desktop lyric window starts at 780×176, bottom-centre. The screenshot at
  `01-desktop-lyrics.png` shows the active line plus previous/next lines; the
  translucent panel and rounded region are visible over the browser behind it.
- Clicking the lock button changes the window style from `ex=0xC0118` to
  `ex=0xC0138` (`WS_EX_TRANSPARENT`) and keeps `WS_EX_LAYERED`. `WindowFromPoint` at the
  centre of the locked overlay returns the Chrome window below it, not Lilt
  (`lock-clickthrough.txt`).
- Unlocking from the tray menu restores `passthrough=False` and the header controls.
- Dragging the lyric header by `+100, -82` moved the window from `890,1180` to
  `990,1098`; hiding and showing it again returned it to the same dragged position.
- Mini player play/pause, previous and next all applied; its state follows the main
  player. Closing it sets `mini_player=false`. The window region removes the rectangular
  margin and rounds the corners (`03-mini-player.png`).
- With the main window hidden to the tray, both lyric and mini windows stayed visible and
  kept working; `Ctrl+Alt+L` hid and re-created the lyric window while the main window was
  hidden.
- SMTC session readback: `Glass Horizon / Lumen Field / Aurora Drift`, `Paused`,
  position `12.01`, end `48`, play/pause and next/previous enabled
  (`smtc-session.txt`). Calling `TryTogglePlayPauseAsync` changed the status to Playing;
  `TrySkipNextAsync` advanced to `Soft Static` (`smtc-control.txt`).
- Release smoke test: the packaged `lilt.exe` registered the layered lyric window and mini
  player, and SMTC reported `Glass Horizon` at `12.015 / 48`. The packaged EXE has PE
  subsystem 2 (no console). The APK still has no `INTERNET` permission.
- Settings card layout was checked at 1180×780 and 720×1280. The card is scrollable at
  both sizes (`05` and `06`).
- Desktop test state is under `base/app/target/`; no Android Room data or library files
  were changed.

## Test-environment note

As with Round 6-D1, this agent sandbox starts workspace executables at low integrity and
the Windows shell rejects tray `NIM_ADD` with `ERROR_ACCESS_DENIED`. The same
`lilt.exe` was copied to the temp folder to run at normal integrity for all tray and
native captures. This is the same binary; a normal Explorer launch is not affected.

## Evidence files

| File | What it shows |
|---|---|
| `01-desktop-lyrics.png` | Translucent rounded desktop lyric window; active line and neighbours |
| `02-desktop-lyrics-locked.png` | Locked overlay without controls, with click-through |
| `03-mini-player.png` | Mini player with cover, transport, shuffle/repeat and seek |
| `04-tray-menu.png` | Tray menu additions: desktop lyrics, lock/unlock, mini player |
| `05-pc-settings-desktop-1180x780.png` | Desktop integration controls at 1180×780 |
| `06-pc-settings-desktop-720x1280.png` | Full desktop/language card at 720×1280 |
| `desktop-session.log` | Native action sequence from the final run |
| `lock-clickthrough.txt` | Layered/transparent styles and hit-test target under the locked window |
| `smtc-session.txt` | Windows SMTC metadata, status and timeline readback |
| `smtc-control.txt` | SMTC play/pause and next results |

## Limitations

- The lyric window uses a uniform layered alpha, not per-pixel alpha. It is translucent
  (about 96% opaque) and rounded, but the desktop is faintly visible through the whole
  panel rather than only outside the card.
- SMTC does not publish album-art thumbnails yet. Metadata and control state are synced.
- Locked lyrics can only be unlocked from the tray menu or Settings, not by clicking the
  click-through window itself.
- The mini player is Windows-targeted like the tray/SMTC work. Other platforms still
  compile the same companion code but do not get native SMTC.
- Android floating/lock-screen lyrics and the Android home-screen widget remain for a
  later round.

## Commands

```text
cd base/app
cargo test
cargo build --release

# native action / SMTC session evidence
$env:LILT_DESKTOP_LOG = "C:\path\to\desktop.log"
$env:LILT_DESKTOP_LYRICS = "1"
$env:LILT_MINI_PLAYER = "1"
.\target\release\lilt.exe
```

For a stable lyric screenshot, point `LILT_DATA_DIR` at a test state and use
`LILT_SEEK=12`, `LILT_PAUSE_AFTER_SEEK=1`; the test state can include a timed lyric
override without modifying the bundled `Starter Music` files.
