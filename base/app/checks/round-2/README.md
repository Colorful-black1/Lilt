# Round 2 checks — values read from the DEX

The APK has no readable native image: `native_strings_find` and `native_function_pseudocode`
both return `not_supported — "An Android app is not native machine code."`, and the
`assets/*` payloads are PairIP `"IAP"` blobs. Deep mode read the real program as Dalvik
bytecode with `android_method_show`; JADX 1.5.6 was used only to read it more easily.

| Screenshot | What it shows |
|---|---|
| `01-equalizer.png` | five device profiles (Built-in speakers, Speakers/Headphones, Headset/Earphones, Bluetooth, USB), the 22 target presets, the exact 31.5 Hz label, the three preamp modes (Off/Headroom/Boost), bass boost, virtualizer and 0–200 balance |
| `02-sleep.png` | custom hours/minutes with the 1:30 and 0:15 history, the After N songs mode, "Wait until the current song ends", "Gradually fade out the last song", and the +1 second note |
| `03-settings.png` | five theme modes including Day/Night, with Black selected by default |
| `04-equalizer-narrow.png` | the same controls at 720×1280; device chips and the Reset button wrap cleanly |
| `05-sleep-narrow.png` | the sleep dialog at 720×1280 |

`cargo build` succeeds and `cargo test` passes 9 tests, including exact target preset tables,
frequencies, Q 1.0, and the target defaults. `rebuild_check` reports build passed, screenshot
passed, and mechanics passed (45 sourced values). Mechanics behavior is honestly marked
unverified: it would need a live comparison against the original, which this desktop rebuild
cannot run.

The source of every value and every DEX code-unit address is in `app/MECHANICS.md` §8.
