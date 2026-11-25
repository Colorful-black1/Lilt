# Android playback-session restore and mode memory

This round adds the Android equivalent of the desktop's "pick up where I left
off" behaviour and keeps shuffle/repeat across restarts.

## What is saved

- Shuffle and repeat mode are global settings in `lilt_settings`:
  `shuffle` and `repeat_mode` (`off`, `all`, `one`). They are included in the
  manual backup and restored through the normal settings path.
- The last playback session is stored in
  `files/playback_session.json`:
  - queue media ids in playback order
  - current queue index
  - current position in milliseconds
  - the active named-queue id, when there was one
  - update timestamp and schema version
- The session file is **not** part of the manual backup. Settings and user data
  are backed up; the transient playback session stays on the device.

## Save and restore

- `PlaybackSessionStore` writes through a temporary file and rename, and resets
  an empty queue.
- `PlaybackService` saves every five seconds while playing, on media-item
  transitions, pause, seek/timeline changes, `onTaskRemoved()` and
  `onDestroy()`. The task-removal and destroy paths use a synchronous save.
- On service creation, the persisted queue is resolved through
  `MusicRepository.songsWithTags()` so virtual tag edits (artist/album/title)
  are used, not only the raw scan rows.
- The queue is installed in ExoPlayer with the saved index and position,
  prepared and left **paused**. Media3 then publishes the item to the app,
  mini player and notification.
- Missing tracks are skipped; if none remain, the session is cleared.
- The app does not restore the last page, tab, folder, sort order, search or
  dialog. A cold start always opens the home screen.
- The sleep timer, A-B loop and "stop after this song" remain one-shot controls
  and are deliberately not restored.

## Evidence

| File | What it shows |
|---|---|
| `01-home-mini-player-restored.png` | After force-stop/reopen: home with the saved "Hello" mini player, paused |
| `02-restored-player.png` | Pressing play resumes at 1:00 with shuffle and repeat still highlighted |
| `03-force-stop-while-playing.png` | Process killed while playing, reopened at the last five-second snapshot in the paused mini player |

## Verification

- Emulator: `lilt35`, Android 15 (API 35), 1080 × 2400.
- Set shuffle on and repeat to all, played "Hello" to 0:54, paused, then
  force-stopped the package. Reopening showed the mini player paused at the
  saved position; the player screen showed 1:00 after play resumed.
- Repeated with the process killed while playing. The saved snapshot was 1:19;
  reopening showed the same song, paused, with the mini player and no autoplay.
- The restored item kept the edited artist "Adele" instead of the raw
  "Unknown artist" row, confirming tag overrides are applied.
- `adb install -r` from 0.2.1 to 0.2.2 kept the 38-song library and 2
  favourites.
- Android unit tests: **65 passed, 0 failed** (including session JSON
  round-trip, stale index/position clamping and backup shuffle/repeat fields).

```text
base/app/Lilt-0.2.2-debug.apk    22,148,381 bytes
```

The session file is intentionally excluded from the manual backup JSON and
from the app's page/navigation state.
