# Android notification actions and lock-screen player

This round adds the KuGou-style favourite and floating-lyrics actions to the
media notification, keeps the previous / play-pause / next group centred, and
adds a full-screen player over the lock screen. It also bumps both builds to
`0.2.1` (Android `versionCode 3`).

## Notification

- The media card shows **heart**, **previous**, **play/pause**, **next** and
  **璇?* controls.
- Android 13+ takes the two custom buttons from
  `MediaSession.setMediaButtonPreferences`; Android 12 and older use
  `LiltMediaNotificationProvider` with the order heart, previous, play/pause,
  next, 璇? The compact view keeps only the three transport buttons.
- The heart reads and writes the same Room favourite state used by the library,
  the now-playing page and backups. The icon switches between outline and
  filled immediately.
- 璇?toggles the existing floating-lyrics overlay. Without the
  display-over-other-apps permission the service shows a short explanation
  instead of silently doing nothing.

On the AOSP Android 15 emulator the system puts play/pause in its large button
and places the two custom actions next to the transport; the notification
action set and order are correct. Phones whose System UI uses the notification
action row (as in the reference screenshot) show the five actions in the
intended heart / previous / play / next / 璇?order.

## Lock-screen player

- `LockScreenActivity` uses `showWhenLocked`, a separate task, and
  `excludeFromRecents`. It does not dismiss or bypass the secure keyguard.
- The layout has the date and clock, album-art background, centred cover,
  title/artist/album, draggable seek bar, repeat and favourite at the sides,
  exactly centred previous/play-pause/next, and a "Swipe up to unlock" hint.
- The playback service starts it on `SCREEN_OFF`/`SCREEN_ON` when a track is
  loaded, playback is not idle/ended, the keyguard is locked, and
  **Settings 鈫?Lock screen 鈫?Show the player on the lock screen** is on.
- Swiping up, tapping the hint, or pressing back finishes the activity and
  reveals the system keyguard. Unlocking with the system (including
  fingerprint/face) is observed through `ACTION_USER_PRESENT` and a keyguard
  poll, and also closes the activity.
- The activity applies the status-bar and navigation-bar insets. It was tested
  with 3-button navigation and gesture navigation.
- Settings includes the switch, the overlay-permission hint, and a
  **Preview lock screen** action that works while the phone is unlocked. The
  switch is stored in the Android backup/restore settings.

## Evidence

| File | What it shows |
|---|---|
| `01-notification.png` | Media card with heart, transport and 璇?|
| `02-notification-favorite.png` | Filled heart after tapping the notification action |
| `03-notification-lyrics.png` | Floating lyrics visible after the 璇?action |
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

## Verification

- Emulator: `lilt35`, Android 15 (API 35), 1080 脳 1920.
- The notification actions were confirmed with `dumpsys media_session`
  (`Favorite` / `Remove favorite` and `Floating lyrics` custom actions).
- The lock screen appeared with the app in the foreground and with the
  launcher in the background.
- `Settings 鈫?Lock screen` off: the system keyguard appears with no Lilt
  overlay. `Preview lock screen` still works.
- Upgraded over the existing 0.2.0 install with `adb install -r`; the 38-song
  library, favourite state, queues and lyrics remained.

```text
PC:      72 passed, 0 failed, 1 ignored
Android: 62 passed, 0 failed
```

```text
dist/Lilt-0.2.1-win64/Lilt.exe        12,085,760 bytes  (PE subsystem 2)
dist/Lilt-0.2.1-win64-portable.zip    18,531,896 bytes
base/app/Lilt-0.2.1-debug.apk         22,115,290 bytes
```
