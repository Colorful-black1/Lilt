# Lyric progress colours — PC desktop lyrics and Android floating lyrics

## Goal

KuGou-style progress colouring for the lyric overlay: the part of the active line
that has already been sung uses one colour, the remaining part uses another, and
both colours can be customised in Settings.

## Behaviour

### PC desktop lyrics

- The active line fills from left to right using its LRC start time and the next
  non-empty line time. A long instrumental gap is capped at eight seconds so the
  line does not crawl for half a minute.
- The already-played part uses **Played lyric color**; the remaining part and the
  next line use **Remaining lyric color** (the next line at lower opacity).
- The fill is clipped in the same galley, so the existing outline, long-line
  shrinking, offset and opacity rules do not change.
- Defaults: Played follows the theme accent; Remaining is warm white. Both rows
  have a reset button and a live two-colour preview in
  **Settings → Desktop integration**.

### Android floating lyrics

- `floatingLyricProgress()` uses the same LRC line window and 8-second cap.
- The service sends the fraction with each overlay state. While playing the
  overlay refreshes every 50 ms; while paused it falls back to 250 ms.
- `OutlinedTextView` paints one fill pass with a hard-stop `LinearGradient`, so
  the partial fill is visible on all Android versions without a second layout.
- Defaults: Played follows the app accent; Unplayed is warm white. Both rows have
  a live preview and a hue / saturation / brightness colour dialog in
  **Settings → Floating lyrics**.

### Persistence

- PC: `desktop_lyrics_played_color` and `desktop_lyrics_unplayed_color` are
  optional RGB triples in the state file and backup. `None` means the defaults.
- Android: `floating_played_color` and `floating_unplayed_color` are optional
  opaque ARGB ints in SharedPreferences and the JSON backup.
- Old state files and old backups without those keys decode to the defaults. No
  Room schema change was needed.

## Verification

```text
PC:      cargo test -> 64 passed, 0 failed, 1 ignored
Android: :app:testDebugUnitTest -> 57 tests, 0 failures
PC:      cargo build --release -> Finished
PC:      PE subsystem 2 (no console window)
Android: :app:assembleDebug -> BUILD SUCCESSFUL
```

PC screenshots use the same synthetic LRC line at 6.0 s with the next line at
8.0 s, paused at 7.5 s (75% fill). The custom screenshot uses a cyan played
colour and warm-white unplayed colour.

Android screenshots use the existing `Hello` lyrics. The default screenshot was
taken around 67% through a timed line; the custom screenshot was paused halfway
through `But when I call, you never seem to be home`. The settings screenshot
shows the two swatches, reset buttons and live preview; the dialog screenshot
shows the HSV picker.

## Evidence

| File | What it shows |
|---|---|
| `01-pc-progress-default.png` | PC desktop lyrics at 75%: accent fill, warm-white remainder, dim next line |
| `02-pc-progress-custom.png` | PC custom cyan/warm-white colours |
| `03-pc-settings-colors.png` | PC Settings → Desktop integration colour rows and preview |
| `04-android-progress-default.png` | Android floating lyric with default accent/warm-white progress fill |
| `05-android-progress-custom.png` | Android custom cyan/warm-white fill, paused mid-line |
| `06-android-settings-colors.png` | Android Settings → Floating lyrics colour rows and preview |
| `07-android-color-dialog.png` | Android hue/saturation/brightness colour picker |
| `pc-tests.txt` | PC unit-test result |
| `android-tests.txt` | Android unit-test result |
| `android-permissions.txt` | APK permission dump; no permission added for colours |

## Packaged artifacts

```text
dist/Lilt-0.1.0-win64/Lilt.exe        11,908,096 bytes  (PE subsystem 2)
dist/Lilt-0.1.0-win64-portable.zip    18,464,250 bytes
base/app/Lilt-1.0-debug.apk           22,926,572 bytes
```

The Android APK uses the standard debug key and still only adds `INTERNET` and
`ACCESS_NETWORK_STATE` for online lyrics. No new permission is needed for the
colour overlay.
