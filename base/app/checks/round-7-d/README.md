# Round 7-D checks — single-track stop and duplicate songs

Produced on the Windows desktop build and on the Android 15 (API 35) emulator
`lilt35` at 1080×1920, 420 dpi. Android screenshots use the debug APK built
from this round.

## Build and tests

```text
cd base/app
cargo test
# 37 passed, 0 failed

cd android
.\gradlew.bat testDebugUnitTest
# 47 passed, 0 failed

.\gradlew.bat connectedDebugAndroidTest
# 7 Room migration tests passed

.\gradlew.bat assembleDebug
# BUILD SUCCESSFUL
```

New desktop coverage (all in `src/duplicates.rs`):

- `groups_near_identical_metadata_but_not_distant_lengths`
- `same_size_and_length_alone_is_not_enough`
- `suggests_the_highest_quality_copy_first`
- `ignores_singletons_missing_durations_and_tiny_titles`
- `formats_small_and_large_files`

New Android coverage:

- `PlaybackToolsTest.stopAfterNeedsAnEnabledPlayingTrackNearTheEnd`
- `DuplicateToolsTest.groupsNearIdenticalTagsButNotDistantLengths`
- `DuplicateToolsTest.sameSizeAndLengthAloneIsNotEnough`
- `DuplicateToolsTest.suggestsHighestQualityCopyFirst`
- `DuplicateToolsTest.ignoresSingletonsMissingLengthsAndTinyTitles`
- `DuplicateToolsTest.formatsBytes`

## Implementation

### Desktop (Rust)

- `LiltApp.stop_after_current` is a session-only one-shot flag. The now-playing
  panel adds a **Stop after this song** / **Stopping after this song ✓** button.
- Enabling it drops an already queued gapless source by restarting the current
  track at its current position, so the next track cannot start before the
  current one ends. `queue_next` is also blocked while the flag is set.
- `update_playback` pauses when the sink drains, clears the flag and saves the
  track end as the queue position; the next press of Play restarts the song
  from 0:00. Manual prev/next, choosing another song/context and toggling the
  button again cancel the flag.
- `src/duplicates.rs` normalises title and artist (lowercase, remove everything
  that is not a letter or digit), groups exact normalised pairs, then clusters
  lengths within 2 seconds against a cluster anchor. Groups need at least two
  songs. The best copy sorts first by bitrate, then file size, then title.
- `Settings → Find duplicate songs` opens a modal with group count, best-copy
  **KEEP** marker, format, bitrate, size, duration and full path. Each copy has
  **Play** and **Show** (Explorer) actions. Nothing is deleted.

### Android (Kotlin)

- `PlaybackService` owns `stopAfterCurrent` and a 100 ms ticker. When the
  position is within 180 ms of the duration, it pauses, seeks to 0:00 and
  clears the flag. The ticker restarts when playback resumes, so the setting
  survives a manual pause. MediaSession commands `STOP_AFTER_SET` and
  `STOP_AFTER_GET` expose the flag; a media-item transition clears it.
- The player has a **Stop after this song** toggle that highlights as
  **Stopping after this song**, plus a persistent **Stopped after this song**
  note after the service fires. The ViewModel polls the service once per second
  while the flag is active.
- `library/DuplicateTools.kt` uses the same normalised title + artist + ±2 s
  length rules and best-copy ordering as the desktop build.
- `Settings → Duplicate songs` opens `DuplicatesScreen`, which lists each group
  with format, estimated bitrate, size, duration and folder path. Tapping a
  copy plays it. No delete action is offered.

### Why the size rule was removed

An early version also grouped identical byte size plus duration. The emulator's
generated 3-second index tracks have several equal byte sizes, so 30 songs were
reported as 8 false groups. Size and length are not enough without an audio
fingerprint. The final rule uses tags only; `same_size_and_length_alone_is_not_enough`
and `sameSizeAndLengthAloneIsNotEnough` guard the behaviour.

## Behaviour checks

