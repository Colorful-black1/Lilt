# Round 4-C checks — library lists, search, history and bookmarks

Produced on an Android 15 (API 35) emulator (`lilt35`), with the four tagged tracks from
`android/testmedia` plus a temporary 28-track library used only for the letter-index check.
Screenshots are stored in this folder.

## Build and tests

```text
cd base/app/android
./gradlew assembleDebug              # BUILD SUCCESSFUL
./gradlew testDebugUnitTest          # 13 tests, 0 failures
./gradlew connectedDebugAndroidTest  # 2 migration tests, 0 failures
```

`LiltDatabaseMigrationTest` now covers both upgrades:

- v1 → v2 adds `song_state` and keeps songs, playlists and queues.
- v2 → v3 adds `bookmarks` (with a unique `mediaId + positionMs` index) and
  `search_history` while keeping existing favourites and play counts.

## What this round adds

| Area | Result | Evidence |
|---|---|---|
| Recently added | Every visible song, newest `dateAdded` first, with an "Added …" line | `06-recent-1080x1920.png` |
| Most played | Songs with play count > 0, count and last-played ordering | `07-most-played-1080x1920.png` |
| History | Latest 200 playback starts, date/time per row, clear action | `08-history-1080x1920.png` |
| Bookmarks | Position + optional note, newest first, tap to play at position, delete | `09-bookmarks-1080x1920.png` |
| Player bookmarks | One-tap chips under the transport controls; dialog freezes the chosen position | `12-player-bookmark-bar-1080x1920.png` |
| Sorting | 9 keys, ascending/descending, persisted across restart | `03-sort-menu-1080x1920.png`, `04-sort-play-count-1080x1920.png` |
| Multi-select | Batch favourite, queue and playlist; select all/none; playlist removal | `02-selection-1080x1920.png`, `13-playlist-multiselect-1080x1920.png` |
| Letter index | A–Z + `#` strip when title-sorted; tapping `S` jumps to the first S | `17-letter-index-720x1280.png`, `18-letter-jump-720x1280.png`, `19-letter-index-1080x1920.png` |
| Search | Grouped songs/albums/artists/folders/playlists, match highlight | `10-search-grouped-1080x1920.png`, `20-search-highlight-1080x1920.png` |
| Search history | Latest 12 queries, tap to reuse, clear all | `11-search-history-1080x1920.png` |
| Layout | 1080×1920 at density 420 and 720×1280 at density 320 | screenshots `01`–`20` |
| Chinese UI | Library sections and count labels switch immediately | `16-chinese-library-720x1280.png` |

## Behaviour checks

### Sorting and persistence

The sort menu contains: title, artist, album, duration, date added, play count, last
played, track number and file name. Ascending/descending is a separate toggle. The title
sort was set to ascending, the app was force-stopped and relaunched, and the list was
still title-sorted with the A–Z strip visible.

### Batch operations

- Two songs were selected in the library and the batch favourite action wrote both rows:
  `42 Signal` and `Afterglow` became favourites.
- Two songs were selected and added to a playlist named `Batch`; the playlist showed
  `2 tracks`.
- In the playlist, the first song was selected and removed; the playlist then showed
  `1 track` with `Last Train` remaining.

### Search

`night` produced one song (`Last Train`), one album (`Night Tracks`), one artist
(`Night Transit`) and no folder or playlist hits. `signal` produced two songs and the
matching part of both titles was highlighted. Submitting queries stored `night` and
`signal`; clearing the query showed both chips, and the Clear action returned the empty
"Type to search" state.

### History and bookmarks

- Playback history reached 17 entries while exercising the app, and the clear action
  emptied it and showed the "No playback history" state.
- Two bookmarks were created on `Glass Horizon` with notes `Verse` and `Fades`; both
  appeared as player chips and in the library. Deleting `Verse` left `1 bookmark`.
- Bookmark taps start the song at the stored position via Media3's start position.

### Final database snapshot

After the checks, the debug database contained:

```text
songs            32  (4 tagged tracks + 28 temporary index files)
favourites        2  (42 Signal, Afterglow)
total playCount  33
history           0  (cleared after verification)
bookmarks         1  (Glass Horizon, 976 ms, "Persist")
search_history    0  (cleared after verification)
named queues      1  (Queue, 32 songs)
playlists         0  (the Batch playlist check happened before the final reinstall)
```

### Emulator reinstall note

During final APK packaging the app was reinstalled and Android restored an older local
backup. The scanned library and play counts came back, while the pre-reinstall Batch
playlist and earlier favourites did not. The playlist and batch-add checks in this
document were performed before that restore and are shown in the screenshots. After the
restore, favourites (2 songs), the bookmark, the current queue and persistence across a
restart were re-checked; those are the values in the snapshot above. This is an emulator
backup artifact, not an application-side reset path.

## Layout notes

- The selection toolbar fits at 720×1280 without clipping: cancel, count, favourite,
  queue, playlist and All are all visible.
- The A–Z strip uses a compact 9 sp line height so all 27 buckets fit above the mini
  player on 720×1280.
- The first letter-index screenshot used 32 songs: the 4 tagged tracks plus 28 synthetic
  files generated locally with ffmpeg. Those synthetic files live outside the repository
  and were pushed only to `/sdcard/Music/LiltIndex`.
- The number of recent songs in `06-recent` is four, matching the screenshot's earlier
  point in the run; later checks used the 32-track library.

## Not verified here

- Pinyin or CJK initials: non-Latin titles currently go to the `#` bucket.
- The letter strip only appears for title sorting and lists of at least 24 songs.
- Search folder results play the matching folder instead of navigating into the folder
  page.
- Editing an existing bookmark note; the current flow is delete and create again.
- Drag-to-reorder playlists and queues; this is still Round 5 work.
- Real embedded/MediaStore album art and real-phone audio routing (same limitations as
  Rounds 3 and 4).

## Commands used

```text
adb install -r base/app/android/app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant dev.lilt.player android.permission.READ_MEDIA_AUDIO
adb shell content call --uri content://media --method scan_volume --arg external_primary
adb shell wm size 1080x1920 && adb shell wm density 420
adb shell wm size 720x1280 && adb shell wm density 320
adb exec-out "run-as dev.lilt.player cat databases/lilt.db" > lilt.db
```
