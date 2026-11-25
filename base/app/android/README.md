# Lilt for Android

This is the installable Android version of Lilt, rebuilt from the requested feature
set: a local-first music player with the same core route as the reference app
(scan → folder → play → queue → notification), plus optional online lyrics. It uses its own
name, icon, colours and copy.

## Build

Requirements: JDK 17 or newer and an Android SDK with platform 35. The wrapper downloads
Gradle 8.13 and the dependencies on the first run.

```
cd app/android
./gradlew assembleDebug
```

On Windows:

```
.\gradlew.bat assembleDebug
```

The installable debug APK is written to:

```
app/android/app/build/outputs/apk/debug/app-debug.apk
```

Install it on a connected phone or emulator:

```
adb install -r app/android/app/build/outputs/apk/debug/app-debug.apk
```

The debug APK is signed with the committed development key
(`app/android/keystore/lilt-debug.keystore`), so later sideload builds can be installed over
the current one without uninstalling Lilt. For a store build, create your own signing key and
configure `signingConfigs` in `app/build.gradle.kts`.

## Put it on a phone

1. Copy `app/Lilt-0.2.1-debug.apk` to the phone by USB or a local file transfer.
2. Tap the file in the phone's file manager. If Android asks, allow that file manager to
   install unknown apps.
3. Open Lilt and allow audio access. The first scan starts automatically; the refresh
   button scans again.
4. For the notification controls on Android 13+, allow notifications when asked.
5. Optional: open **Settings → Floating lyrics** and turn on **Show over other apps**. If
   Android opens the display-over-other-apps screen, allow Lilt there; this is needed for
   the floating lyric overlay and for the lock-screen player to appear automatically. The
   now-playing page's **词** button shows or hides the floating lyrics, and **Lock position
   and controls** can be undone in Settings.
6. The media notification now also has a **heart** (favourite) and **词** (floating lyrics)
   action. **Settings → Lock screen** has the lock-screen player switch and a preview
   action.

This debug APK is for testing and sideloading, not for a store listing. A store build
needs a release signing key and an `.aab` produced with `bundleRelease`.

## What is implemented

- Kotlin + Jetpack Compose, one activity and Compose Navigation.
- MediaStore scan for mp3, flac, ogg, opus, wav, m4a, aac and m4b, persisted in Room.
  Opening the app rescans; stale rows are removed; progress is shown.
- **Filename tag fallback.** When MediaStore reports a blank title/artist or the
  `<unknown>` placeholder, a filename such as `周杰伦 - 晴天`, `Aimer-After Rain`,
  `01. Artist - Title` or `(02) Artist-Title` is split into artist and title for display.
  `7 Rings` and `1984` stay titles; real tags and single-song tag overrides always win.
- Folder-first browsing with breadcrumbs, recursive play/shuffle and folder counts.
- Include-only and exclude folder rules that rebuild the visible library immediately.
- **Library tab with songs, albums, artists, favourites, recently added, most played,
  history and bookmarks.** Album and artist detail pages support play all, shuffle, queue
  and playlist actions. Favourite hearts appear in the library, folder, playlist, search
  and now-playing screens.
- **List tools** on the song views: title, artist, album, duration, date added, play count,
  last played, track number and file name sorting with an ascending/descending toggle that
  survives restarts; multi-select for batch favourite, queue and playlist actions; an A–Z
  index strip when sorted by title; per-playlist multi-select removal; and a **locate
  current song** button in the app bar and song-list header.
- **Search** is grouped into songs, albums, artists, folders and playlists, highlights the
  matching text, and keeps the last 12 queries for one-tap reuse.
- **Lyrics** are stored per song in Room, support LRC timestamps with the current line
  highlighted in the player, plain text, and import from a local `.lrc`/`.txt` document.
- **Lyrics-first player.** The now-playing page shows the current and next lyric line in
  the middle, above the title, instead of a card at the bottom. Tapping the lyric block
  opens an immersive full-screen sheet with centred, auto-scrolling lines, tap-to-seek,
  source label, offset controls, seek bar and transport; the pencil opens the editor.
