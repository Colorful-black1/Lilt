# Round 5 checks — lyrics, sleep timer, fades, equalizer and queue memory

Produced on an Android 15 (API 35) emulator (`lilt35`) with the four tagged tracks from
`android/testmedia` plus 28 short synthetic index tracks. Screenshots are stored in this
folder.

## Build and tests

```text
cd base/app/android
./gradlew assembleDebug              # BUILD SUCCESSFUL
./gradlew testDebugUnitTest          # 20 tests, 0 failures
./gradlew connectedDebugAndroidTest  # 4 migration tests, 0 failures
```

`LiltDatabaseMigrationTest` covers 1→2, 2→3, 3→4 and 4→5. The 4→5 migration adds
`lastIndex` and `lastPositionMs` to `queues` with defaults and keeps existing rows.

## What this round adds

| Area | Result | Evidence |
|---|---|---|
| Lyrics storage | Room v4 `lyrics` table keyed by `mediaId`, separate from scan data | database snapshot below |
| LRC parsing | `[mm:ss.xx]`, `[mm:ss,xxx]`, multiple tags per line, metadata ignored, sorted | 20 unit tests |
| Lyrics display | Plain text and timed lines; current line highlighted and auto-scrolled | `01-lyrics-player-1080x1920.png` |
| Lyrics editor | Full-screen editor, timed-line count, clear confirmation | `08-lyrics-editor-1080x1920.png` |
| Lyrics import | Local `.lrc`/`.txt` through the system document picker | imported `lilt-import.lrc`, 4 timed lines |
| Sleep timer | Custom hours/minutes, song count, after this track, fade, countdown, cancel, service-side | `02-sleep-dialog-1080x1920.png`, `03-sleep-active-dialog-1080x1920.png` |
| Fades | Play fade-in and pause fade-out, 0–4 s in 250 ms steps | Settings slider, measured pause delay |
| Equalizer | 22 presets, preamp Off/Headroom/Boost, headroom suggestion, bass, virtual surround, balance, per-device profiles | `05-equalizer-top-1080x1920.png`, `06-equalizer-tone-1080x1920.png` |
| Queue memory | Named queues save index + position, resume on tap, setting to disable | `07-queue-resume-1080x1920.png` |
| Layout | 1080×1920 at density 420 and 720×1280 at density 320 | screenshots `01`–`12` |
| Chinese UI | New player, sleep, equalizer and queue strings | `09`–`12` |

## Behaviour checks

### Lyrics

- A four-line LRC file was imported for `Glass Horizon` through the DocumentsUI picker;
  the editor showed `4 timed lines` and the text without corruption.
- At `0:10` the player highlighted `Static on the line`, the third timestamped line.
- After a force-stop and relaunch, the lyrics were still shown for `Glass Horizon`.
- The stored text was 120 characters in the `lyrics` row for media id `1000000017`.

### Sleep timer

- Song mode: started with `1 song`, fade off, from a paused 3-second track. Playback
  stopped by itself at `2.403 s / 3.030 s` and the timer state disappeared.
- Time mode: started at `0:58 left`, counted down to `0:42 left`, and **Cancel timer**
  cleared it.
- Background test: a one-minute time timer was started, then the app was sent home with
  `KEYCODE_HOME`. After 66 seconds the MediaSession was `PAUSED` at `436 ms` of a
  3-second track, active item 16, not `STOPPED`. This shows the service timer ran with
  the screen off and the activity in the background.
- Sleep state was cleared after firing and after the queue ended.

### Fades

- `fade_out_ms = 1000` was stored from the Settings slider.
- With a 1-second fade-out, the session was still `PLAYING` 0.8 s after the pause key
  and `PAUSED` at the 1.7 s sample. (ADB command latency makes these approximate.)
- Fade-in uses the same 50 ms volume ramp from 0 to 1. The emulator runs with `-no-audio`,
  so the audible curve was not verified; the playback state and volume-path code were.