- **Desktop stop-after.** A two-track queue started at 0:45.5 of the 48-second
  `Glass Horizon` with stop-after enabled. After the track ended the player
  showed `0:48 / 0:48`, paused, and **Up next** still showed `Soft Static`.
  See `01-pc-stop-after-1180x780.png` and `behaviour.txt`.
- **Desktop duplicates.** A temporary library with three copies of
  `Glass Horizon` and two copies of `Soft Static` produced
  `2 duplicate groups · 5 files`. The first copy was marked **KEEP**; the
  dialog listed `WAV · 256 kbps · 1.5 MB · 0:48` and each path. The same dialog
  renders at 720×1280 without clipped controls.
- **Android stop-after.** A three-item search queue started with the 3-second
  `Glass`, followed by two `Glass Horizon` copies. Stop-after was enabled
  immediately; five seconds later the player still showed `Glass` at
  `0:00 / 0:03`, paused, with **Stopped after this song**. It did not advance
  to the next item. A second capture shows the active
  **Stopping after this song** state on `Glass Horizon`.
- **Android duplicates.** `05 - Glass Horizon copy.mp3` was copied into
  `/sdcard/Music/LiltTest`; MediaStore and the app scan raised the library to
  33 songs. Settings showed `1 group · 2 files`, and the screen marked
  `01 - Glass Horizon.mp3` **KEEP** while listing both files as
  `MP3 · 24 kbps · 59 KB · 0:20` with paths. Database rows
  `1000000017` and `1000000392` share title, artist, duration and size.
- Neither build adds a network permission. Duplicate detection reads only
  already scanned/MediaStore metadata; no storage permission was added.

## Evidence files

| File | What it shows |
|---|---|
| `01-pc-stop-after-1180x780.png` | Desktop paused at 0:48 after the song, next item unchanged |
| `02-pc-duplicates-1180x780.png` | Desktop duplicate dialog, 2 groups, KEEP and file details |
| `03-pc-duplicates-720x1280.png` | Narrow desktop duplicate dialog |
| `04-pc-settings-duplicates-1180x780.png` | Settings library card with the duplicate entry |
| `05-android-stop-active-1080x1920.png` | Android **Stopping after this song** active state |
| `06-android-stopped-1080x1920.png` | Android paused at `Glass` 0:00 / 0:03 after the stop |
| `07-android-duplicates-1080x1920.png` | Android duplicate group with KEEP and paths |
| `08-android-settings-duplicates-1080x1920.png` | Android settings showing `1 group · 2 files` |
| `behaviour.txt` | Queue, duplicate-row and false-positive details |
| `pc-tests.txt` | Desktop test result and new test names |
| `android-tests.txt` | Android unit/migration test results |

## Packaged artifacts

```text
base/app/Lilt-1.0-debug.apk             22,526,322 bytes
dist/Lilt-0.1.0-win64/lilt.exe          10,944,000 bytes  (PE subsystem 2)
dist/Lilt-0.1.0-win64-portable.zip      18,074,368 bytes
```

`aapt dump permissions` reports no `INTERNET` permission. `SYSTEM_ALERT_WINDOW`
remains the optional floating-lyrics permission. The final APK was installed
over the previous build, granted audio permission and smoke-launched without a
fatal exception. The desktop EXE remains a Windows GUI-subsystem binary.

## Limitations

- Stop-after is one-shot session state. It is not saved into `state.json` or
  Settings and is cancelled by changing track. It does not persist across a
  service restart or app process death.
- Stop-after relies on the 100 ms service ticker on Android and on the sink
  becoming empty on desktop. Very short or corrupt tracks that end before the
  first tick are not specially handled.
- An active A-B loop can keep playback inside the range and prevent the track
  from reaching its end, so stop-after will not fire until the loop is cleared.
- Duplicate detection is tag/metadata based. It does not hash audio samples, so
  re-tagged copies with different titles or artists are not linked. It also
  deliberately does not delete, move or rename files; batch cleanup remains for
  a later round.
- True crossfade, batch rename, audio trimming, CUE and format conversion remain
  for later rounds.
