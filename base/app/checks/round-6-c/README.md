# Round 6-C checks — lyric offset, font size and opacity

Produced on the Android 15 (API 35) emulator `lilt35` and on the Windows desktop
build. Android captures use 1080×1920 at density 420 and 720×1280 at density 320.
Desktop captures are 1180×780 and 720×1280.

## Build and tests

```text
cd base/app
cargo test                             # 17 passed, 0 failed
cargo build --release                  # success

cd base/app/android
./gradlew testDebugUnitTest            # 25 tests, 0 failures
./gradlew connectedDebugAndroidTest    # 6 migration tests, 0 failures
./gradlew assembleDebug                # BUILD SUCCESSFUL
```

The new tests cover:

- PC offset helpers: positive delays, negative advances, reset, ±60 s clamp and
  non-negative seek/active positions.
- Android `activeLrcLine(document, positionMs, offsetMs)` for positive and negative
  offsets, `lyricsPositionMs`, `lyricsSeekMs`, the clamp and `mm:ss.d` preview formatting.
- Android `migrate6To7AddsLyricsOffsetAndKeepsStoredLyrics`: a v6 database with lyrics and
  a tag override migrates to v7, keeps the lyrics text, defaults `offsetMs` to 0 and then
  accepts an updated offset.

## Implementation

### Desktop

- `SongExtra.lyrics_offset_ms` stores the per-song offset in `state.json`; old files load
  with 0 because `SongExtra` uses serde defaults.
- The now-playing lyric panel has `-500 / -100 / +100 / +500` and **Reset** controls.
  Positive values delay the lyrics: line `t` lights up at `t + offset`.
- Clicking a lyric line seeks to `line + offset`, clamped at zero.
- Settings has **Lyrics → Font size** (10–24 px) and **Opacity** (30–100%) sliders with a
  live preview. Defaults are 13 px and 100%.
- Active lines are 1.5 px larger and use the accent colour; opacity scales both active and
  inactive lyric text.

### Android

- Room v7 adds `lyrics.offsetMs` (`INTEGER NOT NULL DEFAULT 0`); `MIGRATION_6_7` keeps
  existing lyric text.
- `saveLyrics` stores the text and offset together; `setLyricsOffset` updates only the
  offset for the playing song. Both clamp to ±60,000 ms.
- The player lyric panel and the full-screen editor share `LyricsOffsetControls`.
  The editor also previews the adjusted timestamps with tenths (`0:02.5`) and shows a
  font/opacity preview.
- `activeLrcLine` uses `position - offset`; scrolling follows the same adjusted line.
- Settings stores `lyrics_font_size` (12–24 sp, half-point steps) and `lyrics_opacity`
  (0.35–1.0) in `lilt_settings` and exposes sliders with a preview.

## Behaviour checks

### Desktop

- A seeded timed LRC for `Glass Horizon` was shown with `+500 ms`, 20 px and 70% opacity.
  At `0:10` the highlighted line was the `0:06` line, proving the positive delay.
- Clicking `+500` in the running app changed the panel to `+1000 ms` and kept the earlier
  line highlighted (`10-...png`). The Reset control appears whenever the offset is non-zero.
- After the click the writable test state file contained `lyrics_offset_ms = 1000`, even
  while playback was paused. A delayed repaint now keeps the save debounce running after a
  settings or lyric edit, so the value does not wait for another input event.
- The Settings card showed 20 px / 70% and both preview lines; changing the sliders updates
  the preview immediately.
- 720-wide captures show the offset chips and settings sliders without horizontal clipping.

Method note: the desktop window was captured with `PrintWindow` while the app was running,
because the inactive `--screenshot` mode does not repaint in this remote Windows session.

### Android

- Seeded timed lyrics for `Glass Horizon` (`mediaId 1000000017`).
- Player panel: `+100` wrote `1000000017|100`; two `+500` taps wrote `1100`.
- Editor: Reset then Save wrote `offsetMs = 0`; `+500` then Save wrote `500`.
- At `0:20` with `+500`, the highlighted line was the `0:15` line. At `+1000` (PC check)
  the same rule moved the active line to `0:06` at `0:10`.
- Settings stored `lyrics_font_size = 23.0` and `lyrics_opacity = 0.65`; the in-app preview
  and the player panel both changed immediately.
- The editor preview showed adjusted timestamps `0:02.5`, `0:06.5`, `0:10.5` for a
  `+500 ms` offset.
- The test lyric row and search history were removed after the checks; font size and opacity
  were restored to 15 sp / 100%. `tag_overrides` remained empty.

## Evidence

| Screenshot | Check |
|---|---|
| `01-pc-lyrics-offset-1180x780.png` | +500 ms, 20 px, 70%; `0:10` highlights the `0:06` line |
| `02-pc-settings-lyrics-1180x780.png` | Font size and opacity sliders with live preview |
| `03-pc-lyrics-offset-720x1280.png` | Narrow desktop layout, offset controls fit |
| `04-pc-settings-lyrics-720x1280.png` | Narrow desktop settings card |
| `05-android-player-offset-1080x1920.png` | Android player, +500 ms, 23 sp, 65%, active line |
| `06-android-settings-lyrics-1080x1920.png` | Android font/opacity sliders and preview |
| `07-android-editor-timing-1080x1920.png` | Editor controls and adjusted timestamps |
| `08-android-player-offset-720x1280.png` | Android 720×1280 player layout |
| `09-android-settings-lyrics-720x1280.png` | Android 720×1280 settings layout |
| `10-pc-click-offset-1000-1180x780.png` | Live PC click changes +500 to +1000 ms |

## Database / preference checks

```text
SELECT mediaId, offsetMs, length(text) FROM lyrics;
1000000017|500|137

SELECT COUNT(*) FROM tag_overrides;
0

SELECT offsetMs FROM lyrics WHERE mediaId = 1000000017;   -- after editor Reset + Save
0
```

`shared_prefs/lilt_settings.xml` during the check:

```xml
<float name="lyrics_font_size" value="23.0" />
<float name="lyrics_opacity" value="0.65" />
```

The APK requests no `INTERNET` permission.

## Limitations

- Offsets apply to timed LRC lines. Plain-text lyrics are shown as written; the offset
  controls are hidden when no line has a timestamp.
- Offset is keyed by the MediaStore/Room `mediaId` (PC: path key). A media ID change after a
  library move needs a new adjustment.
- Colors and per-line styling are not configurable; only size and opacity are.
- Android embedded and sidecar `.lrc` auto-loading is still not implemented; lyrics are
  stored through the editor/import flow.
- The desktop capture used the window capture path described above; the normal
  `--screenshot` flag is unchanged.

## Commands

```text
adb install -r base/app/android/app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant dev.lilt.player android.permission.READ_MEDIA_AUDIO
adb shell wm size 1080x1920 && adb shell wm density 420
adb shell wm size 720x1280  && adb shell wm density 320
adb shell "run-as dev.lilt.player sqlite3 databases/lilt.db 'SELECT mediaId,offsetMs FROM lyrics;'"

$env:LILT_START_PAGE="now"; $env:LILT_LYRICS="1"; $env:LILT_LYRICS_OFFSET="500"
$env:LILT_PAUSE_AFTER_SEEK="1"
.\base\app\target\debug\lilt.exe --size 1180x780
```
