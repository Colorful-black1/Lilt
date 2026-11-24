# Desktop lyrics: resize border and cursor while unlocked

## Problem

The desktop lyric window is frameless. Winit expands its client area over the
whole window, so `DefWindowProc` returned `HTCLIENT` even on the visible card
border. Windows therefore never offered the usual horizontal, vertical or
diagonal resize cursor, and the only way to resize was to know that an
invisible border existed.

The rounded window region makes this worse: the transparent 6/5-point margin
outside the card is clipped from the window, so a resize zone measured from the
raw window edge is only about two pixels wide and is almost impossible to hit.

## Fix

`base/app/src/desktop.rs` now supplies the missing hit test itself:

- `lyrics_window_proc` handles `WM_NCHITTEST` while the overlay is unlocked.
- `lyrics_resize_hit` reads the screen coordinates from `lparam`, gets the
  window rect and DPI, and calls the pure `resize_hit_code` helper.
- The border begins at the rounded region edge and extends eight points into
  the card: `6 + 8` points horizontally and `5 + 8` points vertically, scaled by
  the monitor DPI. The visible card edge therefore shows the standard resize
  cursor on every side and in every corner.
- The returned codes are the normal `HTLEFT`, `HTRIGHT`, `HTTOP`, `HTBOTTOM`
  and corner codes, so Windows handles the actual sizing drag and loads
  `IDC_SIZEWE`, `IDC_SIZENS` or `IDC_SIZENWSE` without custom cursor painting.
- Locked lyrics still return `HTTRANSPARENT`, so click-through is unchanged.
- The region margin constants are shared with `apply_rounded_region`, so moving
  the card edge later cannot silently break the resize hit test.

A regression test checks all eight border directions, the centre and points
outside the window.

## Verification

Native hit-test and cursor probe on the release build at 900×190:

```text
left edge        hit=HTLEFT         cursor=SIZEWE    window=lyrics
right edge       hit=HTRIGHT        cursor=SIZEWE    window=lyrics
top edge         hit=HTTOP          cursor=SIZENS    window=lyrics
bottom edge      hit=HTBOTTOM       cursor=SIZENS    window=lyrics
top-left         hit=HTTOPLEFT      cursor=SIZENWSE  window=lyrics
bottom-right     hit=HTBOTTOMRIGHT  cursor=SIZENWSE  window=lyrics
center           hit=HTCLIENT       cursor=ARROW     window=lyrics
```

A real mouse drag on the bottom-right border changed the window from
`900x190` to `960x230`, proving that the standard sizing loop runs, not just
that the cursor changes.

```text
cargo test
# running 71 tests
# test result: ok. 70 passed; 0 failed; 1 ignored
```

## Evidence

| File | What it shows |
|---|---|
| `01-resize-border-zones.png` | Annotated unlocked card: the 8-point band inside the visible rounded edge |
| `resize-hit-test.txt` | Native `WM_NCHITTEST` codes, cursor handles and drag result |
| `pc-tests.txt` | PC unit-test summary including the new hit-test regression |

### Hover target

The cursor appears when the pointer is inside the **visible card edge**, within
about 8 points of it. The outer transparent halo (6 points horizontally, 5
vertically) belongs to the rounded window region and passes clicks through to
the desktop, so it intentionally has no resize cursor.

## Packaged artifacts

```text
dist/Lilt-0.1.0-win64/Lilt.exe        11,924,992 bytes  (PE subsystem 2)
dist/Lilt-0.1.0-win64-portable.zip    18,471,090 bytes
```

## Limitations

- The border is a native hit-test band, not a drawn grip. The card keeps its
  clean KuGou-style look; the standard Windows cursor is the affordance.
- Width-only resizing changes how much text fits but not the lyric height.
  Drag the top/bottom edge, a corner, or use the wheel to scale the text.
- The transparent outer halo remains click-through by design.
