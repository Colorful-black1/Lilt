# Desktop lyrics redesign — transparency and unlock

## What changed

The old desktop lyric window had two problems:

1. The unlocked card was filled with an opaque black colour.
2. Locking set the whole window click-through. The small lock drawn in the locked
   overlay was only a painted icon, not a button, so clicking it did nothing.
   Unlocking required the tray menu or Settings.

This round changes the desktop lyric window to:

- **Unlocked:** a semi-transparent dark card (constant window alpha 215/255) with a
  toolbar of 32 px targets: favourite, previous, play/pause, next, open player, lock and
  close. The favourite heart is filled with the accent colour when the current song is
  favourited. The header can be dragged and the window resized.
- **Locked:** no card, toolbar or progress rail. The whole window uses a black colour
  key, so only the lyric text and a small lock button remain. The lyric window is
  click-through; a separate 30×30 always-on-top unlock viewport sits above the lyrics
  and sends the same `ToggleLyricsLock` action as the tray. Locked text is drawn without
  the soft shadow so it stays sharp.
- **Narrow windows:** below 560 px the brand and metadata line are hidden. The minimum
  window is 420×160, which keeps the toolbar and two wrapped lyric lines visible.
- **Text contrast:** unlocked lines have a soft dark shadow and the active line uses the
  accent colour. The shadow is disabled in the locked transparent state.

Windows uses `LWA_ALPHA` for the unlocked card and `LWA_COLORKEY` for the locked
window. Click-through is implemented by subclassing the lyric window and returning
`HTTRANSPARENT` from `WM_NCHITTEST` while locked, so the layered window stays visible.
The tray menu and Settings page remain alternate unlock paths.

## Tests and checks

```text
cargo test
# 43 passed, 0 failed

cargo build --release
# Finished
```

New tests:

- `ui::companion::tests::locked_lyrics_hide_the_card_and_controls`
- `ui::companion::tests::unlock_puck_only_appears_for_visible_locked_lyrics`

Native hit-test check (window at 300,430, 780×176, point 690,450):

```text
locked:   -1 (HTTRANSPARENT)
unlocked:  1 (HTCLIENT)
```

## Evidence

| File | What it shows |
|---|---|
| `01-pc-lyrics-unlocked-780x176.png` | Semi-transparent card over a bright page, 32 px toolbar and progress rail |
| `02-pc-lyrics-locked-780x176.png` | Fully transparent, crisp lyrics with the small lock button |
| `03-pc-lyrics-no-lyrics-780x176.png` | Unlocked no-lyrics state with title and empty-state line |
| `04-pc-lyrics-unlocked-420x160.png` | Narrow layout: toolbar complete, two wrapped lyric lines |
| `05-pc-lyrics-favorite-780x176.png` | Favourited heart filled with the accent colour |
| `06-pc-lyrics-locked-no-lyrics-780x176.png` | Locked no-lyrics state with crisp title and unlock button |
| `hit-test.txt` | Native `WM_NCHITTEST` values for locked/unlocked |
| `pc-tests.txt` | 43-test result and new test names |

The screenshots were taken with the release build over a live desktop background. The
card alpha, transparency and hit-test values are real native-window results, not
simulated UI.

## Packaged artifacts

```text
dist/Lilt-0.1.0-win64/Lilt.exe        11,197,952 bytes  (PE subsystem 2)
dist/Lilt-0.1.0-win64-portable.zip    18,190,273 bytes
```

`package.ps1 pc` completed successfully after the change. The Android APK is
unchanged in this round.

## Limitations

- The unlock button is an egui viewport, so the automated test session could not inject
  a physical mouse click into it (the same limitation applies to the existing mini
  player). The button queues `CompanionAction::ToggleLyricsLock`, the same action used
  by the tray and Settings, and both visual states are covered by startup state and
  native window checks.
- The unlocked card intercepts mouse clicks because it contains controls. Only the
  locked state is click-through.
- A-B loop and stop-after are unchanged.
- Android is unchanged; this round is desktop-only.
