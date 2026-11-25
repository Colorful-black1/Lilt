# PC playback-mode and window restore

This round saves the desktop player's shuffle/repeat choice and the companion
window geometry alongside the existing queue, song and position state.

## Saved state

- `Settings.shuffle` and `Settings.repeat` are written whenever the user toggles
  the controls, including the three "Shuffle" buttons that start a context.
- `Settings.main_window_position`, `main_window_size` and
  `main_window_maximized` are updated from the live viewport.
- `Settings.desktop_lyrics_position` / `desktop_lyrics_size` and
  `mini_player_position` / `mini_player_size` are updated from the companion
  windows.
- Existing queue, current index, position, volume and speed persistence is
  unchanged.
- Sleep timer, A-B loop, stop-after, selected page, search and dialogs remain
  one-shot.

## Restore behaviour

- `LiltApp::new` initialises shuffle/repeat from settings and seeds the
  companion-window memory with the saved geometry.
- The first frame applies the saved main-window size/position and maximized
  state. A screenshot run keeps its requested geometry.
- The previous queue/song/position is available for playback as before; this
  round does not autoplay on launch.
- Backup restore reapplies shuffle/repeat to the running app.

## Verification

- `cargo test`: **73 passed, 0 failed, 1 ignored**. The new model test covers
  old state files (defaults) and a full geometry/mode round trip; the backup
  test includes shuffle/repeat.
- Windows release build and portable package complete successfully.

```text
dist/Lilt-0.2.2-win64/Lilt.exe        12,055,552 bytes  (PE subsystem 2)
dist/Lilt-0.2.2-win64-portable.zip    18,515,751 bytes
```
