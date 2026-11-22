# Round 4 checks — Android library, favourites, albums and artists

Everything here was produced in this workspace on an Android 15 (API 35) emulator (`lilt35`)
with the four tracks from `android/testmedia`, pushed into
`/sdcard/Music/LiltTest` and scanned through MediaStore.

## Build and tests

```
cd base/app/android
./gradlew assembleDebug              # BUILD SUCCESSFUL
./gradlew testDebugUnitTest          # 8 tests, 0 failures
./gradlew connectedDebugAndroidTest  # 1 migration test, 0 failures
```

The migration test creates a schema-v1 database with a song, playlist, queue and their
membership rows, runs `MIGRATION_1_2`, validates the schema and confirms the old rows plus
the new `song_state` table are usable.

## What this round adds

| Area | Result | Evidence |
|---|---|---|
| Songs library | All visible songs, title/artist/album, duration, favourite heart | `01-songs-1080x1920.png`, `07-songs-720x1280.png` |
| Favourites | Heart toggles; Favorites section survives app restart and rescan | `02-favorites-1080x1920.png` |
| Albums | Grouped from tags, cover/artist/count/duration, play button | `03-albums-1080x1920.png` |
| Album detail | Header, play all, shuffle, song list, favourite/queue/playlist actions | `04-album-detail-1080x1920.png`, `08-album-detail-720x1280.png` |
| Artists | Grouped from tags, initials, track/album counts, play button | `05-artists-1080x1920.png` |
| Artist detail | Header, play all, shuffle, song list, favourite/queue/playlist actions | `06-artist-detail-1080x1920.png` |
| Chinese UI | New library strings switch immediately | `09-chinese-library-720x1280.png` |
| Existing screens | Hearts added to folder, playlist, search and now-playing screens | code + screenshots above |
| Play count | 0 after 2 seconds, 1 after 8 seconds on a 0:14 track (30% threshold) | SQLite query below |
| Upgrade | v1 APK installed, scanned, then v2 APK installed over it; all 4 songs remained | manual emulator run |

## Database evidence

After playing `Last Train` for 2 seconds:

```text
sqlite> SELECT mediaId, playCount FROM song_state;
(empty)
```

After relaunching and playing the same track for 8 seconds:

```text
sqlite> SELECT mediaId, playCount FROM song_state;
1000000020|1
```

The favourite row was also checked:

```text
sqlite> SELECT mediaId, favorite FROM song_state WHERE favorite = 1;
1000000017|1
```

## Layout checks

- `01-songs-1080x1920.png` through `06-artist-detail-1080x1920.png`: 1080×1920, density 420.
- `07-songs-720x1280.png`, `08-album-detail-720x1280.png`: 720×1280, density 320.
- `09-chinese-library-720x1280.png`: Chinese UI at 720×1280.
- No overlapping controls or clipped buttons. Long song subtitles ellipsize as intended.

## Not verified here

- Physical phone audio routing and headset unplug (same limitation as Round 3).
- Album art from real embedded/MediaStore artwork; the four test files have no album art,
  so the generated fallback covers are shown.
- Same-name albums are separated by MediaStore `albumId` in the navigation route, but no
  real duplicate-name pair was available for an end-to-end check.
- Multi-artist tags such as `Artist A; Artist B` (current grouping keeps the full tag).
- Tag editing, lyrics, bookmarks and history screens are still later rounds.

## Commands used

```
adb install -r base/app/android/app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant dev.lilt.player android.permission.READ_MEDIA_AUDIO
adb shell content call --uri content://media --method scan_volume --arg external_primary
adb shell wm size 1080x1920 && adb shell wm density 420
adb shell wm size 720x1280 && adb shell wm density 320
adb exec-out "run-as dev.lilt.player cat databases/lilt.db" > lilt.db
```
