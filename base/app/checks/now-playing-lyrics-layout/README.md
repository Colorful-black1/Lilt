# Lyrics-first PC now-playing page

The now-playing overlay is no longer a cover block with a 180 px lyric card squeezed under
it. It is a two-column page: cover and song facts on the left, a large lyric sheet on the
right, and one transport bar along the bottom.

## What changed

- The right pane uses the full body height (roughly ten lyric lines at 1180×780), so the
  reading area is much larger than the old two-line card.
- The lyric sheet and the transport controls have **no card fill or border**. They sit
  directly on the cover-tinted page background, so the accent and text colours follow the
  rest of the theme.
- A **full-window progress line** is the divider between the lyrics and the bottom
  controls. The played portion uses the accent colour, the rest a faint neutral line; the
  time labels and buttons sit below it. The lyric scrollbar is hidden.
- The quick actions sit at the two outer edges, leaving the transport group exactly in the
  centre: shuffle, previous, play/pause, next and repeat never move horizontally. Wide
  windows use full labels; medium windows switch to short labels (A-B, 本曲后停止, 均衡,
  定时, 桌面, 标签, 1.00×); only the narrowest windows use icon buttons. No label is clipped
  at the tested 1045/900/720 px widths.
- Lyric lines are centred horizontally. The active line is centred vertically in the pane
  with `scroll_to_rect(..., Align::Center)` whenever the highlighted line changes, so the
  sheet follows playback.
- Played lines use the configured desktop-lyric played colour, upcoming lines use the
  unplayed colour, and the active line is larger with a short accent underline. Clicking a
  timed line seeks to it.
- The pane header has **Lyrics / Details** tabs. Details keeps Up next, file information
  and **Show in folder**.
- The bottom row places the five transport buttons exactly in the centre. The A-B loop,
  stop-after, equalizer, sleep timer, desktop lyrics, tags and speed shortcuts sit at the
  outer edges; below 1180 px they become compact icon buttons so the centre never shifts.
- With no lyrics, the pane shows the LRCLIB/AI status, retry, candidate and AI actions in
  the same large reading area.

## Evidence

| File | What it shows |
|---|---|
| `00-before-1180x780.png` | Old layout: 180 px lyric card under the controls, two visible lines |
| `01-after-1180x780.png` | New two-column page at the same size |
| `02-after-1180x780-seek80.png` | At 1:33 the current line is underlined near the middle; played lines use the played colour; quick actions flank the centred transport above the progress line |
| `03-after-900x600.png` | 900×600 uses icon-only side actions; previous/play/next remain centred |
| `04-after-720x540.png` | Minimum window keeps the transport centred and all controls unclipped |
| `05-details-tab.png` | Details tab with Up next and the file card |
| `06-compact-1045x650.png` | Medium window uses short but complete labels; transport stays centred and nothing is cut off |

The `02` capture uses `LILT_START_PAGE=now`, `LILT_LYRICS=1`, `LILT_AUTOPLAY=1` and
`LILT_SEEK=80` against a copied test state, then PrintWindow at 1180×780.

## Tests

```text
running 71 tests
test result: ok. 70 passed; 0 failed; 1 ignored
```

The release EXE keeps Windows GUI subsystem 2 (no console window). The overlay code lives
in `base/app/src/ui/now_playing.rs`; `dialogs.rs` delegates to it so the existing modal
and action plumbing stays unchanged.

```text
dist/Lilt-0.1.0-win64/Lilt.exe        11,961,856 bytes  (PE subsystem 2)
dist/Lilt-0.1.0-win64-portable.zip    18,496,032 bytes
```
