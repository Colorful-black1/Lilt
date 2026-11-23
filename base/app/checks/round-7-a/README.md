# Round 7-A checks — Android floating lyrics and home-screen widget

Produced on the Android 15 (API 35) emulator `lilt35` at 1080×1920, 420 dpi, and
720×1280, 320 dpi for the narrow layout. The desktop build was not changed in
this round.

## Build and tests

```text
cd base/app/android
.\gradlew.bat testDebugUnitTest          # 30 passed, 0 failed
.\gradlew.bat connectedDebugAndroidTest  # 6 migration tests passed
.\gradlew.bat assembleDebug              # BUILD SUCCESSFUL

cd ..\..
.\package.ps1 apk                         # Lilt-1.0-debug.apk, 21,894,114 bytes
```

New unit coverage:

- `floatingLyricLines` keeps the previous/current/next window around the timed
  line, returns a preview before the first tag, and applies the per-song offset.
- Plain-text lyrics show the first lines without pretending to be timed; empty
  documents return empty strings.
- Floating font size uses 0.5 sp steps between 18 and 44 sp.
- Widget progress is bounded to 0..1000 and the `m:ss / m:ss` label handles
  unknown durations and hour-long tracks.
- Accent ids map to the native ARGB colours used by the overlay and widget.

The existing Room migration suite (6 tests) still passes; this round adds no
schema changes.

## Implementation

### Floating lyrics

- `PlaybackService` owns the overlay. It already runs as the media
  foreground service, so no second foreground service, notification or
  `FOREGROUND_SERVICE_SPECIAL_USE` permission was added.
- `FloatingLyricsOverlay` uses `WindowManager` with
  `TYPE_APPLICATION_OVERLAY`, a rounded translucent card and a
  `SYSTEM_ALERT_WINDOW` app-op granted by the user from Settings. The switch
  opens `ACTION_MANAGE_OVERLAY_PERMISSION` when the permission is missing.
- The card shows the song/artist strip, previous line, active line (accent),
  next line, a thin progress bar and a transport row (previous, play/pause,
  next, open app). The header has lock and close buttons.
- Lyrics come from the same Room `lyrics` row as the player. A Room Flow updates
  the document if the user edits the lyrics while the overlay is visible. The
  existing per-song `offsetMs` is applied through `floatingLyricLines`; the
  global lyric opacity and the new floating font size are reused.
- Dragging the header updates `floating_x/floating_y`; the position is restored
  when the service/window is recreated. The card is kept 24 dp from the top and
  28 dp above the bottom edge so the controls stay above the status and
  navigation bars. A display-size, split-screen or rotation change re-fits the
  width and position.
- Locking hides the header, transport and progress and adds
  `FLAG_NOT_TOUCHABLE`; the whole card becomes click-through. It is unlocked in
  **Settings → Floating lyrics → Lock position and controls**.
- Closing the card sets `floating_lyrics=false`, removes the window and leaves
  playback running.

### Home-screen widget

- `LiltWidgetProvider` exposes a RemoteViews card with cover art, title,
  artist, elapsed/total time, progress and previous/play-pause/next buttons.
  Tapping the card opens the app.
- The playback service pushes state on player events and once a second while
  playing; progress therefore advances without polling the media session from
  the launcher.
- `WidgetActionReceiver` connects a short-lived `MediaController` for the three
  transport actions, so the widget and the app always share the same
  `MediaSession`. Previous follows the same ten-second restart rule as the app.
- Album art is decoded off the main thread with a 256 px thumbnail, with a
  rounded music-note placeholder when a track has no art. The last snapshot is
  cached in the process so a widget added while paused shows the current track
  instead of an idle card.
- Settings offers **Add home screen widget** through
  `AppWidgetManager.requestPinAppWidget`; if the launcher does not support
  pinning, the app shows a short instruction instead.

## Behaviour checks

- Enabling **Show over other apps** created a `TYPE_APPLICATION_OVERLAY` window
  (`appop=SYSTEM_ALERT_WINDOW`) with wrapped height 441 px and
  `fl=NOT_FOCUSABLE NOT_TOUCH_MODAL LAYOUT_IN_SCREEN`.
- A seeded timed LRC for Glass Horizon showed `We fade into the light` as the
  previous line and `Glass horizon, open wide` as the active line at 18 s while
  the widget showed the same track at `0:18 / 0:20`.
- Locking wrote `floating_lyrics_locked=true`, changed the window flags to
  `NOT_FOCUSABLE NOT_TOUCHABLE NOT_TOUCH_MODAL LAYOUT_IN_SCREEN` and reduced the
  requested height to 234 px. The locked capture shows only the title strip and
  lyrics. Unlocking in Settings removed `NOT_TOUCHABLE` and restored the
  transport row.
- Dragging the header changed the saved `floating_x/floating_y`; the position
  survived service recreation and the display-size change. Re-fitting after a
  1080×2400 → 1080×1920 change moved the card to `y=1479` (the previous value
  was 1561), keeping the controls inside the screen.
- The widget was pinned from Settings; `dumpsys appwidget` showed provider
  `LiltWidgetProvider` and bound id 4. Its play/pause and previous actions drove
  the same MediaSession; the time label advanced from `0:01` to `0:04` in three
  seconds while playing.
- The final settings card is scrollable at 1080×1920 and 720×1280 without
  clipped controls. The narrow capture uses the same card and slider widths as
  the wide one.
- `aapt dump permissions` lists `SYSTEM_ALERT_WINDOW` and no `INTERNET`
  permission. The APK contains the provider, action receiver, widget layout,
  widget info XML and overlay/ widget vector icons.

## Evidence files

| File | What it shows |
|---|---|
| `01-floating-lyrics-home-1080x1920.png` | Final APK: widget plus floating timed lyrics over the home screen |
| `02-floating-lyrics-locked-1080x1920.png` | Final APK: locked lyrics; no controls or transport |
| `03-settings-floating-1080x1920.png` | Floating lyrics card with overlay, enable switch on |
| `04-settings-floating-720x1280.png` | Narrow settings layout, no clipped controls |
| `05-widget-pin-preview.png` | Launcher pin preview for the Lilt widget |
| `06-home-widget-1080x1920.png` | Widget cropped from the final home-screen capture |
| `overlay-window.txt` | Unlocked/locked window flags, sizes and position notes |
| `media-session.txt` | Glass Horizon paused at 18.088 s in the shared MediaSession |
| `appwidget.txt` | Widget provider and bound widget id |
| `settings-prefs.txt` | Floating lyrics position and lock preferences |

## Limitations

- Some apps (for example, YouTube in this emulator) set
  `HIDE_NON_SYSTEM_OVERLAY_WINDOWS`; Android hides all third-party overlays over
  those apps. This is a platform security rule, not a Lilt error.
- Locked lyrics can only be unlocked from Settings; the click-through window
  intentionally ignores its own touches.
- The widget uses a placeholder when the track has no art. Real MediaStore art
  is loaded asynchronously.
- Android lock-screen lyrics, a lyric-only home-screen widget, Android Auto, a
  quick-settings tile and a media-button customisation remain for later rounds.
- The overlay is a rounded translucent card, not a per-pixel transparent or
  blurred window.
