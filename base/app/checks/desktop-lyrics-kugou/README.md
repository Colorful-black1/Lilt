# Desktop lyrics — KuGou-style left/right lines and semi-transparent card

## Goal

The previous desktop overlay stacked three centred lyric lines. This round changes it
to the layout KuGou uses on desktop:

- the current lyric line sits on the **top-left**, aligned to the left edge;
- the next lyric line sits on the **bottom-right**, aligned to the right edge;
- both lines stay on a single row, never wrapped or ellipsized;
- the font shrinks automatically if either line is wider (or taller) than the
  window, so the full text is always visible;
- the lyric text has a dark painted outline, so it stays readable over the
  semi-transparent card and bright wallpapers.

Unlocked mode uses a **semi-transparent rounded card** with a thin accent frame and
a floating dark toolbar of 44 px buttons (38 px in narrow windows; favourite,
previous, play/pause, next,
open player, lock and close). The whole lyric body is draggable and double-click
opens Now Playing. Locked mode draws **no card, frame or toolbar**: only the
outlined lyrics and the small unlock puck remain, fully transparent and
click-through.

The Settings page now has `A−` / `A+` buttons beside the desktop lyric size slider.
The Now Playing page also has a `词` / **Lyrics overlay** quick button that shows or
hides the desktop lyrics without opening Settings.

## Implementation

- `base/app/src/ui/companion.rs`
  - `lyric_pair` chooses the current and next non-empty lines using the existing
    per-song offset.
  - `fit_font_size` and `lyric_galley` fit each line to the available width and
    height. There is no wrapping or truncation.
  - `paint_outlined_line` paints a near-black outline in eight directions, then
    the accent-coloured fill.
  - the unlocked layout draws the dark rounded card, a header with the grip and a
    toolbar pill of 44 px buttons (38 px when the card is narrower than 560 px);
    the current line starts below it, left aligned.
  - the next line is painted at the bottom-right of the available body.
  - default window size is `900x190`; the minimum is `460x160`.
- `base/app/src/desktop.rs`
  - unlocked lyrics use `LWA_ALPHA` (215/255) plus a rounded window region, which
    makes the card genuinely semi-transparent.
  - locked lyrics switch to `LWA_COLORKEY` with no card; only the painted text and
    outline remain.
  - `WM_NCHITTEST -> HTTRANSPARENT` is applied only while locked.
  - the mini player keeps its own rounded window region.
- `base/app/src/ui/pages.rs`
  - Settings copy describes the left/right layout, auto-fitting and the two card
    states; the size row has A−/A+ buttons.
- `base/app/src/ui/dialogs.rs`
  - the Now Playing quick-action row has a `词` / Lyrics overlay toggle.

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
locked:   hit=-1 (HTTRANSPARENT) layered=True key=0 alpha=0   flags=1 (LWA_COLORKEY)
unlocked: hit=1  (HTCLIENT)     layered=True key=0 alpha=215 flags=2 (LWA_ALPHA)
```

This confirms the locked state is fully transparent/click-through and the unlocked
state uses a semi-transparent card.

## Evidence

All screenshots were taken with the release build. The main window was moved
off-screen and the lyric window was placed over a synthetic white/pastel desktop so
the card transparency, outline and toolbar are visible. The two long Chinese lines
in the narrow screenshots are synthetic test text used to prove that long lines
scale to fit without clipping.

| File | What it shows |
|---|---|
| `01-pc-lyrics-unlocked-900x190.png` | Semi-transparent card, current line left, next line right, 44 px toolbar |
| `02-pc-lyrics-locked-900x190.png` | Fully transparent locked overlay: no card/toolbar, crisp outlined text, unlock puck |
| `03-pc-lyrics-narrow-460x160.png` | Minimum width: a long line is scaled to fit completely, next line right aligned |
| `04-pc-lyrics-narrow-680x160.png` | Reference-like width: both long lines fully visible |
| `05-pc-lyrics-no-lyrics-900x190.png` | Unlocked no-lyrics state: title left, empty-state message right |
| `06-pc-lyrics-favorite-900x190.png` | Favourite heart filled with the accent colour |
| `07-pc-lyrics-no-lyrics-locked-900x190.png` | Locked no-lyrics state: only text and the unlock puck |
| `hit-test.txt` | Native hit-test and layered-window values |
| `pc-tests.txt` | 46-test result and relevant test names |

## Packaged artifacts

```text
dist/Lilt-0.1.0-win64/Lilt.exe        11,210,752 bytes  (PE subsystem 2)
dist/Lilt-0.1.0-win64-portable.zip    18,197,805 bytes
```

`package.ps1 pc` completed successfully after the change.

## Limitations

- Automated mouse injection into the deferred companion viewports still does not
  reach the unlock puck in this environment, so its click was verified by the
  shared `ToggleLyricsLock` action and the separate always-on-top window rather
  than by a physical click. Manual confirmation of the puck remains worthwhile.
- The outline is painted as eight offset text layers. This is intentional and
  keeps the text sharp on both dark and bright backgrounds, but it does not
  scale like a vector stroke; very small fitted sizes use a 1 px outline.
- The toolbar is always visible while unlocked (not hover-only), so it takes an
  approximately 50 px strip at the top of a wide window (44 px in narrow ones).
- Android has separate evidence in `../android-floating-lyrics-compact/`.