- **System-bar aware layout.** The home mini player and the player transport apply the
  platform `navigationBars` inset, so their buttons sit above both gesture navigation and
  the taller 3-button navigation bar. No fixed bar height is assumed.
- **Notification media actions.** The media card has favourite and floating-lyrics buttons
  alongside the transport. The transport is kept in the system's central play slot; on
  Android 12 and older the custom notification provider places the five actions in the
  order favourite, previous, play/pause, next, lyrics. The favourite shares the same Room
  state as the library and the in-app player; the lyrics button toggles the floating lyric
  overlay and explains the overlay permission when it is missing.
- **Lock-screen player.** With a track loaded, locking the phone shows a full-screen Lilt
  player with the clock, album art, title, seek bar, shuffle on the left, repeat on the
  right, the favourite heart beside the title, and a centred previous/play/next row.
  Saved LRC lyrics appear below the title in a five-line
  window that highlights the current line and slides as playback advances; the line wraps
  to two lines when needed; a three-line window is used when the lyric area is short. Swiping up
  (or tapping the unlock hint) closes Lilt and reveals the system PIN/pattern screen; it
  never bypasses the secure keyguard.
  The setting defaults on and can be turned off or previewed in **Settings → Lock screen**.
  Automatic display uses the same display-over-other-apps permission as floating lyrics.
- **Manual update check.** About shows the current version and the GitHub Releases URL.
  Checking downloads a newer APK automatically; **Install update** hands it to the system
  package installer, which may ask for permission to install unknown apps. The APK is
  signed with the committed development key so later sideload builds can be installed over
  earlier ones.
- **Online lyrics (optional).** When a track starts without lyrics, the playback service
  queries LRCLIB in the background and writes a high-confidence match into Room. Close
  matches open a version picker instead of silently replacing the studio version with a
  live/remastered one. When LRCLIB has nothing, the lyrics panel offers a manual **AI
  lyrics** button. The prompt asks for a draft LRC with approximate timestamps rather than
  requiring web search or verified timing, and short copyright refusals are reported as
  errors. AI output is previewed and validated before saving. Settings has a
  master switch, OpenAI-compatible provider/model fields, model fetching, an encrypted API
  key (Android Keystore) and a cache clear button. Room v9 adds `lyrics.source`,
  `lyrics.fetchedAt` and `lyrics_fetch_state` for negative/ambiguous results and for
  remembering that the user cleared a song's lyrics. Only title, artist, album and
  duration are sent; audio files and paths never leave the device.
- **Lyric timing and display** are adjustable. Each song keeps its own offset (`-500`,
  `-100`, `+100`, `+500` ms and reset, limited to ±60 s); positive values delay the lyrics.
  The editor shows the adjusted timestamps and saves the offset with the text. Settings has
  lyric font size (12–24 sp) and opacity (35–100%) sliders with a live preview.
- **Floating lyrics.** A compact always-on-top lyric outside the app. It shows only the
  current LRC line with the same per-song offset and opacity, plus a separate 18–44 sp
  maximum size; the line auto-shrinks so it fits on one row. The line is filled from left
  to right as it is sung; the played and unplayed colours can be customised in
  **Settings → Floating lyrics** and previewed there. It needs the optional
  **display over other apps** permission; Settings opens the system screen when it is
  missing. Unlocked mode is a small semi-transparent card with a drag grip, lock and close
  buttons. Locked mode removes the card and buttons completely, leaves only the outlined
  lyric and makes the window click-through (`FLAG_NOT_TOUCHABLE`); unlock it in Settings.
  The now-playing page has a **词** quick button that only shows or hides the overlay, while
  size, lock state and lyric colours stay in Settings, where the floating size slider also has
  explicit **缩小 / 放大** buttons. The overlay runs inside the existing playback service,
  not a second foreground service, so it always uses the same MediaSession as the
  notification.
- **Home-screen widget.** A resizable RemoteViews card with cover, title, artist,
  elapsed/total time, progress and previous/play-pause/next buttons; tapping the card opens
  Lilt. The playback service pushes state to it on changes and once a second while playing,
  and the transport buttons use a short-lived MediaController, so widget, app and
  notification stay in step. Add it from **Settings → Floating lyrics → Add home screen
  widget**. The launcher's pin support varies; a fallback instruction is shown when pinning
  is unavailable.
