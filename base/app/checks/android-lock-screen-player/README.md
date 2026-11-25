# Android notification actions, lock-screen player and shuffle

This round adds the KuGou-style favourite and floating-lyrics actions to the
media notification, keeps the previous / play-pause / next group centred, adds
a full-screen player over the lock screen, and adds shuffle / repeat controls
plus synced lyrics to that player. The current build is `0.2.2`
(Android `versionCode 4`); the first version of this round was `0.2.1`.

## Notification

- The media card shows **heart**, **previous**, **play/pause**, **next** and
  **词** controls.
- Android 13+ takes the two custom buttons from
  `MediaSession.setMediaButtonPreferences`; Android 12 and older use
  `LiltMediaNotificationProvider` with the order heart, previous, play/pause,
  next, 词. The compact view keeps only the three transport buttons.
- The heart reads and writes the same Room favourite state used by the library,
  the now-playing page and backups. The icon switches between outline and
  filled immediately.
- 词 toggles the existing floating-lyrics overlay. Without the
  display-over-other-apps permission the service shows a short explanation
  instead of silently doing nothing.

On the AOSP Android 15 emulator the system puts play/pause in its large button
and places the two custom actions next to the transport; the notification
action set and order are correct. Phones whose System UI uses the notification
action row (as in the reference screenshot) show the five actions in the
intended heart / previous / play / next / 词 order.

## Lock-screen player

- `LockScreenActivity` uses `showWhenLocked`, a separate task, and
  `excludeFromRecents`. It does not dismiss or bypass the secure keyguard.
- The layout has the date and clock, album-art background, centred cover,
  title/artist/album, a lyric window, draggable seek bar, and exactly centred
  previous/play-pause/next. **Shuffle** is on the left of the transport,
  **repeat** is on the right, and the **heart** sits to the right of the title
  block. This mirrors the in-app player and keeps the centre group balanced.
- Shuffle toggles `Player.shuffleModeEnabled` on the same Media3 session used by
  the app. Its icon turns to the accent colour while active; repeat cycles
  through off, all and one, and the heart reads/writes the shared Room
  favourite state.
- Saved LRC lyrics appear between the title and the seek bar. The five-line
  window highlights the current line, keeps it in the middle, and slides up one
  line as playback advances. A long current line wraps to two lines and is
  measured before the window is positioned; when the lyric area is short, a
  three-line window is used so the controls never overflow.
- The lock screen only reads lyrics already saved in Room. It never starts an
  online lookup, records history or changes the lyric offset. Pausing or
  dragging the seek bar refreshes the highlighted line.
- The playback service starts it on `SCREEN_OFF`/`SCREEN_ON` when a track is
  loaded, playback is not idle/ended, the keyguard is locked, and
  **Settings → Lock screen → Show the player on the lock screen** is on.
- Swiping up, tapping the hint, or pressing back finishes the activity and
  reveals the system keyguard. Unlocking with the system (including
  fingerprint/face) is observed through `ACTION_USER_PRESENT` and a keyguard
  poll, and also closes the activity.
- The activity applies the status-bar and navigation-bar insets. It was tested
  with 3-button navigation and gesture navigation, and with a 720 × 1280
  override at 320 dpi (360 × 640 dp).
- Settings includes the switch, the overlay-permission hint, and a
  **Preview lock screen** action that works while the phone is unlocked. The
  switch is stored in the Android backup/restore settings.

## Evidence

| File | What it shows |
|---|---|
| `01-notification.png` | Media card with heart, transport and 词 |
| `02-notification-favorite.png` | Filled heart after tapping the notification action |
| `03-notification-lyrics.png` | Floating lyrics visible after the 词 action |
| `04-lock-screen-3button.png` | Lock-screen player with 3-button navigation |
| `05-lock-screen-gesture.png` | Lock-screen player with gesture navigation |
| `06-system-keyguard.png` | System keyguard after swiping up from the Lilt lock screen |
| `07-lock-screen-paused.png` | Pause action changes the button to play |
| `08-lock-screen-next.png` | Next track updates cover, title, favourite and duration |
| `09-lock-screen-controls.png` | Favourite, repeat and seek from the lock screen |
| `10-settings-lock-screen.png` | Settings card, switch, permission hint and preview |
| `11-settings-preview.png` | Preview opened while the phone is unlocked |
| `12-settings-off.png` | Lock-screen switch turned off |
| `13-system-lock-screen-setting-off.png` | With the switch off, the system lock screen appears directly |
| `14-lock-screen-background.png` | Lilt lock screen appears while the launcher is the background app |
| `15-player-centered.png` | In-app previous / play / next group exactly centred |
| `16-lyrics-player-centered.png` | Full-screen lyrics transport exactly centred |
| `17-lock-screen-lyrics.png` | Five-line lyric window with the current line centred at 4:21 |
| `18-lock-screen-lyrics-compact.png` | Three-line lyric window on a 360 × 640 dp screen, with shuffle and repeat at the sides |
| `19-lock-screen-shuffle.png` | Shuffle toggled on and highlighted in the lock-screen transport |

## Verification

- Emulator: `lilt35`, Android 15 (API 35), 1080 × 2400 (physical), also tested
  with a 720 × 1280 / 320 dpi override (360 × 640 dp).
- The notification actions were confirmed with `dumpsys media_session`
  (`Favorite` / `Remove favorite` and `Floating lyrics` custom actions).
- The lock screen appeared with the app in the foreground and with the
  launcher in the background.
- With "Hello" playing from the launcher, the lock screen showed the saved LRC
  lines; the highlighted line advanced at 0:24, 0:33, 1:42, 2:41, 2:49,
  3:05 and 4:21. Seeking to 2:41 and pausing at 1:42 kept the correct line
  centred. The long line "I was wondering if after all these years you'd like
  to meet" wrapped to two lines without being clipped.
- Tapping shuffle changed the icon from white to the accent colour; tapping it
  again restored it. Opening the app afterwards showed the same shuffle state.
  Repeat still cycles off / all / one, and the heart still toggles the current
  song's Room favourite from its new position next to the title.
- A 720 × 1280 / 320 dpi override gave a 360 × 640 dp layout with a three-line
  window; shuffle, repeat, the heart, clock, cover, title, lyrics, seek bar
  and transport all fit without overlap.
- `Settings → Lock screen` off: the system keyguard appears with no Lilt
  overlay. `Preview lock screen` still works.
- Upgraded over the existing 0.2.0 install with `adb install -r`; the 38-song
  library, favourite state, queues and lyrics remained.
- The lock screen does not perform an online lyrics lookup; tracks without
  saved lyrics show "暂无歌词" until lyrics are added in the app.

```text
PC:      73 passed, 0 failed, 1 ignored
Android: 65 passed, 0 failed
```

```text
dist/Lilt-0.2.2-win64/Lilt.exe        12,055,552 bytes  (PE subsystem 2)
dist/Lilt-0.2.2-win64-portable.zip    18,515,751 bytes
base/app/Lilt-0.2.2-debug.apk         22,148,381 bytes
```
