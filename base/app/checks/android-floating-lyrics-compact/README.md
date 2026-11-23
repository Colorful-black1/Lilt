# Android floating lyrics — compact KuGou-style line

## Goal

The old Android overlay was a large black card with a title strip, three lyric
lines, a progress line and transport buttons. The user marked that card as too
large and asked for a KuGou-like overlay that is just one lyric. This round makes
it a compact single-line overlay:

- only the **active lyric line** is shown; no title strip, previous/next line,
  progress bar or transport controls;
- the lyric auto-shrinks to fit one line (18–44 sp maximum from Settings), so long
  lines stay complete;
- **unlocked** mode is a small semi-transparent dark card with a tiny grip and
  lock/close buttons;
- **locked** mode removes the card, grip and buttons completely, leaving only the
  outlined lyric, and the window is click-through. Unlock on the Settings page;
- the lyric is painted with a dark outline, so it stays readable on white pages
  and wallpapers.

Settings now shows an explicit **缩小 / 放大** pair next to the floating lyric size
slider, and the Now Playing page has a **词** quick button that toggles only the
floating overlay's visibility. Size, lock state and accent remain in Settings.

## Implementation

- `base/app/.../playback/FloatingLyricsOverlay.kt`
  - removed `titleStrip`, `beforeText`, `afterText`, `progress` and the transport
    row; the view now contains only a compact header and one `OutlinedTextView`;
  - `applyVisualStyle` switches between a semi-transparent card with header
    (`0xCC0D0B0A`) and a fully transparent, header-less locked state;
  - `setAutoSizeTextTypeUniformWithConfiguration` keeps the lyric on one line;
  - `OutlinedTextView` draws a dark stroke behind the accent fill;
  - the active lyric and grip are both draggable when unlocked.
- `base/app/.../ui/SettingsScreen.kt`
  - font-size sliders have `缩小` / `放大` buttons; opacity keeps a plain slider;
  - copy explains the single-line, transparent-locked behaviour.
- `base/app/.../ui/PlayerScreen.kt`
  - added a `词` quick action bound to the existing `setFloatingLyrics` setting;
  - enabling it without overlay permission shows a message and opens the system
    overlay-permission screen.

## Verification

```text
.\gradlew.bat testDebugUnitTest
# 47 tests, 0 failures

.\gradlew.bat connectedDebugAndroidTest
# Starting 7 tests on lilt35(AVD)
# Finished 7 tests
# BUILD SUCCESSFUL
```

The Room schema was not changed in this round; the seven migration tests still pass.
See `android-tests.txt`.

## Evidence

The screenshots were taken on the Pixel API 35 emulator with the debug APK.

| File | What it shows |
|---|---|
| `01-android-lyrics-unlocked.png` | Compact semi-transparent card with one auto-fitted lyric, grip and lock/close |
| `02-android-lyrics-locked.png` | Locked overlay on a white Chrome page: lyric only, no card or buttons, outlined text |
| `03-android-settings-zoom.png` | Clean Settings screenshot with `缩小` / `放大` beside both font-size sliders; opacity has no zoom buttons |
| `04-android-player-word-button.png` | Now Playing page with the `词` quick button and the overlay hidden |
| `android-tests.txt` | 47 unit tests and 7 migration tests |

The long lyric in the floating-card screenshot is synthetic test data inserted
only on the emulator.

## Packaged artifact

```text
base/app/Lilt-1.0-debug.apk    22,532,463 bytes
```

`package.ps1 all` rebuilt the desktop release and the Android debug APK. The
Android manifest still has no `INTERNET` permission.

The long test lyric and the LRC used for these screenshots are test data inserted
only on the emulator; the player, lyrics editor and backup paths were not changed.

## Limitations

- The overlay is intentionally one line. Extremely long lines shrink to the
  minimum auto-size and then ellipsize; widening the font size in Settings gives
  the line more room before it has to shrink.
- Locked lyrics hide the in-overlay unlock button by design. Unlock with the
  Settings switch, or hide/show the overlay from the player's `词` button.
- Android does not use the network at all; the overlay reads only the local Room
  lyrics row.
