# Round 7-B checks — local backup and restore

Produced on the Windows desktop build and on the Android 15 (API 35) emulator
`lilt35` at 1080×1920, 420 dpi. Android screenshots use the packaged debug APK.

## Build and tests

```text
cd base/app
cargo test
# 29 passed, 0 failed

cd android
.\gradlew.bat testDebugUnitTest
# 37 passed, 0 failed

.\gradlew.bat connectedDebugAndroidTest
# 6 Room migration tests passed

.\gradlew.bat assembleDebug
# BUILD SUCCESSFUL

cd ..\..
.\package.ps1 apk
# APK copied to base/app/Lilt-1.0-debug.apk
```

New desktop coverage:

- `backup_round_trip_keeps_user_data`
- `backup_rejects_other_platforms_and_versions`
- `backup_sanitizes_hand_edited_values`
- `duplicate_ids_are_reassigned_and_active_queue_stays_valid`
- `restore_keeps_existing_roots_when_backup_paths_are_missing`
- `describes_the_most_important_counts`

New Android coverage:

- JSON encode/decode round trip for songs, playlists, queues, favourites,
  history, bookmarks, lyrics, tag overrides, folder rules, searches, settings
  and EQ;
- rejection of a desktop backup, an unknown format and invalid values;
- lookup by MediaStore id, then folder + file name, then title/artist/duration;
- ambiguous metadata is not guessed;
- preview counts and EQ profile conversion.

## Implementation

### Desktop (Rust)

- `src/backup.rs` defines a versioned envelope:
  `app = "lilt"`, `format = 1`, `platform = "pc"` and a `data` object.
- The data contains user-owned state only: settings, library roots, include and
  exclude rules, playlists, queues and positions, favourites, play counts, last
  positions, bookmarks, lyrics and offsets, tag overrides, EQ profiles and
  recent searches. Audio files and the scanned index are not copied.
- Export writes pretty JSON through the native save dialog. Restore validates
  the envelope, sanitises ranges, normalises colliding playlist/queue ids, keeps
  the current library roots when the backup points at folders that no longer
  exist, stops playback, applies EQ/settings, saves `state.json` and starts a
  rescan.
- Settings → **Backup & restore** has Export and Restore buttons. Restore shows
  a confirmation with the file name and count summary before replacing data.

### Android (Kotlin)

- `data/Backup.kt` is the versioned JSON model and parser. The envelope is
  `app = "lilt"`, `format = 1`, `platform = "android"`. All numeric ranges are
  clamped and enum-like strings fall back to safe defaults.
- `data/BackupManager.kt` collects Room user tables and `SharedPreferences`
  values, then applies a restore inside one `RoomDatabase.withTransaction`.
  Scanned `songs` rows are never written.
- Song references are resolved by `mediaId`, then `folder + displayName`, then
  `title + artist + duration`. Missing references are skipped and counted, so a
  rescanned library does not get invented mappings.
- Restore replaces playlists, queues, song state, history, bookmarks, lyrics,
  tag overrides, folder rules and search history; playlists and queues receive
  fresh Room ids. Settings are applied through `SettingsStore`, the active queue
  is pointed at the restored queue, and the restored EQ profile is pushed to the
  running playback service.
- `SettingsScreen` uses the Storage Access Framework
  (`CreateDocument` / `OpenDocument`), so no storage permission is added. A
  confirmation dialog shows the backup contents. Export/restore run on
  `Dispatchers.IO`; results are reported with a toast.

## Behaviour checks

- **Android end-to-end.** A backup of 9,687 bytes was exported to Download. The
  test state had one playlist (3 songs), one queue (2 songs, index 1, 5000 ms),
  one favourite with 4 plays, one 85-character lyric at +250 ms, one bookmark,
  one tag override and one recent search. Settings were accent `sage`, speed
  `1.25×`, lyric size `17.5 sp`, floating size `30.5 sp`.
- All user tables were then cleared and the settings changed to `rose` /
  `0.75×` / `12.0 sp` / `20.0 sp`; the app was restarted.
- Restoring the same JSON recreated the playlist (3 songs), queue (2 songs,
  index 1, 5000 ms), favourite (4 plays), lyric (+250 ms), bookmark, tag
  override and search. The settings returned to the backup values. See
  `android-restored-db.txt` and `settings-restored.txt`.
- The playlist screen then showed **Backup Test List · 3 tracks**. See
  `05-android-restored-playlists-1080x1920.png`.
- The confirmation dialog reports the actual counts and warns that audio files
  are not changed. See `04-android-restore-confirm-1080x1920.png`.
- The desktop settings card renders at 1180×780 and 720×1280 without clipped
  buttons. See the first two screenshots.
- `aapt dump permissions` still reports no `INTERNET` permission. The backup
  path adds no storage permission because SAF owns file access.

## Evidence files

| File | What it shows |
|---|---|
| `01-pc-backup-settings-1180x780.png` | Desktop Backup & restore card |
| `02-pc-backup-settings-720x1280.png` | Narrow desktop card, no clipped controls |
| `03-android-backup-settings-1080x1920.png` | Android Backup & restore card |
| `04-android-restore-confirm-1080x1920.png` | Restore confirmation with counts |
| `05-android-restored-playlists-1080x1920.png` | Playlist recreated after restore |
| `android-backup.json` | Real exported Android backup (9,687 bytes) |
| `android-restored-db.txt` | Room rows after export → clear → restore |
| `settings-restored.txt` | SharedPreferences after restore |
| `pc-tests.txt` | Desktop test result and new test names |
| `android-tests.txt` | Android unit/migration test results |

## Limitations

- PC and Android backups use separate platform-specific JSON envelopes. A file
  from one platform is rejected by the other with a clear message; cross-platform
  import is not implemented.
- A restore replaces the current playlists, queues and user data instead of
  merging them. There is no undo; export a fresh backup before restoring.
- References to audio files that no longer exist are skipped and counted.
  Re-linking missing files is a later library-tools round.
- Android backup does not include audio, cached artwork, scan rows or the
  per-device EQ profile keys; it includes the current EQ values and pushes them
  to the service after restore.
- The desktop backup keeps playlist/queue keys for missing files instead of
  deleting them, so a later file-relocation feature can use them.
