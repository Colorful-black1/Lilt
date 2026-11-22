# Lilt

Lilt is a local, offline music player built with Rust, egui/eframe and Rodio. It is the
desktop reconstruction of the Musicolet feature set requested for this workspace; it does
not use the target's code, name, icon, fonts, audio, screenshots or text.

## Run

```
cargo run --release
```

Build only:

```
cargo build
```

Release builds use the Windows GUI subsystem, so double-clicking `lilt.exe` does not open
a terminal. For diagnostics, enable **Settings → Developer → Developer mode**; Lilt opens a
separate `Lilt developer console` window that tails `developer.log`. Closing that window
does not stop playback, and logs live in the data folder below. Command-line flags such as
`--list-audio` still print to the terminal they are launched from.

Screenshot mode (starts without stealing focus, draws 120 frames, writes a PNG and exits):

```
cargo run -- --screenshot shots/now.png
```

List the sound outputs and see which one is the system default:

```
cargo run -- --list-audio
```

Optional size for layout checks:

```
cargo run -- --screenshot shots/720.png --size 720x1280
```

For a settings-page check, `LILT_PAGE=settings` opens Settings first, and
`LILT_SETTINGS_SCROLL=1050` starts its scroll area at the Playback card (used by the
screenshots in `checks/`).

## First launch

Lilt ships a small **starter library** of ten original instrumental WAV files with real
RIFF-INFO tags and one `.lrc` file. They are scanned like any other folder. If the
bundled folder is unavailable, the same pieces are generated locally in the data folder.
Add your own folder from **Settings → Library → Add folder**; the program then scans it
directly.

## No sound?

Windows sometimes sends a program to an output that is not the one you are listening to
(for example an HDMI monitor with no speakers). Open **Settings → Audio → Sound output**,
pick the speakers or headphones, and press **Test**; a short confirmation tone plays on
that output. You can also list the outputs from a terminal:

```
lilt --list-audio
```

The line marked `(system default)` is the current default. Lilt remembers the chosen
output in its local state file. If the tone is still silent, check the Windows volume
mixer for `lilt.exe` and the hardware volume of the selected device.

Data lives in:

- Windows: `%APPDATA%\Lilt\state.json`
- macOS/Linux: the platform data directory under `Lilt` / `lilt`

Set `LILT_DATA_DIR` to use a different data folder.

## What is included in this round

- Real folder scanning (mp3/flac/ogg/opus/wav/m4a/aac and more), hierarchical folder
  browsing, breadcrumbs, exclude/include rules, hidden-folder and scan-on-start options,
  manual rescan and live scan progress.
- Playback through Rodio/Symphonia, pause/resume, seek, next/previous (10-second replay
  rule), gapless preload, repeat off/all/one, shuffle, volume, 0.50–3.00× speed,
  ReplayGain tag application, the target's 0–4000 ms play/pause volume fades (250 ms
  steps) with its own quadratic curves, and a sleep timer with custom hours/minutes, N
  songs, the five-entry histories, "wait until the song ends" and "fade out the last song".
- Up to 20 named queues with unique names, no duplicates, remembered positions, loading
  from a playlist, M3U export, and the target's Replace/Merge policy for same-name
  creation (Replace is the default).
- Playlists with M3U import/export and move up/down, Most Played with CSV export, recent
  and history views, bookmarks/notes, tag editing (including batch), lyrics from
  embedded tags / `.lrc` / editor, and live search across songs, albums, artists, folders
  and playlists.
- **Locate current song** from the top bar, the Songs header or the now-playing overlay.
  It opens the complete song list and scrolls the playing row into view; the one-shot
  scroll does not fight later manual scrolling.
- 10-band equalizer at the target's exact frequencies and Q, 22 target presets, three
  preamp modes, bass boost, virtualizer, 0–200 balance, headroom suggestion and five
  stored device profiles; changes apply to the live audio source and persist after
  restart.
- Light, dark, black, day/night (06:00–17:59) and follow-system themes, five accent
  colours, English/Chinese UI, generated cover artwork, accessibility-sized controls and
  responsive layouts.
- A **Sound output** picker with a test tone at the top of Settings, so a Windows default
  pointed at an HDMI screen can be changed without editing files.
- A separate, installable **Android APK** in `android/` built with Kotlin, Compose,
  Media3 and Room; see `android/README.md`.

## Keyboard

| Key | Action |
|---|---|
| Space | Play / pause |
| Ctrl+Right | Next |
| Ctrl+Left | Previous |
| Ctrl+F | Focus search |
| Escape | Close the top panel / clear search |

## Platform note

The requested target is an Android app. This folder holds the desktop program; the
Android APK is a separate Kotlin/Compose project in `android/` (see `android/README.md`).
Because the desktop program is a native Rust window, it cannot be "packaged" into an
APK by changing a file extension; the Android path is a real second build with the
requested Media3 foreground service, Room library and Compose screens.

No network permission or network SDK is present. Everything runs from local files.
