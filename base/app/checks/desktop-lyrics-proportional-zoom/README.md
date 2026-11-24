# Desktop lyrics: proportional zoom while unlocked

## Goal

The PC desktop-lyrics window could be resized, but only the empty card area
changed: the lyric font stayed at the configured maximum until a long line had
to shrink. This round makes the unlocked overlay behave like a zoomable lyric
window:

- the current line and the next line scale together with the window height;
- scrolling the mouse wheel over the unlocked window zooms both axes by one
  factor, so the window keeps its current proportions;
- long lines still shrink to fit, so no lyric is ever clipped or wrapped;
- locking keeps the size that was chosen while unlocked.

## Implementation

- `base/app/src/ui/companion.rs`
  - `lyrics_scale(inner_height)` treats the existing 900×190 window as the
    design size. The scale is `height / 190`, clamped to `0.6..4.0`, so the
    Settings size slider remains the baseline at the default window size.
  - The active line size is `desktop_lyrics_font_size * scale`; the next line
    keeps its `0.88` ratio and the line gap scales with the same factor.
  - `zoomed_lyrics_size(size, scroll)` applies one factor to width and height.
    The minimum is 460×160; the maximum is 3600×760. It is called only while
    the overlay is unlocked and only for scroll input delivered to the lyric
    viewport.
  - Locked mode draws no card or toolbar and receives no zoom input, but keeps
    the current scale so locking does not snap the text back to the baseline.
- `base/app/src/ui/pages.rs`
  - Settings copy now explains that the unlocked window can be dragged taller
    or shorter, or scrolled over, to scale the lyrics.
- No new settings field, no data migration and no change to the size slider
  range. The window geometry is still remembered for the current session only,
  as before.

## Why height drives the scale

The overlay is a wide two-line card. Its width already has a dedicated
fit-to-width path in `fit_font_size`, so using width in the scale would shrink
long lines twice. Height is the dimension that represents the lyric size, and
the wheel gesture changes both axes together for a true proportional zoom.

## Verification

```text
cargo test
# running 70 tests
# test result: ok. 69 passed; 0 failed; 1 ignored

cargo build --release
# Finished
```

New unit tests:

- `lyric_scale_follows_window_height_and_stays_bounded`
- `scroll_zooms_both_axes_by_one_factor_and_clamps`

Native wheel check (`wheel-zoom.txt`), after resetting the window to 900×190:

```text
wheel up 1:    954x201
wheel up 2:   1012x212
wheel up 3:   1073x224

wheel down 1: 1010x212
wheel down 2:  952x201
wheel down 3:  897x188
```

Both axes move by the same factor on every notch. The font is derived from the
height, so these measurements also prove the text zoom path is exercised.

## Evidence

| File | What it shows |
|---|---|
| `01-unlocked-900x190-base.png` | Baseline 900×190 window; 34 px baseline lyric size |
| `02-unlocked-1350x285-1.5x.png` | 1.5× window height; both lyric lines and the gap scale together |
| `03-unlocked-460x160-min.png` | Minimum window size; text stays inside the card and long lines fit |
| `wheel-zoom.txt` | Native `WM_MOUSEWHEEL` check with equal width/height factors |
| `pc-tests.txt` | PC unit-test summary |

The screenshots use a neutral desktop-like background and the release build.
The window is at 900×190, 1350×285 and 460×160 respectively.

## Packaged artifacts

```text
dist/Lilt-0.1.0-win64/Lilt.exe        11,939,328 bytes  (PE subsystem 2)
dist/Lilt-0.1.0-win64-portable.zip    18,472,292 bytes
```

## Limitations

- Resizing only the left or right edge changes available width but not the
  lyric scale; drag the top/bottom edge, a corner, or use the wheel to zoom.
  A follow-up adds the native resize border and cursors: see
  `../desktop-lyrics-resize-border/`.
- The toolbar keeps its 44/38 px target size while the lyrics scale, so the
  controls stay easy to hit at every zoom level.
- Desktop-lyrics window position and size still last for the current session
  only; persisting them across restarts is a separate change.
