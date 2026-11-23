# Round 7-C checks — A-B loop and missing-file re-linking

Produced on the Windows desktop build and on the Android 15 (API 35) emulator
`lilt35` at 1080×1920, 420 dpi. Android screenshots use the debug APK built
from this round.

## Build and tests

```text
cd base/app
cargo test
# 32 passed, 0 failed

cd android
.\gradlew.bat testDebugUnitTest
# 41 passed, 0 failed

.\gradlew.bat connectedDebugAndroidTest
# 7 Room migration tests passed

.\gradlew.bat assembleDebug
# BUILD SUCCESSFUL
```

New desktop coverage:

- `ab_loop_waits_for_b_and_jumps_at_the_end`
- `relinking_rewrites_playlists_and_queues_without_duplicates`
- `relinking_a_real_file_moves_references_and_song_state` (reads a real
  Starter Music WAV and checks playlist/queue/SongExtra/current-key movement)

New Android coverage:

- A/B markers require A first and a later B; the service jumps only at B.
- Missing references group playlists, queues, favourites, plays, lyrics,
  bookmarks and tag overrides per old media id, and skip ids that exist again.
- Room 7→8 creates `missing_songs` and keeps songs, favourites and playlist
  rows intact.

## Implementation

### Desktop (Rust)

- `AbLoop { a, b }` is an in-memory playback marker. The now-playing panel
  cycles A → B → clear and shows both times plus **Clear A-B**.
- `update_playback` checks the current position against B and seeks back to A
  through the existing audio engine. Manual seeks outside the range and track
  changes clear the markers.
- Scans no longer delete playlist keys that do not resolve to a scanned file.
  Queues remain transient and are still pruned.
- `missing_files()` merges playlist/queue keys, songs whose path disappeared
  and SongExtra entries with user data. The settings Library card and playlist
  header show the count; a modal lists the old path and the affected sources.
- **Locate…** validates that the replacement is an audio file inside a library
  root, reads its metadata with the old SongExtra values, updates playlist and
  queue keys (deduplicating), moves the SongExtra, updates `current_key` and
  the selection, rebuilds album/artist/folder indexes and saves immediately.

### Android (Kotlin)

- `PlaybackService` owns two markers (`abStartMs`, `abEndMs`) and a 100 ms
  ticker. The marker values are pushed with MediaSession custom commands
  `AB_SET`, `AB_GET` and `AB_CLEAR`, so the loop keeps running with the screen
  off or the app in the background. A media-item transition clears it.
- The now-playing screen has **Set A**, **Set B**, **Clear** and shows the two
  times. A/B must be later than A; manual seeks outside an active range clear
  it.
- Room v8 adds `missing_songs`. A scan reads the stale `songs` rows before
  deleting them and stores media id, title, artist, album, folder, file name,
  duration and last-seen time.
- `MissingFiles.kt` groups old ids with playlist/queue names and saved data.
  `MissingFilesScreen` lists them and opens a searchable picker over the
  current library.
- Re-linking runs one Room transaction and rewrites `playlist_songs`,
  `queue_songs`, `song_state`, `lyrics`, `bookmarks`, `tag_overrides` and
  `history`, then deletes the stale `missing_songs` row. `UPDATE OR IGNORE`
  plus a delete of the old rows keeps an already-existing new row instead of
  creating duplicates.

## Behaviour checks

- **Android missing file.** A fake MediaStore row (id `999000001`) was added to
  `songs` and referenced by playlist **Missing Test**, a favourite with 9
  plays, a lyric at +250 ms and a bookmark. A real scan removed it from
  `songs`, wrote its metadata to `missing_songs` and kept every reference.
  Settings showed **1 track can be reconnected**; the missing screen showed the
  title, folder/file name and all reference sources.
- **Android re-link.** Choosing `Glass Horizon` (id `1000000017`) rewrote the
  playlist, favourite, play count, lyric offset and bookmark in one
  transaction. `missing_songs` became empty and the screen reported
  **Nothing is missing**. The playlist already contained the same song, so the
  conflict policy kept one row. See `android-missing-db.txt`.
- **Android A-B.** On `Glass Horizon`, A was set at 0:00 and B at 0:04.
  Sampling after more than 4 seconds still showed 0:01, i.e. playback had
  jumped back instead of reaching 0:05+. The screenshot shows both markers and
  the Clear button.
- **Desktop A-B.** The now-playing panel shows `A-B 0:10 – 0:16` and
  **Clear A-B** with playback inside the range.
- **Desktop missing files.** Settings shows `1 missing file(s)` with
  **Review missing files**; the playlist header shows
  `2 tracks · 1 min · 1 missing`; the dialog lists the old path, playlist and
  saved data with a **Locate…** button. The file-picker flow itself was
  covered by the real-file unit test rather than automating the native Windows
  dialog.
- Neither build adds a network permission. Android re-linking uses only the
  existing MediaStore library and Room; no storage permission was added.

## Packaged artifacts

```text
base/app/Lilt-1.0-debug.apk             22,508,399 bytes
dist/Lilt-0.1.0-win64/lilt.exe          10,895,360 bytes  (PE subsystem 2)
dist/Lilt-0.1.0-win64-portable.zip      18,044,523 bytes
```

`aapt dump permissions` reports no `INTERNET` permission. `SYSTEM_ALERT_WINDOW`
is still the optional floating-lyrics permission. The final APK was installed
over the previous build, granted audio permission and smoke-launched without a
fatal exception. The desktop EXE remains a Windows GUI-subsystem binary.

## Evidence files

| File | What it shows |
|---|---|
| `01-pc-ab-loop-1180x780.png` | Desktop now-playing A-B controls with active markers |
| `02-pc-missing-dialog-1180x780.png` | Desktop missing-file dialog, path and references |
| `03-pc-playlist-missing-1180x780.png` | Playlist header with `1 missing` and locate action |
| `04-pc-settings-missing-1180x780.png` | Settings Library card with missing count |
| `05-pc-settings-missing-720x1280.png` | Narrow settings layout, no clipped controls |
| `06-android-ab-loop-1080x1920.png` | Android A 0:00 / B 0:04 loop in the player |
| `07-android-missing-list-1080x1920.png` | Missing list with title, path and saved data |
| `08-android-relink-picker-1080x1920.png` | Searchable replacement picker |
| `09-android-relinked-1080x1920.png` | Empty missing list after re-linking |
| `android-missing-db.txt` | Room rows before/after the re-link transaction |
| `pc-tests.txt` | Desktop test result and new test names |
| `android-tests.txt` | Android unit/migration test results |

## Limitations

- A-B markers are session state. They are not saved into `state.json` or
  Settings and are cleared when the track changes.
- A-B loop does not implement crossfade, gapless-boundary looping across tracks
  or a single-track “stop after this song” mode.
- On desktop the replacement must be inside a configured library folder; the
  file picker cannot add a new root by itself.
- On Android the replacement is chosen from the current MediaStore library.
  Files that are not visible in MediaStore cannot be selected, and a stale
  active MediaController item is not rewritten mid-playback.
- Conflicting playlist/queue duplicates keep the existing row for the new id;
  the old row is removed rather than merged into a second copy.
- Duplicate detection, batch rename, audio trimming, CUE and format conversion
  remain for later rounds.
