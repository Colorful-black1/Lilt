# Desktop lyrics: unbounded proportional zoom while unlocked

## Goal

The PC desktop-lyrics window could be resized, but only the empty card area
changed: the lyric font stayed at the configured maximum, and the toolbar
buttons stayed at 44/38 px. The window also stopped at 460×160 and 3600×760.
This round makes the unlocked overlay a genuinely zoomable lyric window:

- the current line, the next line and the line gap scale with the window;
- the toolbar buttons, icons and spacing scale with the same factor;
- the card padding, rounded corners and window region scale with it;
- scrolling the mouse wheel over the unlocked window zooms both axes by one
  factor, so the window keeps its current proportions;
- there is no fixed minimum or maximum size; manual resizing and wheel zoom can
  go below 460×160 and above 3600×760 (only a one-pixel native floor remains);
- long lines still shrink to fit, so no lyric is clipped or wrapped;
- locking keeps the size that was chosen while unlocked.

## Implementation

- `base/app/src/ui/companion.rs`
  - `lyrics_scale(inner_height)` treats the existing 900×190 window as the
    design size. The scale is `height / 190` with no user-facing cap, so the
    Settings size slider remains the baseline at the default window size.
  - The active line size is `desktop_lyrics_font_size * scale`; the next line
    keeps its `0.88` ratio and the line gap scales with the same factor.
  - The toolbar uses a scaled button size, scaled icon sizes, scaled spacing,
    scaled pill padding and a scaled pill radius. A width cap only shrinks the
    buttons when the window is made unusually narrow, so no control is pushed
    outside the card.
  - Card inset, inner padding, grip, header and rounded corners all use the
    same scale. Insets are additionally clamped to a fraction of the current
    card so an extreme edge drag cannot create an inverted rectangle.
  - `zoomed_lyrics_size(size, scroll)` applies one factor to width and height
    with no maximum; it only stops at a 1×1 technical floor.
  - The standalone unlock pill scales with the lyric window as well, including
    its label, icon and corner radius.
  - Locked mode draws no card or toolbar and receives no zoom input, but keeps
    the current scale so locking does not snap back to the baseline.
- `base/app/src/desktop.rs`
  - The rounded window region now scales its 6/5-point margins and 18-point
    radius with the lyric scale, so the visible card and the native clip match
    at every size.
  - `WM_GETMINMAXINFO` sets the minimum tracking size to 1×1; the previous
    460×160 limit no longer applies to mouse resizing.
  - The resize hit band scales with the card too, with a small physical floor
    so it remains grabbable at tiny sizes.
- `base/app/src/ui/pages.rs`
  - Settings copy now says the lyrics and buttons scale together and that there
    is no fixed minimum or maximum.
- No new settings field, no data migration and no change to the size slider
  range. The window geometry is still remembered for the current session only,
  as before.

## Why height drives the scale

The overlay is a wide two-line card. Its width already has a dedicated
fit-to-width path in `fit_font_size`, so using width in the scale would shrink
long lines twice. Height is the dimension that represents the lyric size, and
the wheel gesture changes both axes together for a true proportional zoom.
Width-only edge drags still change how much text fits, but use the wheel or a
corner/top/bottom drag when you want to change the size.

## Verification

```text
cargo test
# running 71 tests
# test result: ok. 70 passed; 0 failed; 1 ignored

cargo build --release
# Finished
```

Updated unit tests:

- `lyric_scale_follows_window_height_without_caps`
- `scroll_zooms_both_axes_by_one_factor_without_caps`

Native wheel check (`wheel-zoom.txt`), after resetting the window to 900×190:

```text
wheel up 1:    954x201
wheel up 2:   1012x212
wheel up 3:   1073x224

wheel down 1: 1010x212
wheel down 2:  952x201
wheel down 3:  897x188
```

Both axes move by the same factor on every notch. The text and controls are
derived from the height, so these measurements also exercise the control zoom.

The `200×60` screenshot proves that the old 460×160 limit is gone: the card,
toolbar icons and lyric text are all reduced together instead of clipping.
`unbounded-size.txt` records the native checks:

```text
WM_GETMINMAXINFO min track = 1x1, max track = 100000x100000
SetWindowPos 200x60       -> 200x60
WM_MOUSEWHEEL +120 x4     -> 3808x804 (above the old 3600x760 maximum)
```

## Evidence

| File | What it shows |
|---|---|
| `01-unlocked-900x190-base.png` | Baseline 900×190 window; 34 px baseline lyric size |
| `02-unlocked-1350x285-1.5x.png` | 1.5× window height; lines, gap, buttons, icons and padding all scale |
| `03-unlocked-460x160-small.png` | A small 460×160 window; all components scale down together |
| `04-unlocked-200x60-tiny.png` | Below the old minimum; the whole card and its controls remain proportional |
| `unbounded-size.txt` | 1×1 tracking minimum, 200×60 accepted and wheel zoom above the old maximum |
| `wheel-zoom.txt` | Native `WM_MOUSEWHEEL` check with equal width/height factors |
| `pc-tests.txt` | PC unit-test summary |

The screenshots use a neutral desktop-like background and the release build.

## Packaged artifacts

```text
dist/Lilt-0.1.0-win64/Lilt.exe        11,929,088 bytes  (PE subsystem 2)
dist/Lilt-0.1.0-win64-portable.zip    18,472,679 bytes
```

## Limitations

- Resizing only the left or right edge changes available width but not the
  lyric height; drag the top/bottom edge, a corner, or use the wheel to zoom.
- The one-pixel floor is a native-window requirement, not a design limit.
- Desktop-lyrics window position and size still last for the current session
  only; persisting them across restarts is a separate change.