- **A-B loop.** The now-playing screen has A, B and Clear controls. A marker uses the
  current position, B must be later than A, and the playback service checks the position
  every 100 ms so the loop also works with the screen off or the app in the background.
  Changing tracks clears the markers. The loop state travels over MediaSession custom
  commands; no second player is created.
- **Stop after this song.** The player has a one-shot toggle. The playback service checks
  the position every 100 ms and pauses in the last 180 ms of the track, then seeks to 0:00
  so the same song remains loaded. Changing tracks clears the toggle. Because the check
  runs in the existing service, it also works with the screen off or the app backgrounded.
- **Duplicate songs.** **Settings → Duplicate songs** groups tracks with the same
  normalised title and artist and a length within two seconds. Each group marks the
  highest-bitrate, then largest copy **KEEP** and lists format, estimated bitrate, size,
  duration and folder path for every copy; tap a row to play that copy. The screen only
  reports — audio files are never deleted or renamed.
- **Missing-file re-linking.** A MediaStore rescan deletes rows for files that moved, but
  playlists, queues, favourites, lyrics, bookmarks and history keep their old ids. Room v8
  stores the last-known title, artist, album, folder and file name in `missing_songs`
  before the stale overview row is removed. **Settings → Missing files** lists each orphan
  with the playlists/queues and saved data that point at it. Pick a replacement from the
  current library and one Room transaction rewrites every reference and removes the old
  missing row; conflicts keep the existing row for the new id.
- **Local backup and restore.** **Settings → Backup & restore** writes one versioned JSON
  file through the system file picker. It contains playlists, queues, favourites, play
  counts, history, bookmarks, lyrics and offsets, tag overrides, folder rules, recent
  searches, EQ settings and app settings; audio files are never included. Restore asks for
  confirmation, resolves backup song ids against the current library (by id, then folder +
  file name, then title/artist/duration), replaces the user tables in one Room transaction
  and applies the settings. References to files that are no longer present are skipped and
  reported, never invented. No storage permission is added because the system document
  picker owns access to the chosen file.
- **Single-song tag editing** for title, artist, album, year and track. Tags are a
  non-destructive Room overlay: the audio file is not rewritten, the values survive
  MediaStore rescans, and **Reset to scanned tags** removes the override. The edit entry
  is in every song menu and in the now-playing menu.
- **Sleep timer** runs in the playback service: custom hours/minutes, stop after a number
  of songs, “after this track”, fade-out, countdown and cancellation. It keeps running
  with the screen off.
- **Play/pause fades** are configurable from 0 to 4 seconds in 250 ms steps.
- **Queue position memory** stores the current index and position for named queues, both
  periodically and on pause, and resumes there when the queue is tapped again. It can be
  turned off in Settings.
- **History and bookmarks.** Playback history keeps the latest 200 starts and can be
  cleared. A bookmark stores a song position plus an optional note; bookmarks are listed
  in the library and as one-tap chips on the now-playing screen.
- **Room database v8** with a separate `song_state` table for favourites, play counts and
  last-played time, so a MediaStore rescan cannot wipe user data. It also stores bookmarks,
  search history, lyrics with a per-song offset, named queue positions, virtual tag
  overrides and `missing_songs` metadata for relocated files. Upgrades from v1/v2/v3/v4/v5/v6/v7
  keep the existing library, playlists, queues and user state.
- **Play counts** are recorded after 30% of a track has been heard; history is recorded at
  playback start.
- Playback through Media3 ExoPlayer inside a foreground `MediaSessionService`:
  notification controls, lock-screen controls, headset/Bluetooth media buttons, noisy
  headphone pause and audio focus.
- Gapless-ready contiguous queue, previous-restarts-after-10-seconds, shuffle and the
  three repeat modes.
- Up to 20 named queues, same-name Replace/Merge policy, M3U export and import with a
  tested round trip. Tapping a queue opens its song list; the play button still resumes the
  saved position.
- Playlists with create, rename, delete, add/remove songs and M3U export/import. Playlist
  and queue detail screens have a reorder mode with up/down controls that save immediately.
