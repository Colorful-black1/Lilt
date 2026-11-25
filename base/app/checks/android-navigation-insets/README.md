# Android navigation-bar and mini-player insets

The app calls `enableEdgeToEdge()`, so its content is laid out behind the system bars.
The mini player previously drew its play/next buttons in the bottom navigation area. With
3-button navigation those buttons overlapped Back/Home/Recents and were easy to mis-tap.

## What changed

- `MiniPlayer` now applies `WindowInsets.navigationBars` before the clickable content. The
  bar background can still reach the screen edge, but all buttons and labels sit above the
  system navigation area.
- The inset is mode-aware: gesture navigation reserves only the gesture-handle strip,
  while 3-button navigation reserves the taller button bar. No fixed height is hard-coded.
- `PlayerScreen` and `LyricsPlayerScreen` apply both `WindowInsets.statusBars` and
  `WindowInsets.navigationBars`, so their bottom transport controls also stay above the
  system navigation keys.
- The same `MiniPlayer` is used by Home, album and artist screens, so all three get the
  fix automatically.

## Evidence

Captured on the `lilt35` emulator (Android 15, 1080閼?920, density 420). The navigation mode
was switched between gesture and 3-button overlays during the test.

| File | What it shows |
|---|---|
| `01-home-gesture-nav.png` | Home mini player above the gesture pill; no overlap |
| `02-home-three-button-nav.png` | Home mini player above Back/Home/Recents; buttons remain fully tappable |
| `03-player-three-button-nav.png` | Player controls stay above the 3-button navigation bar |

## Tests

```text
Android unit tests: 60 passed, 0 failed
APK: 22,705,330 bytes
```

The navigation inset comes from the platform, so it follows the user's chosen navigation
mode without a separate setting.
