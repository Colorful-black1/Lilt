# Desktop-lyrics unlock button

## Problem

When PC desktop lyrics were locked, the only way to unlock them was a 30×30 px
floating square with a 13 px lock glyph. On a busy wallpaper it read as a stray
dot, and the click target was small.

## Fix

- The unlock control is now a **96×38 px pill** centred above the lyric line.
- It shows an 18 px unlock icon plus an explicit **Unlock / 解锁** label.
- Hover brightens the pill and the theme-accent border; the cursor becomes a
  pointing hand.
- Locked lyrics reserve 38 px at the top so the larger pill never overlaps the
  first lyric line.
- The separate unlock window remains colour-key transparent and clickable only
  while desktop lyrics are locked.

## Verification

```text
cargo test
# 64 passed, 0 failed, 1 ignored

cargo build --release
# Finished

PE subsystem 2 (no console window)
```

The before screenshot is the previous 30×30 dot. The after screenshot is the new
pill over a live desktop; `03-pill-zoom.png` is a 6× crop showing the unlock
icon and label.

## Evidence

| File | What it shows |
|---|---|
| `01-before.png` | Previous 30×30 unlock dot above locked lyrics |
| `02-after.png` | New 96×38 Unlock pill above locked lyrics |
| `03-pill-zoom.png` | Enlarged view of the icon and label |
| `pc-tests.txt` | PC unit-test result |

## Packaged artifacts

```text
dist/Lilt-0.1.0-win64/Lilt.exe        11,910,144 bytes  (PE subsystem 2)
dist/Lilt-0.1.0-win64-portable.zip    18,464,645 bytes
```

Android is unchanged in this fix.
