# Round 6-B checks — Android virtual tag editing

Produced on the Android 15 (API 35) emulator `lilt35`. The 1080×1920 captures use
density 420; the 720×1280 captures use density 320. The media library is the existing
32-track emulator test library.

## Build and tests

```text
cd base/app/android
./gradlew testDebugUnitTest          # 24 tests, 0 failures
./gradlew connectedDebugAndroidTest  # 5 migration tests, 0 failures
./gradlew assembleDebug              # BUILD SUCCESSFUL
```

The new unit tests cover:

- applying an override to all display fields while keeping scan-only fields
- blank overrides falling back to the scanned tags
- the list-level override map
- blank, numeric, negative and invalid year/track parsing

The new migration test is `migrate5To6AddsTagOverridesAndKeepsUserData`. It creates a
version 5 database with a song, favourite/play-count state and lyrics, runs the 5→6
migration, verifies the existing rows, and inserts a `tag_overrides` row.

## Implementation

- Room is now version 6 with a `tag_overrides` table keyed by `mediaId`.
- The scanned `songs` row remains read-only. Blank text fields and zero numbers fall
  back to the MediaStore value.
- All song exits from `MusicRepository` merge overrides: library, all-songs flow,
  playlist flows, queue flows, and the suspend playlist/queue reads used before playback.
- The tag editor is reachable from every song overflow menu and from the now-playing
  overflow menu.
- Saving calls `replaceMediaItem` for the current track only if the edited song is the
  active Media3 item, then restores its position. The MediaSession metadata updates too.
- Reset deletes the override and restores the scanned values.

## Evidence

| Screenshot | Check |
|---|---|
| `01-tags-menu-1080x1920.png` | Overflow menu has **Edit tags** |
| `02-tags-editor-1080x1920.png` | Editor opens with scanned title/artist/album/track |
| `03-tags-edited-1080x1920.png` | Form accepts custom title, artist, album, year |
| `04-tags-saved-songs-1080x1920.png` | Song list immediately shows custom title/artist/album |
| `05-tags-albums-1080x1920.png` | Custom album appears as its own album group |
| `06-tags-artists-1080x1920.png` | Custom artist appears as its own artist group |
| `07-tags-search-1080x1920.png` | Search finds the custom title, artist and album |
| `08-tags-player-live-1080x1920.png` | Editing the playing song updates the player title and MediaSession metadata |
| `09-tags-reset-dialog-1080x1920.png` | Reset confirmation explains that the override is removed |
| `10-tags-reset-player-1080x1920.png` | Reset restores the scanned title/artist/album in the live player |
| `11-tags-persist-search-1080x1920.png` | Override survives force-stop, relaunch and the startup MediaStore scan |
| `12-tags-editor-720x1280.png` | Editor fits at 720×1280 with Save, fields and Reset visible |

## Database checks

After saving:

```text
1000000153|42 Signal Custom|Index Artist Two|Index Album Two|2026|27
```

After reset:

```text
tag_overrides count = 0
```

For the restart/scan check, the override was inserted while the app was stopped:

```text
1000000153|Persist Test
```

After relaunch and the automatic MediaStore scan:

```text
1000000153|Persist Test|Persist Artist|Persist Album|2026|27
```

## Limitations

- Only single-song editing is implemented in this slice; the multi-select bar does not
  have a batch tag editor yet.
- Cover replacement and genre editing are not implemented.
- The audio file is not rewritten. Tags are a Lilt/Room overlay and are not visible to
  other players.
- Changing the album name keeps the original MediaStore `albumId` for artwork, so the
  cover does not change.
- Long album names are ellipsized on 720-wide rows, matching the existing library
  layout.

## Commands

```text
adb install -r base/app/android/app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant dev.lilt.player android.permission.READ_MEDIA_AUDIO
adb shell wm size 1080x1920 && adb shell wm density 420
adb shell wm size 720x1280  && adb shell wm density 320
adb shell dumpsys media_session
```
