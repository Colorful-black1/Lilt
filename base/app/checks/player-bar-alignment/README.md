# Player bar alignment — fixed centre transport

## Goal

Two problems in the bottom player bar:

1. the previous/next buttons were not vertically aligned with the play button;
2. the transport group drifted left or right when the song title/artist length
   changed.

The screenshots show the old behaviour: short titles left the group far from the
window centre, while long titles moved it.

## Changes

- `base/app/src/ui/shell.rs`
  - replaced the old `left_w` / `right_w` estimates with a fixed **three-column**
    layout: a 260 px centre column and two equal side columns.
  - the side width accounts for the two item gaps:
    `side = (width - 260 - gap*2) / 2`.
  - each side column calls `ui.set_width(side)` and the centre column calls
    `ui.set_width(260)`, so the title/artist content can no longer change the
    width used by the next column.
  - the transport group is drawn in a fixed 44 px row with
    `Layout::left_to_right(Align::Center)`, so buttons of different sizes
    (shuffle 28, previous/next 30, play 40) share the same vertical centre.
  - the group width is calculated from the actual button sizes and item spacing;
    the remaining space is split equally on both sides, placing the play button at
    the centre of the 260 px column and therefore the window.

## Verification

```text
cargo test
# 46 passed, 0 failed

cargo build --release
# Finished
```

The screenshots were captured from the release build with PrintWindow, using a
synthetic two-track library: one short title (`Baby`) and one very long title.
The two runs are otherwise identical.

Pixel measurements from `alignment.txt`:

```text
short title: play x=597.5  previous y=749.5  next y=749.5
long title:  play x=597.5  previous y=749.5  next y=749.5
```

The window is 1196 px wide, so the centre is x=598. The play button stays within
half a pixel of the centre for both titles, and previous/next share the same
vertical centre as play.

## Evidence

| File | What it shows |
|---|---|
| `01-player-bar-short-title.png` | Short title; transport fixed at the window centre |
| `02-player-bar-long-title.png` | Long title; play button stays at the same x and previous/next stay aligned |
| `alignment.txt` | Measured centres for both screenshots |
| `pc-tests.txt` | 46-test result |

The title/artist text in the bar is intentionally truncated to the fixed left
column; the full long title is visible in the library row above.

## Packaged artifacts

```text
dist/Lilt-0.1.0-win64/Lilt.exe        11,210,752 bytes  (PE subsystem 2)
dist/Lilt-0.1.0-win64-portable.zip    18,197,805 bytes
```

Android is unchanged in this round.
