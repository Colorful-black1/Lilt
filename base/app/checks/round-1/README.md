# Round 1 checks

## Build and tests

- `cargo build` — success.
- `cargo test` — 8 passed, 0 failed. Tests cover duration formatting, EQ presets, initials, LRC timestamps, M3U path/order parsing, filename track prefixes, queue de-duplication and the 20-queue cap.

## Screenshot command

The program was run with a private `LILT_DATA_DIR` so the workspace and user profile were
not touched:

```
lilt.exe --screenshot shots/final_folders.png
lilt.exe --screenshot shots/final_narrow.png --size 720x1280
lilt.exe --screenshot shots/final_tall.png --size 1080x1920
```

`--screenshot` opens inactive, stays behind other windows on Windows, draws 120 frames,
writes the PNG and exits. Confirmed on this machine.

## Evidence files

| File | What it shows |
|---|---|
| `01-folders-1180x780.png` | cold-start library, folder tree cards, breadcrumb, real scanned tracks, player bar |
| `02-songs-1180x780.png` | full song list, sort control, alphabet index, time/album columns, more menus, queue badge |
| `03-now-playing.png` | full-screen player, cover, seek, transport, queue count 10, up-next list, file metadata |
| `04-equalizer.png` | ten live faders, response curve, presets, preamp/bass/surround, four device profiles |
| `05-settings.png` | theme, accent, language, roots, scan rules, playback settings |
| `06-album.png` | album detail with scanned tracks and batch action buttons |
| `07-playlists.png` | playlist cards, created from scanned songs |
| `08-playlist-detail.png` | playlist detail, play/export/rename/delete, ordered songs |
| `09-tag-editor.png` | batch tag editor with cover chooser |
| `10-sleep-timer.png` | sleep timer fired: paused, toast shown, sleep state cleared |
| `11-gapless-transition.png` | auto-advance after seeking to 54.4 s of a 55 s track: next track playing at 0:02 with no rebuild click |
| `12-dark-chinese.png` | black/dark theme + Simplified Chinese UI |
| `13-narrow-720x1280.png` | narrow layout (compact nav rail, no player/list overlap) |
| `14-narrow-equalizer-720x1280.png` | ten faders and three effect rows fit 720 px |
| `15-narrow-settings-720x1280.png` | settings fit 720 px |
| `16-tall-1080x1920.png` | tall layout, all rows above the player bar |

The starter library used in the screenshots is ten real WAV files (bundled or generated)
that were scanned normally. The two playlists in shots 07/08 are test user data added to
the test state; their songs are real scanned songs, not fabricated rows.

The final Craft Anything check (`checks/round-0/screenshot.png`) was also run after the
fallback was added; it shows the same 10-track starter library, so the check is not
capturing a pre-scan empty state.

## Verified in this round

- Real scan → folder browsing → playback with working audio output.
- Gapless preload: seeking near the end of track 1 advanced to track 2 automatically and
  the progress counter continued.
- Sleep timer: zero-second test paused playback and showed the stop message.
- M3U order/round-trip path parsing unit test.
- Layouts at 1180×780, 720×1280 and 1080×1920 have no player-bar/list overlap or clipped
  primary controls.
- Node/machinery sheet present in `mechanics.json`, with sources for target-derived values.

## Not verified or not built

- Android notification, lock-screen, headset-unplug, media-key, widget and tile surfaces.
- Continuous audio playback for the full length of every codec (MP3/FLAC/Vorbis/WAV/AAC
  are decoded by Symphonia; Opus files are scanned but the bundled decoder set does not
  include an Opus decoder).
- Actual pointer drag reordering in playlists (row menu provides move up/down).
- Independent pitch shifting and ReplayGain calculation/writing.