### Equalizer

- Selecting the `Bass` preset changed every band in the UI and wrote the same curve to
  the base keys and to `profile_speaker`.
- Moving the 63 Hz band to about +8.5 dB changed the preset to `Custom` and produced a
  `Suggested headroom: -7 dB` hint. Using the hint set Headroom to about 2.5 dB.
- Bass 28–50 %, virtual surround 17–50 % and Balance L52/R21 all updated immediately and
  were written to the profile. The emulator reported the virtualizer as available.
- Turning **Per device** off kept the base values changing while `profile_speaker` stayed
  at its old string. Turning it back on copied the current values into `profile_speaker`.
- After force-stop and relaunch, Bass 39 %, Virtual surround 23 % and Balance R21 were
  restored, along with the per-device switch.

Stored profile example:

```text
1|1|0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0|off|6.0|2.0|0.3935236|0.23216246|121.29529|Flat
```

### Queue position memory

- A queue `Memory` was saved while `Glass Horizon` was paused at `0:08`; the row showed
  `2 tracks · resume 0:08`.
- Playing another song cleared the active queue after saving the old position. Tapping
  `Memory` again resumed at about `0:09` on `Glass Horizon` (index 1), not at the start.
- After a force-stop and relaunch, the row still showed `resume 0:09` and tapping it
  resumed at `9.6 s`.
- Turning **Remember queue positions** off made the same queue start at index 0,
  position `0.2 s` (`Glass`), while the stored position stayed in the database.
- Positions are written every five seconds while playing and immediately on pause or when
  switching away from a queue.

### Final database snapshot

```text
user_version      5
songs             32
lyrics            1   (Glass Horizon, 4 timed lines, 120 chars)
queues            1   (Memory, lastIndex 1, lastPositionMs 9249, 2 songs)
queue songs       0: Glass / 1: Glass Horizon
favourites        0
total playCount  22
bookmarks         0
search_history    1
```

### Settings used for the checks

The UI checks used English at 1080×1920 and Chinese at 720×1280, then returned to English
at 1080×1920. The sleep defaults were set to 1 h 30 min, `fade_out_ms` to 1000 and the EQ
profile to the values above. A final instrumentation run cleared the debug app data after
the evidence was captured, so the emulator does not retain those demo values; the database
snapshot below describes the verified run.

## Layout notes

- The 1080×1920 player scrolls; the lyrics card sits below the transport and quick
  actions. Scrolling reveals the full 180 dp lyric viewport.
- The 720×1280 sleep dialog fits without clipping: both mode pills, both number fields,
  all four time presets, both switches and the buttons are visible.
- The 720×1280 equalizer keeps the output card, preamp, presets and the first bands on
  one scrollable page; the preset row scrolls horizontally.
- Non-Latin titles still use the `#` letter bucket; no pinyin index.

## Not verified here

- Embedded lyrics and same-folder `.lrc` auto-loading. Android scoped storage does not
  expose arbitrary sidecar files, so lyrics are Room-backed and imported through SAF.
- LRC offset (`[offset:]`) and word-by-word lyrics.
- Audible fade-in/fade-out shape because the emulator runs without an audio device.
- Balance on Android 8.0–8.1, where the legacy platform equalizer has no per-channel EQ.
- Real-phone headset/Bluetooth routing (same limitation as earlier rounds).
- Queue/playlist drag reorder. Queue position memory does not add reordering.

## Commands used

```text
adb install -r base/app/android/app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant dev.lilt.player android.permission.READ_MEDIA_AUDIO
adb shell wm size 1080x1920 && adb shell wm density 420
adb shell wm size 720x1280 && adb shell wm density 320
adb shell input keyevent KEYCODE_HOME
adb shell dumpsys media_session
adb exec-out "run-as dev.lilt.player cat databases/lilt.db" > lilt.db
```
