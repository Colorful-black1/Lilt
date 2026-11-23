# Desktop lyrics — KuGou-style left/right lines and full-width fitting

## Goal

The previous desktop overlay stacked three centred lyric lines inside a dark card.
This round changes it to the layout KuGou uses on desktop:

- the current lyric line sits on the **top-left**, aligned to the left edge;
- the next lyric line sits on the **bottom-right**, aligned to the right edge;
- both lines stay on a single row, never wrapped or ellipsized;
- the font shrinks automatically if either line is wider (or taller) than the
  window, so the full text is always visible;
- the text has a dark painted outline instead of a card background, so it stays
  readable over bright wallpapers without a dark rectangle.

The unlocked window has a thin accent frame and a floating dark toolbar with 32 px
buttons: favourite, previous, play/pause, next, open player, lock and close. The
lyric body itself is also draggable (double-click opens Now Playing). Locked mode
draws no frame, toolbar or progress rail: only the outlined lyrics and the small
unlock puck remain. The whole lyric area can be clicked through while locked.

## Implementation

- `base/app/src/ui/companion.rs`
  - `lyric_pair` chooses the current and next non-empty lines using the existing
    per-song offset.
  - `fit_font_size` and `lyric_galley` fit each line to the available width and
    height. There is no wrapping or truncation.
  - `paint_outlined_line` paints a near-black outline in eight directions, then
    the accent-coloured fill.
  - the unlocked layout uses a 38 px header with the grip and a 32 px-button
    toolbar pill; the current line starts below it, left aligned.
  - the next line is painted at the bottom-right of the available body.
  - default window size is `900x190`; the minimum is `460x160`.
- `base/app/src/desktop.rs`
  - the lyric window now always uses `LWA_COLORKEY` (black key). The dark card
    alpha path is gone, so both states are genuinely transparent.
  - `WM_NCHITTEST -> HTTRANSPARENT` is applied only while locked.
  - the mini player keeps its own rounded window region.
- `base/app/src/ui/pages.rs`
  - Settings copy now describes the outline overlay, left/right layout and
    auto-fitting instead of a translucent panel.

## Verification

```text
cargo test
# running 46 tests
# test result: ok. 46 passed; 0 failed

cargo build --release
# Finished
```

New tests cover line selection, the width/height fitting rule and the
locked/unlocked chrome switch. See `pc-tests.txt`.

Native window check at 300,430 with a 900x190 client area, tested point
(690,450):

```text
locked:   hit=-1 (HTTRANSPARENT) layered=True key=0 alpha=0 flags=1
unlocked: hit=1  (HTCLIENT)     layered=True key=0 alpha=0 flags=1
```

The `flags=1` result is `LWA_COLORKEY`, confirming there is no dark alpha card in
either state.

## Evidence

All screenshots were taken with the release build. The main window was moved
off-screen and the lyric window was placed over a synthetic white/pastel desktop
so the transparency and outline are visible. The two long Chinese lines in the
narrow screenshots are synthetic test text used to prove that long lines scale to
fit without clipping.

| File | What it shows |
|---|---|
| `01-pc-lyrics-unlocked-900x190.png` | Current line left, next line right, accent frame, 32 px toolbar |
| `02-pc-lyrics-locked-900x190.png` | Fully transparent locked overlay: no card/toolbar, crisp outlined text, unlock puck |
| `03-pc-lyrics-narrow-460x160.png` | Minimum width: a long line is scaled to fit completely, next line right aligned |
| `04-pc-lyrics-narrow-680x160.png` | Reference-like width: both long lines fully visible |
| `05-pc-lyrics-no-lyrics-900x190.png` | Unlocked no-lyrics state: title left, empty-state message right |
| `06-pc-lyrics-favorite-900x190.png` | Favourite heart filled with the accent colour |
| `07-pc-lyrics-no-lyrics-locked-900x190.png` | Locked no-lyrics state with the unlock puck |
| `hit-test.txt` | Native hit-test and layered-window values |
| `pc-tests.txt` | 46-test result and relevant test names |

## Packaged artifacts

```text
dist/Lilt-0.1.0-win64/Lilt.exe        11,197,952 bytes  (PE subsystem 2)
dist/Lilt-0.1.0-win64-portable.zip    18,191,219 bytes
```

`package.ps1 pc` completed successfully after the change. The Android APK is
unchanged in this round.

## Limitations

- Automated mouse injection into the deferred companion viewports still does not
  reach the unlock puck in this environment, so its click was verified by the
  shared `ToggleLyricsLock` action and the separate always-on-top window rather
  than by a physical click. Manual confirmation of the puck remains worthwhile.
- The outline is painted as eight offset text layers. This is intentional and
  keeps the text sharp on both dark and bright backgrounds, but it does not
  scale like a vector stroke; very small fitted sizes use a 1 px outline.
- The toolbar is always visible while unlocked (not hover-only), so it takes a
  38 px strip at the top of the window.
- Android is untouched in this round.
