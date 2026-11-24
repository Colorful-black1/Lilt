# Android centred player lyrics and full-screen sheet

The now-playing page no longer ends with a lyrics card below the controls. The current and
next lines sit in the middle of the page, between the cover and the title, and tapping the
lyric block opens an immersive lyric sheet.

## What changed

- **Player page.** A `CompactLyrics` block shows the active line in the accent colour and
  the next line in a muted colour, centred under the cover and above the song title. With
  no lyrics it shows a compact "Tap for lyrics" row. Tapping anywhere in the block opens
  the full-screen sheet.
- **Full-screen sheet.** The active line is highlighted with an accent background and
  larger text. The list auto-scrolls so the active line stays in the middle, timed lines
  can be tapped to seek, and the played lines are dimmed. Half-viewport spacers let even
  the first and last lines reach the centre, and soft top/bottom fades hide clipped rows.
  The source (LRCLIB/AI/local) is shown next to the artist. The bottom holds the offset
  controls, seek bar and transport. The pencil in the app bar opens the original lyrics
  editor.
- **Menu.** The overflow menu now has separate **Lyrics** (full-screen view) and **Edit
  lyrics** (editor) entries.
- The sheet follows the current media id, so an automatic track change swaps the title and
  lyrics without leaving the full-screen view.
- The old 180 dp bottom panel and its private `SyncedLyrics` list were removed.

## Evidence

Captured on the `lilt35` emulator (Android 15, 1080×1920, density 420) with the real library
already scanned.

| File | What it shows |
|---|---|
| `01-player.png` | Current/next lyric lines centred between cover and title; no bottom lyric card |
| `02-fullscreen-lyrics.png` | Full-screen sheet with the active line in the middle, source chip, offset controls and transport |
| `03-player-after-back.png` | Returning from the sheet restores the compact centred block and keeps playback position |
| `04-fullscreen-before-next.png` | Sheet open on `Hello` before pressing next |
| `05-fullscreen-after-next.png` | The same sheet follows the next track (`7 Rings`) and shows its own empty lyric state |

## Tests

```text
BackupTest                 7
DuplicateToolsTest         5
FilenameTagsTest           2
LibraryTest               31
MissingFilesTest           2
OnlineLyricsClientTest     2
OnlineLyricsTest           8
PlaybackToolsTest          3
total                     60 passed, 0 failed
```

The APK is built with `gradlew.bat :app:testDebugUnitTest :app:assembleDebug`; Room
migrations and user data are unchanged by this UI-only round.

```text
base/app/Lilt-1.0-debug.apk    22,948,389 bytes
```
