# Lilt

Lilt is a local-first music player built with Rust, egui/eframe and Rodio. Audio playback,
scanning and user data always stay on this device. Online lyrics are optional: they send
only the track title, artist, album and duration to LRCLIB or the AI provider configured in
Settings, and can be turned off there. The player never uploads audio files, paths or
listening history. It is a desktop reconstruction of the Musicolet feature set requested
for this workspace; it does not use the target's code, name, icon, fonts, audio,
screenshots or text.

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
does not stop playback, and logs live in the data folder below. AI requests, results,
errors and model/connection tests are logged there with the API key redacted; the log is
appended rather than cleared when developer mode is re-enabled. Command-line flags such as
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
screenshots in `checks/`). For lyric checks, `LILT_START_PAGE=now`, `LILT_LYRICS=1`,
`LILT_LYRICS_OFFSET=500` and `LILT_PAUSE_AFTER_SEEK=1` open the now-playing lyric panel
at a fixed position with an offset applied. For tray and hotkey checks,
`LILT_DESKTOP_LOG=<file>` appends native action lines (tray click, tray menu, hotkey,
show/hide) to that file without enabling the developer console. The desktop companion
windows can be opened without touching saved settings with `LILT_DESKTOP_LYRICS=1`,
`LILT_DESKTOP_LYRICS_LOCKED=1` and `LILT_MINI_PLAYER=1`.

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
- **Filename tag fallback.** When a file has no title or artist tag, a filename such as
  `周杰伦 - 晴天`, `Aimer-After Rain`, `01. Artist - Title` or `(02) Artist-Title` is split
  into artist and title for display. Leading track numbers are only removed when they look
  like a track prefix, so `7 Rings` and `1984` stay titles. Real embedded tags and user tag
  edits always win; no audio file is modified.
- Playback through a local Symphonia decoder with content-aware probing, so M4A/MP4
  files play even when they use a `.mp3` name and damaged files report an error instead
  of closing the player; pause/resume, seek, next/previous (10-second replay
  rule), gapless preload, repeat off/all/one, shuffle, volume, 0.50–3.00× speed,
  ReplayGain tag application, the target's 0–4000 ms play/pause volume fades (250 ms
  steps) with its own quadratic curves, and a sleep timer with custom hours/minutes, N
  songs, the five-entry histories, "wait until the song ends" and "fade out the last song".
- **Fixed transport bar.** The bottom bar uses a fixed three-column layout: the
  transport group stays at the window centre and previous/next align with play
  regardless of the song title length.
- Up to 20 named queues with unique names, no duplicates, remembered positions, loading
  from a playlist, M3U export, and the target's Replace/Merge policy for same-name
  creation (Replace is the default).
- Playlists with M3U import/export and move up/down, Most Played with CSV export, recent
  and history views, bookmarks/notes, tag editing (including batch), lyrics from
  embedded tags / `.lrc` / editor, and live search across songs, albums, artists, folders
  and playlists.
- **Online lyrics (optional).** When a track starts without lyrics, Lilt queries LRCLIB in
  the background. High-confidence matches are written as a `.lrc` sidecar; close matches
  open a version picker so a live/remastered take cannot silently replace the studio one.
  When LRCLIB has nothing, the lyrics panel offers a manual **AI lyrics** button. The AI
  prompt asks for a draft LRC with approximate timestamps instead of requiring web search
  or verified timing; short copyright refusals are reported as errors. AI output is
  previewed before saving. The AI panel supports OpenAI-compatible providers (DeepSeek,
  GLM, MiMo, OpenAI, OpenCode Go/Zen, Kimi, Qwen, SiliconFlow and custom endpoints) with
  model listing and encrypted API-key storage. Only title, artist, album and duration are
  sent; audio files, paths and listening history stay local. Settings has a master switch
  and a cache clear button; Developer mode logs AI requests, results and errors (never the
  key) to `developer.log`.
- **Locate current song** from the top bar, the Songs header or the now-playing overlay.
  It opens the complete song list and scrolls the playing row into view; the one-shot
  scroll does not fight later manual scrolling.
- **A-B loop.** In the now-playing panel, press **A-B loop** once to set A, again to set B
  and once more (or **Clear A-B**) to stop. Playback returns to A when it reaches B; seeking
  outside the range or changing track clears the markers. The same panel shows both times.
- **Stop after this song.** The now-playing panel has a one-shot **Stop after this song**
  button. It drops an already queued gapless track, pauses when the current song ends and
  keeps the queue in place; pressing prev/next or starting another context cancels it.
- **Duplicate songs.** **Settings → Find duplicate songs** groups tracks with the same
  normalised title and artist and a length within two seconds (case, spaces and punctuation
  are ignored). Each group puts the highest-bitrate, then largest copy first and marks it
  **KEEP**, with format, bitrate, size, duration and full path for every copy. Play or open
  the containing folder from the dialog. Detection only reports; audio files are never
  deleted or renamed.
- **Missing-file re-linking.** Playlists keep entries whose file disappears instead of
  silently dropping them. **Settings → Library** shows the missing count and opens a list
  with the old path and the playlists, queues, favourite, lyrics and bookmarks that point at
  it. Choose **Locate…** and pick the moved file inside a library folder; Lilt moves the song
  state to the new key, refreshes the library immediately and rewrites playlist/queue
  references.