- Ten-band equalizer (31.5 Hz to 16 kHz, ±15 dB) with all 22 desktop presets, preamp
  Off/Headroom/Boost, a peak-based headroom suggestion, bass boost, virtual surround where
  supported, left/right balance and one saved profile per output device. On Android 9+
  this uses `DynamicsProcessing`; Android 8.0–8.1 falls back to the platform equalizer.
- Full sleep timer (custom time, song count, after this track, fade, countdown, cancel)
  that runs in the playback service, play/pause fades up to four seconds, queue position
  memory, playback speed 0.50–3.00×, search, light/dark/black/system themes, five accents,
  and a switchable English/Chinese interface.

## Deliberately not in this build

Batch tag editing, cover replacement, embedded ReplayGain writing, drag-to-reorder (the
playlist and queue up/down controls are available), pinyin letter buckets, a lyric-only
home-screen widget, quick-settings tile, Android Auto, Chromecast and audio trimming are
later rounds. They are not simulated with fake data. Backups are
platform-specific today; importing a desktop backup on Android (or the reverse) is not
implemented.

## Privacy and permissions

The manifest requests media reading, notifications, the media-playback foreground service,
wake lock, `SYSTEM_ALERT_WINDOW` for floating lyrics and the automatic lock-screen player,
and `INTERNET` plus `ACCESS_NETWORK_STATE` for the optional online lyrics feature. The
overlay permission is optional, is only requested when the user turns the feature on, and
is never needed for playback. Online lyrics are optional and can be turned off in Settings;
the app sends only
the track title, artist, album and duration to LRCLIB or the configured AI endpoint. It
contains no advertising, analytics or crash-reporting SDK. The AI key is encrypted with an
Android Keystore AES key and is excluded from backups. Media3 also declares
`ACCESS_NETWORK_STATE`; Lilt now keeps it for the online lyrics feature.

## Verification in this workspace

- `./gradlew assembleDebug` passes and produces the APK above.
- `./gradlew testDebugUnitTest` passes 62 unit tests for M3U round trips, path imports,
  include/exclude filtering, album/artist grouping, sorting, search grouping, the letter
  index, filename tag fallback and track-prefix handling, LRC parsing, lyric offsets,
  floating lyric line windows and font clamping, widget progress/time formatting, backup
  JSON round trips, platform/format rejection, song reference resolution, A-B loop bounds,
  stop-after bounds, duplicate grouping, missing-reference grouping, EQ curves/profiles,
  queue resume positions, list reordering, virtual tag merging, online lyric
  matching/cleanup, draft AI prompt/refusal handling, base URL normalization and the
  OpenAI-compatible HTTP client.
- `./gradlew connectedDebugAndroidTest` passes the Room 1→2, 2→3, 3→4, 4→5, 5→6, 6→7,
  7→8 and 8→9 migration tests on an Android 15 emulator.
- The APK was installed on an Android 15 emulator and the main flow was exercised; see
  `../checks/filename-tag-fallback/README.md` for filename-derived artist/title evidence,
  `../checks/ai-lyrics-prompt-fix/README.md` for the draft AI prompt and live endpoint
  check, `../checks/round-8-b-online-lyrics-android/README.md` for automatic LRCLIB lookup,
  candidate selection, AI preview/save and the manifest permission dump,
  `../checks/round-7-d/README.md` for stop-after and duplicate-song evidence,
  `../checks/round-7-c/README.md` for A-B loop and missing-file re-link evidence,
  `../checks/round-7-b/README.md` for backup export, restore confirmation and restored
  playlist/favourite evidence, `../checks/round-7-a/README.md` for floating lyric and
  home-screen widget evidence,
  `../checks/round-6-c/README.md` for lyric offset, font and opacity evidence,
  `../checks/round-6-b/README.md` for virtual tag editing, `../checks/round-6/README.md`
  for locate-current and playlist/queue reorder evidence, `../checks/round-5/README.md`
  for lyrics, sleep/fade, equalizer and queue-memory evidence,
  `../checks/round-4-c/README.md` for the library evidence, and
  `../checks/round-3/README.md` for the remaining unverified items (headset unplug and
  real-phone audio routing).
