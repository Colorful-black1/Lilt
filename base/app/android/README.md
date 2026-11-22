# Lilt for Android

This is the installable Android version of Lilt, rebuilt from the requested feature
set: a purely offline local music player with the same core route as the reference app
(scan → folder → play → queue → notification). It uses its own name, icon, colours and
copy.

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

The debug APK is signed with the standard Android debug key, which is fine for sideloading.
For a store or a shareable release build, create your own signing key and configure
`signingConfigs` in `app/build.gradle.kts`; a release build is otherwise unsigned.

## Put it on a phone

1. Copy `app/Lilt-1.0-debug.apk` to the phone by USB or a local file transfer.
2. Tap the file in the phone's file manager. If Android asks, allow that file manager to
   install unknown apps.
3. Open Lilt and allow audio access. The first scan starts automatically; the refresh
   button scans again.
4. For the notification controls on Android 13+, allow notifications when asked.

This debug APK is for testing and sideloading, not for a store listing. A store build
needs a release signing key and an `.aab` produced with `bundleRelease`.

## What is implemented

- Kotlin + Jetpack Compose, one activity and Compose Navigation.
- MediaStore scan for mp3, flac, ogg, opus, wav, m4a, aac and m4b, persisted in Room.
  Opening the app rescans; stale rows are removed; progress is shown.
- Folder-first browsing with breadcrumbs, recursive play/shuffle and folder counts.
- Include-only and exclude folder rules that rebuild the visible library immediately.
- **Library tab with songs, albums, artists, favourites, recently added, most played,
  history and bookmarks.** Album and artist detail pages support play all, shuffle, queue
  and playlist actions. Favourite hearts appear in the library, folder, playlist, search
  and now-playing screens.
- **List tools** on the song views: title, artist, album, duration, date added, play count,
  last played, track number and file name sorting with an ascending/descending toggle that
  survives restarts; multi-select for batch favourite, queue and playlist actions; an A–Z
  index strip when sorted by title; and per-playlist multi-select removal.
- **Search** is grouped into songs, albums, artists, folders and playlists, highlights the
  matching text, and keeps the last 12 queries for one-tap reuse.
- **History and bookmarks.** Playback history keeps the latest 200 starts and can be
  cleared. A bookmark stores a song position plus an optional note; bookmarks are listed
  in the library and as one-tap chips on the now-playing screen.
- **Room database v3** with a separate `song_state` table for favourites, play counts and
  last-played time, so a MediaStore rescan cannot wipe user data. It also stores bookmarks
  and search history. Upgrades from v1/v2 keep the existing library, playlists, queues and
  user state.
- **Play counts** are recorded after 30% of a track has been heard; history is recorded at
  playback start.
- Playback through Media3 ExoPlayer inside a foreground `MediaSessionService`:
  notification controls, lock-screen controls, headset/Bluetooth media buttons, noisy
  headphone pause and audio focus.
- Gapless-ready contiguous queue, previous-restarts-after-10-seconds, shuffle and the
  three repeat modes.
- Up to 20 named queues, same-name Replace/Merge policy, M3U export and import with a
  tested round trip.
- Playlists with create, rename, delete, add/remove songs and M3U export/import.
- Ten-band equalizer (31.5 Hz to 16 kHz, ±15 dB) applied to the media session, stored on
  the device, plus eight presets. On Android 9+ this uses `DynamicsProcessing`; Android
  8.0–8.1 falls back to the platform five-band equalizer mapped to the same curve.
- Sleep timer with a five-second fade, playback speed 0.50–3.00×, search, light/dark/
  black/system themes, five accents, and a switchable English/Chinese interface.

## Deliberately not in this build

Tag editing, lyrics editing, embedded ReplayGain writing, drag-to-reorder playlists and
queues, home-screen widgets, quick-settings tile, Android Auto, Chromecast, audio trimming
and backup/restore are later rounds. They are not simulated with fake data.

## Privacy and permissions

The manifest requests only media reading, notifications, the media-playback foreground
service and wake lock. It requests **no INTERNET permission** and contains no network,
advertising, analytics or crash-reporting SDK. Media3 declares `ACCESS_NETWORK_STATE` in
its library manifest; Lilt removes it with a manifest merge rule.

## Verification in this workspace

- `./gradlew assembleDebug` passes and produces the APK above.
- `./gradlew testDebugUnitTest` passes 13 unit tests for M3U round trips, path imports,
  include/exclude filtering, album/artist grouping, sorting, search grouping and the
  letter index.
- `./gradlew connectedDebugAndroidTest` passes the Room 1→2 and 2→3 migration tests on an
  Android 15 emulator.
- The APK was installed on an Android 15 emulator and the main flow was exercised; see
  `../checks/round-4-c/README.md` for the library evidence, and
  `../checks/round-3/README.md` for the remaining unverified items (headset unplug and
  real-phone audio routing).
