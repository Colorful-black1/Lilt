# Round 6-A checks — locate current song and list reordering

Produced on the Android 15 (API 35) emulator `lilt35` at 1080×1920, density 420, with
the existing 32-track test library. The screenshots in this folder are from the same
emulator run.

## Build and tests

```text
cd base/app/android
./gradlew testDebugUnitTest          # 21 tests, 0 failures
./gradlew connectedDebugAndroidTest  # 4 migration tests, 0 failures
./gradlew assembleDebug              # BUILD SUCCESSFUL

cd base/app
cargo test                           # 14 tests, 0 failures
cargo build --release                # success
```

The new unit test is `LibraryTest.movedItemReordersAndIgnoresInvalidPositions`. It
covers moving an item down, moving it to the end, a no-op move, negative and
past-the-end indexes, and an empty list.

## Locate current song

Android:

- The top app bar has a target icon. It is disabled until a track has been started
  (`PlayerUiState.mediaId > 0`).
- The Songs list header also shows the target icon when the current track is in the
  visible list.
- A request switches the Library tab to Songs and scrolls the list to the current row.
- The request is one-shot: after the scroll it is consumed, so later manual scrolling
  and the 3-second test tracks do not keep pulling the list back.

Evidence:

| Screenshot | Check |
|---|---|
| `01-locate-available-1080x1920.png` | Current track `Drift` is highlighted, target icon appears in the header and app bar |
| `02-locate-before-1080x1920.png` | List scrolled to the end (`Tide`…`Zenith`) |
| `03-locate-after-1080x1920.png` | Tapping the app-bar target returned to the current row `Iris` |
| `08-locate-cross-tab-1080x1920.png` | Tapping target from the Playlists tab switched to Library → Songs and found `Afterglow` |

The current track moved from `Drift` to `Iris` between captures because these test
tracks are only three seconds long; the important result is that the new current row
was found and centred.

Desktop:

- `LiltApp::locate_current_song()` clears the search/favourites filter, opens Songs and
  sets a one-shot `scroll_to_current` flag.
- `song_table` consumes the flag only on the current row `ui.scroll_to_rect` call, and
  navigation clears stale flags.
- The button is available in the top bar, the Songs header and the now-playing overlay.
- `11-desktop-locate-1180x780.png` captures the release build at 1180×780: the target
  icon is in the top bar and the **Locate** button is beside Sort and Shuffle. The
  desktop shell used for this run cannot drive an interactive window, so the desktop
  scroll action itself was compile-checked and packaged but not clicked; the Android
  flow above verifies the same one-shot interaction model.

## Playlist reorder

- Open a playlist, tap the up/down icon, then use the arrows on each row.
- The database was checked directly before and after the tap:

```text
before: 1000000017|0  1000000018|1  1000000019|2
after:  1000000018|0  1000000017|1  1000000019|2
```

- `04-playlist-reorder-1080x1920.png` shows reorder mode; `05-playlist-moved-1080x1920.png`
  shows `Soft Static` and `Glass Horizon` swapped.
- The first row's up arrow and the last row's down arrow are disabled.
- Selection, favourite, remove and the overflow menu are hidden while reordering.

## Queue detail and reorder

- Tapping a queue row now opens a detail screen; the row's play icon still starts
  playback from the saved queue position.
- The detail screen shows the count and total duration, Play/Shuffle, per-song
  favourite/remove actions, and the same reorder mode.
- Tapping a song starts that queue from the tapped row.
- Direct database check after moving the first queue row:

```text
before: 1000000017|0  1000000018|1  1000000019|2
after:  1000000018|0  1000000017|1  1000000019|2
```

- `06-queue-detail-1080x1920.png` shows the new screen; `07-queue-moved-1080x1920.png`
  shows the reordered queue.

## Implementation notes

- Reorder writes happen in one Room transaction: read the ordered media IDs, apply
  `movedItem`, then rewrite every position. No schema change was needed, so Room stays
  at v5 and the existing 1→2, 2→3, 3→4 and 4→5 migrations remain valid.
- Playlist and queue order changes affect the saved list, not the already-prepared
  Media3 item list. The new order is used the next time the playlist or queue is played.
- The queue detail page removes songs from the saved queue only; audio files are never
  deleted.

## Layout checks

- 1080×1920 at density 420: all new controls fit, including the app-bar target,
  reorder mode and queue detail.
- 720×1280 at density 320: the playlist and queue detail screens keep both arrow
  columns and the header actions visible. Album subtitles use the existing ellipsis.
  See `09-playlist-reorder-720x1280.png` and `10-queue-detail-720x1280.png`.

## Not verified here

- Drag gestures: reordering currently uses accessible up/down controls, not drag-and-drop.
- Live reordering of the playing Media3 queue while it is already prepared.
- Clicking the desktop Locate buttons through an interactive window (the buttons are
  visible in the captured release build, but this shell cannot drive the window).
- Queue-to-queue automatic advance, which remains a later round.

## Screenshot list

| File | Content |
|---|---|
| `01-locate-available-1080x1920.png` | Locate icons with a current track |
| `02-locate-before-1080x1920.png` | Songs list scrolled to the end |
| `03-locate-after-1080x1920.png` | Current row after tapping Locate |
| `04-playlist-reorder-1080x1920.png` | Playlist reorder mode |
| `05-playlist-moved-1080x1920.png` | Playlist after moving a row |
| `06-queue-detail-1080x1920.png` | New queue detail screen |
| `07-queue-moved-1080x1920.png` | Queue reorder mode after moving a row |
| `08-locate-cross-tab-1080x1920.png` | Locate from another tab switches to Songs |
| `09-playlist-reorder-720x1280.png` | Playlist reorder at 720×1280 |
| `10-queue-detail-720x1280.png` | Queue detail at 720×1280 |
| `11-desktop-locate-1180x780.png` | Desktop top-bar and Songs-header Locate buttons |