- **Local backup & restore.** **Settings → Backup & restore** exports one versioned JSON
  file with playlists, queues, favourites, play counts, last positions, bookmarks, lyrics
  and offsets, tag overrides, EQ profiles, recent searches and settings. Restore validates
  the app, format and platform, stops playback, replaces the user data, saves the state and
  rescans the library. Audio files and the scanned index are never copied into the backup;
  playlists whose files are gone keep their keys so the missing-file tool can re-link them.
- **Lyric timing and display.** Each song keeps its own offset (`-500`, `-100`, `+100`,
  `+500` ms and reset, limited to ±60 s); positive values delay the lyrics. Clicking a
  lyric line seeks to its adjusted time. Settings has lyric font size (10–24 px) and
  opacity (30–100%) sliders with a live preview.
- **Windows desktop integration.** A self-drawn tray icon with left-click restore and a
  right-click menu (Show Lilt, Play/Pause, Previous, Next, Desktop lyrics, Lock/Unlock
  desktop lyrics, Mini player, Quit Lilt). Closing hides to the tray by default;
  minimizing does too when enabled. Playback, track changes, sleep timers, fades and
  state saves keep advancing while hidden.
- **Desktop lyrics.** A borderless, always-on-top overlay in the style of KuGou's desktop
  lyrics: the current line is left aligned at the top, the next line is right aligned at
  the bottom, and both use the same per-song offset and opacity settings as the now-playing
  panel. The active line fills from left to right as it is sung: the already-played part uses
  the played colour and the remaining part uses the unplayed colour. Both colours can be
  changed in **Settings → Desktop integration** and previewed live; the next line uses the
  unplayed colour at lower opacity. Lines never wrap or ellipsize; the font shrinks to fit
  so the full text is visible even in a narrow window. Text is drawn with a dark outline.
  When unlocked the overlay is a
  semi-transparent rounded dark card with a thin accent frame and a large floating toolbar
  (44 px targets, 38 px in narrow windows)
  (favourite, previous, play/pause, next, open player, lock and close); the whole lyric area
  can be dragged and double-click opens Now Playing. Locking removes the card and toolbar,
  makes the window fully transparent and click-through, and leaves only the outlined lyrics
  plus an **Unlock** pill above them; click it, use the tray menu or use Settings to
  unlock. Settings has A−/A+ buttons beside the desktop lyric size slider, and Now Playing
  has a `词` quick button that only shows or hides the overlay. Unlocked, resize the window
  or scroll over it to zoom the lyrics proportionally: the size slider is the baseline at
  the 900×190 design size, and both lyric lines scale from the window height while long
  lines still shrink to fit. The visible card edge has an 8-point native resize band, so
  the standard horizontal, vertical and diagonal cursors appear on hover. The default size
  is 900×190 (minimum 460×160). `Ctrl+Alt+L` toggles the window.
- **Mini player.** A compact always-on-top window with cover art, title/artist/album,
  previous/play/next, shuffle/repeat, elapsed time and a seek bar. Its transport and seek
  actions go through the same playback engine and stay in sync with the main window. The
  header can be dragged; the expand button returns to the full player.
- **Windows SMTC.** The system media flyout gets the current title, artist, album, play
  state, position/duration, shuffle and repeat mode. Its play, pause, previous and next
  buttons drive the same playback state as the main window and the tray.
- Global hotkeys work while another app is focused: `Ctrl+Alt+Space` (play/pause),
  left/right (previous/next), up/down (volume ±5%) and `L` (desktop lyrics). Tray,
  close-to-tray, global hotkeys, desktop lyrics, lock, lyric size and the mini player are
  all in **Settings → Desktop integration**.
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
  Media3 and Room; see `android/README.md`. Android has the same local backup/restore
  route, with its own platform-specific JSON envelope and Room transaction.

## Keyboard

| Key | Action |
|---|---|
| Space | Play / pause |
| Ctrl+Right | Next |
| Ctrl+Left | Previous |
| Ctrl+F | Focus search |
| Escape | Close the top panel / clear search |

Global hotkeys (registered system-wide while Lilt runs, and also effective when the main
window is hidden in the tray):

| Key | Action |
|---|---|
| Ctrl+Alt+Space | Play / pause |
| Ctrl+Alt+Right | Next |
| Ctrl+Alt+Left | Previous |
| Ctrl+Alt+Up / Down | Volume ±5% |
| Ctrl+Alt+L | Desktop lyrics |

## Platform note

The requested target is an Android app. This folder holds the desktop program; the
Android APK is a separate Kotlin/Compose project in `android/` (see `android/README.md`).
Because the desktop program is a native Rust window, it cannot be "packaged" into an
APK by changing a file extension; the Android path is a real second build with the
requested Media3 foreground service, Room library and Compose screens.

Online lyrics are optional and off-switchable in Settings. The desktop build sends only
title, artist, album and duration to LRCLIB and to the AI endpoint chosen by the user. It
contains no advertising, analytics or crash-reporting SDK, and the AI key is stored with
the operating system's user-bound protection (DPAPI on Windows). Audio files, paths and
listening history are never uploaded. The Android manifest and permissions are documented
separately in `android/README.md`.
The tray icon, global hotkeys, companion windows and SMTC use `tray-icon`,
`global-hotkey`, egui viewports and the Windows `windows` crate. The SMTC integration is
built for Windows; the rest of the desktop program still builds on other platforms.
