# Now-playing transport alignment

## Problem

On the PC now-playing page the previous and next buttons did not share the same
vertical centre.

The transport row used a plain `ui.horizontal`. egui gives that row an initial
height of 18 px and centres each item inside it. Widgets taller than 18 px are
clamped to the row top, so the first controls stayed at the top while the taller
play button pushed later controls down. The previous button therefore sat 8 px
above the next button.

The old group width was also hard-coded to 268 px while the buttons and gaps
actually took 230 px, so the play button sat about 17 px left of the seek bar
centre.

## Fix

`base/app/src/ui/dialogs.rs` now draws the transport row in a fixed 54 px high
child with `Layout::left_to_right(Align::Center)`, so shuffle, previous, play,
next and repeat share one vertical centre. The group width is calculated from
the five button sizes and the real item spacing, and the remaining width is
split equally on both sides. The play button is now centred on the seek bar.

## Verification

```text
cargo test
# 61 passed, 0 failed, 1 ignored

cargo build --release
# Finished

PE subsystem 2 (no console window)
```

Both screenshots come from the release build with PrintWindow at 1180x780,
using the same one-track test state and the same seek position. `01-before.png`
is the previous package; `02-after.png` is this build.

Pixel measurements, in captured image coordinates (1196x819 including the title
bar):

```text
                         before                 after
seek bar centre x        774.0                  774.0
play button centre x     757.5                  774.5
shuffle centre y         359.5                  369.5
previous centre y        361.5                  369.5
play centre y            369.5                  369.5
next centre y            369.5                  369.5
repeat centre y          369.5                  369.5
```

After the fix all five controls share `y=369.5`, and the play button is within
half a pixel of the seek bar centre. The narrow 720x1280 layout was also
checked: the play button stays centred on the seek bar and all five controls
keep the same centre line.

## Evidence

| File | What it shows |
|---|---|
| `01-before.png` | Previous button 8 px above next; play left of the seek bar centre |
| `02-after.png` | All transport controls on one centre line, play centred |
| `03-narrow-720x1280.png` | Narrow layout; controls stay aligned and centred |
| `alignment.txt` | Measured centres |
| `pc-tests.txt` | PC unit-test result |

## Packaged artifacts

```text
dist/Lilt-0.1.0-win64/Lilt.exe        11,839,488 bytes  (PE subsystem 2)
dist/Lilt-0.1.0-win64-portable.zip    18,440,600 bytes
```

Android is unchanged in this fix.
