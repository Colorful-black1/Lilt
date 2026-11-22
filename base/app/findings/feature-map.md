# Feature map — target app to desktop rebuild

Target: `base.apk` (Musicolet 6.14.1), package `in.krosbits.musicolet`.
APK SHA-256 `2e696fd282711807a1340d0fb7d64ab668b9b8de63ada9471fa5bf7c55a031b0`.

The rebuild is a desktop program because the build environment is a Windows desktop.
The Android-only pieces are mapped to desktop equivalents below; they are not silently
dropped. Every rule in this file was read from the target's manifest, resources or
bytecode, or is explicitly marked **estimated**.

## 1. How the evidence was read

| What | Evidence |
|---|---|
| All 70 manifest components | `ev_11b7ee876d057f6ec66507065266c2f47a5a944d4152c9a47cfbe5eea9da9c5a` |
| Package / SDK / permissions | `ev_82854c9805c4c6e5b2f07f76d237d67c6e182eb99927d1597ac79e2f93c13d61` |
| Strings, 1,304 entries read | `ev_07b5a0ad73030c81ec84bb4c82a46625a262ad7c8b595a8df55a95305acc2bf9` |
| Arrays / option values | `ev_90d0189d5e7bb6121847399146fdc45cf3345463361df66a9d843c23479ba3de` |
| Equalizer device list (`EqualizerActivity2.l0`) | `ev_5a6e6ae027075cc4c131ff283511556aeb72ae404f77f7a20df4118ed7dd420a` |
| Equalizer runtime bands/preamp (`EqualizerActivity2.o0`) | `ev_47de1c211cd6b026358ccc864a5856eb42445658374a405f80092fcfea9a9683` |
| Equalizer class layout | `ev_011cb96b95e027a587758792c99358619253a4427316f90426c4c94ff7332da6` |
| `MusicService.onStartCommand` action table | `ev_fe218243b9bfecc4881881ee3a817c7d080c3f8edf581b03605ae719fc6b0617` |

Method bytecode has no virtual addresses; citations below name the class/method and the
code-unit offset where relevant, as in `MECHANICS.md`.

## 2. Screen inventory (manifest → rebuild)

The manifest declares the following. "Rebuild" is what the desktop program actually has.

| Target component | Rebuild equivalent | State |
|---|---|---|
| `MusicActivity` | main window, Folders/Playlists home tabs, persistent player bar | done |
| `SearchActivity`, `GhostSearchActivity` | live search field + grouped results page | done |
| `PlaylistActivity`, `PlaylistEditActivity` | playlists grid and detail editor | done (drag reorder replaced by move up/down; see gaps) |
| `MostPlayedActivity` | Most Played page + CSV export | done |
| `EqualizerActivity2` | Equalizer page, per-device profiles | done (10 fixed bands; see §5) |
| `Tag2Activity` | tag-editor modal, single and batch | done |
| `LyricsEditActivity` | lyrics card + editor, embedded/LRC read | done |
| `FolderExcluderActivity`, `IncludedFoldersActivity` | Settings → excluded / included folder lists | done |
| `SettingsActivity` | Settings page | done |
| `WelcomeActivity` | first-run starter library + empty states | adapted |
| `AboutActivity`, `FAQActivity` | About, keyboard shortcuts dialogs | partial (FAQ not separate) |
| `MiniPlayerActivity` | player bar is always visible; full-screen Now Playing overlay | adapted |
| `LockScreenActivity`, `LockScreen` | not possible for a normal desktop window; full-screen Now Playing is the closest surface | not reproduced |
| `ScreenSaverActivity` | not reproduced; full-screen Now Playing covers the same need | not reproduced |
| `Widget4X1Adv`, `WidgetService`, `WidgetPrefActivity` | no desktop widget host | not applicable |
| `MusicService` | in-process `AudioEngine` with a persistent app lifetime | adapted |
| `MediaBrowserServiceImpl` | no Android Auto host on desktop | not applicable |
| `CastProvider` | no Chromecast in an offline desktop build | not applicable |
| `TrimActivity` | not in this round | P3 |
| `RestoreActivity`, `DbCleanActivity` | not in this round | P3 |
| `EPCsCSVActivity` | Most Played CSV export implemented directly | partial |
| `IVActivity`, `TaskSaviour`, `SetLcWallActivity`, `FileUtilsActivity`, `RGReadCalcActivity` | platform helpers; no direct desktop equivalent | not reproduced |

The target also registers quick-settings (`QsService`), Bluetooth/USB and media-mount
receivers, notification widgets and Play/AndroidX components. Those are Android plumbing;
the desktop rebuild deliberately has no network, ad, analytics or crash-report component.

## 3. Library and folders

- **Folder tree is first-class.** The target tab's structure is selectable between
  hierarchical and linear: arrays `option_fldstruc` / `values_fldstruc` in
  `ev_90d0…`; strings `hierarchical_folders` and `folder_linear` in `ev_07b5…`.
  Rebuild: `folder_hierarchical` setting; hierarchical folder cards + breadcrumb, or a
  flat list.
- **Scan controls.** `scan_on_app_start`, `scan_hidden_folders`, `scan_specified_folders`,
  `folders_to_scan`, `excluded_folders…` strings in `ev_07b5…`. Rebuild: roots, excluded
  folders, include-only list, hidden-folder toggle, scan-on-start, manual rescan.
  Changes to either rule list trigger a new scan immediately.
- **MediaStore/audio formats.** The target scans local audio through its own scanner
  (`musicolet_scanner`, `normal_scan`, `advanced_scan` strings). Rebuild reads the files
  directly (no MediaStore on desktop) for `mp3`, `flac`, `ogg`, `opus`, `wav`, `m4a`,
  `aac` (plus a few extensions), using Lofty for tags/properties and a RIFF-INFO fallback.
- **No fake rows.** Every row/album/artist/folder count comes from a real directory scan.
  On a machine with no music, the program uses a bundled starter library of ten short
  original instrumental WAVs (generated for this rebuild, tagged through RIFF INFO) and
  scans it. If the bundle is missing it generates the same pieces in its data folder.
  These are real files with real tags and durations, not hard-coded UI rows.

## 4. Playback and queues

- **Multiple queues, max 20.** The target's own facts string says the maximum is 20,
  names must be unique, and no duplicate songs are allowed in a queue
  (`ans_facts_about_queues`, id `0x7f120066`, `ev_07b5…`). The overflow string says the
  first queue is removed (`max_queue_limit_reached_1st_deleted`, id `0x7f1202e7`,
  `ev_07b5…`). Rebuild: `MAX_QUEUES = 20`, oldest-created queue evicted, unique names,
  deduped inserts.
- **Each queue remembers its own place.** The same facts string says so; separate strings
  describe resuming and switching queues. Rebuild stores `index` + `position` per queue,
  saves them while playing, and resumes from them.
- **Queue source/merge policy.** Strings `merge_queue` / `replace_queue` and
  `ans_what_is_diff_queues_playlists` (`ev_07b5…`). Rebuild has a `Merge`/`Replace`
  setting used when a queue is explicitly created/loaded; clicking a new list creates a
  fresh uniquely-named queue instead of destroying the current one.
- **Gapless.** `k_b_glp` defaults to **true** in `default_preference.xml`; `MusicService`
  and `dd` read the same key. Rebuild pre-appends the next source to the same Rodio sink
  when the current track begins at position 0. Seeking rebuilds the current track first,
  so the seek cannot spill into the queued next source.
- **Previous button.** `PP_PBTNBH` default `"1"`: if the current position is over
  **10 000 ms** restart the song, otherwise play the previous one; value 0 is always
  previous (`MECHANICS.md` §8.2). Rebuild has exactly that switch and threshold.
- **Play counting.** `MINPBR` options are **10 / 30 / 50 / 90 %** and the default is
  **30 %**. The service counts a play when
  `played_ms >= duration_ms * percent / 100 - crossfade_ms` (crossfade default 0)
  (`MusicService.p()`, `MECHANICS.md` §8.2). Rebuild uses that formula.
- **Repeat / shuffle.** `RSM` is the song-end setting (1 stop, 2 next, 3 repeat song,
  4 load next + pause; default 2) and `RQM` is the queue-end setting (1 stop, 2 next
  queue, 3 repeat queue; default 2). The simple button uses
  off = (2,1), all = (2,3), one = (3,1). Rebuild exposes off / all / one, shuffle with
  the target's "all songs in the queue" scope, and jumps to the next queue by default.
- **Speed and pitch.** `k_f_plyspd4` default **1.00×** (stored 10000), range **0.50…3.00×**
  in **0.01** steps, seven presets; `k_f_plyptch4` range **0.50…2.00×**. The desktop build
  exposes the real speed range but Rodio couples pitch to speed; independent pitch is
  **not** implemented.
- **Sleep timer.** Custom hours/minutes, **+1 second**, five remembered times (default
  1:30 and 0:15) and five remembered song counts (default 10 and 5), N = 1…99,
  "wait until the song ends" and "fade out the last song" both default **false**. Fade
  covers the remaining current song and is skipped when ≤ 1000 ms remain
  (`zy.a`, `wm1`, `dd.l0`, `MECHANICS.md` §8.3). Rebuild implements all of it.
- **Android-only transport surfaces.** Notification and lock-screen controls, headset
  unplug events, Bluetooth/media-button receiver routing and Android Auto are provided by
  Android services/receivers (`MusicService`, `RemoteMediaControlReceiver`, `MyReceiver`,
  `MediaBrowserServiceImpl`, all in `ev_11b7…`). A plain desktop window cannot reproduce
  those OS surfaces; the player bar, keyboard shortcuts and in-app controls cover the
  same user actions. This is a platform limitation, not a hidden feature.

## 5. Equalizer

- `EqualizerActivity2.l0()` builds **five** built-in rows in this order: built-in
  speakers, speakers/headphones, headset/earphones, Bluetooth, USB headset; saved devices
  follow (`ev_bd5c…`). Rebuild: the same five profiles, stored separately and selected by
  chips.
- `c01.a(int)` has fixed tables for **3, 5, 10, 15 and 31 bands**; `c01.d(int)` gives the
  matching Q values (10 bands → Q **1.0**). The target can also read the device equalizer.
  The rebuild fixes the **10-band** table, which is the table used by the preset list:
  **31.5, 63, 125, 250, 500, 1 k, 2 k, 4 k, 8 k, 16 k Hz**.
- Rebuild DSP: RBJ peaking biquads at those ten frequencies with Q 1.0, a 90 Hz low shelf
  for bass boost, the target's three preamp modes (off / headroom 1…30 dB / boost 1…9 dB),
  the target's **0…200 balance** (100 = centre), a stereo cross-feed for the virtualizer,
  and a soft clamp. Slider changes are applied on the next audio block and persisted.
- Presets are the target's **22** ten-band tables from `c50.<clinit>`: Flat, Bass, Mid,
  Treble, Bass and Treble, Full Bass, Full Mid, Full Treble, Reduced Bass, Reduced Mid,
  Reduced Treble, No Bass, No Mid, No Treble, Classic, Dance, Live, Speaker, Pop, Rock,
  Soft, Techno. Gain range is **−15…+15 dB**. Names are the decoded target names, not
  invented ones.
- The headroom suggestion is the target's formula (`EqualizerActivity2.o0()`): if the
  adjusted peak is above 6 dB, suggest `min(peak − 6, 30)` dB. Bass contributes up to
  +15 dB below 200 Hz; mid bands are reduced 2 dB and treble 4 dB.
- The exact BassBoost/Virtualizer curves are Android device DSP; the desktop build keeps
  the target's ranges and documents its own low-shelf/cross-feed shapes as approximations.

## 6. Content management

- **Playlists** support create, rename, delete, add/remove songs, move up/down, and M3U
  import/export. Export writes an absolute-path `#EXTM3U` list; import resolves relative
  and absolute paths and matches them to scanned songs. The round-trip order is tested.
- **Tag editor** changes title/artist/album/genre/year/track and cover. Because the
  desktop build must not depend on every container's tag writer, edits are stored as
  per-path overrides that are re-applied on every scan; a rescan therefore keeps the new
  values and cover. Batch edits use the same override map.
- **Lyrics** are read in this order: override/edited text, embedded tag lyrics, sidecar
  `.lrc`. Editing writes a `.lrc` next to the audio and stores an override.
- **Bookmarks / notes** are stored per path with position + note; clicking jumps to the
  saved position.
- **Most played / recent / history** are computed from stored play counts, last-played
  timestamps and file creation timestamps. CSV export is available.
- **Search** filters titles, artists, albums, genres, folder paths, folders, artists and
  playlists as the user types.
- **ReplayGain** tags are read (`REPLAYGAIN_TRACK_GAIN`) and can scale the sink volume.
  Computing and writing missing gain tags is not implemented; this is a gap.

## 7. Appearance and language

- `option_base_theme` order is Light, Dark, Black (AMOLED), Day/Night, Follow system.
  The first-run default is **Black** (`THMR_BT = "2"`), and Day/Night uses **06:00–17:59**
  (`k_i_dstt` 360, `k_i_dedt` 1079). Rebuild implements all five modes. Accent colours
  and cover art are original, generated in code.
- Language: English and Simplified Chinese are complete for the main screens. A few
  file-format/unit labels remain English.
- Every cover in the rebuild is drawn from a deterministic local palette and shapes; no
  target image, logo, icon, font, audio or screenshot is used.

## 8. This round's P0 status

| Acceptance item | State |
|---|---|
| cold start, scan real files, folder view | done; screenshot verified |
| play/pause/previous/next/seek, continuous switching | done in code; pause, seek, auto-advance and gapless transition verified in screenshots |
| notification/lock screen/headset/media keys | not possible on desktop; keyboard shortcuts + player bar substitute |
| two named queues, switch, M3U round trip | queue plane done; M3U order unit-tested |
| equalizer live + persistence | DSP + JSON persistence done |
| sleep timer | done; fade + pause |
| tag edit + rescan | override persistence done |
| 720×1280 / 1080×1920 layout | 720×1280 and 1180×780 screenshots checked; 1080×1920 checked for overlap |

## 9. Known gaps / limits

- Notification, lock-screen, headset-unplug, media-key and home-screen widgets are OS
  integrations on Android and are not reproducible in an ordinary desktop window.
- Android Auto, Chromecast, audio trim, backup/restore, database clean and quick-settings
  tile were not built in this round (P3 on the request).
- Playlist drag reordering is implemented as explicit move up/down in the row menu; true
  pointer drag is not built.
- Pitch is coupled to speed; there is no independent pitch shifter.
- ReplayGain calculation/writing is not built, only tag reading and gain application.
- The target's equalizer band count is device-dependent; the rebuild fixes the 10-band
  table and does not expose the 3/5/15/31-band variants.
- The BassBoost/Virtualizer dB curves are device DSP; the rebuild's low-shelf and
  cross-feed shapes are documented approximations, while ranges and control values are
  exact.
- Play/pause fades (`0…4000 ms`, step 250) are read but not implemented.
- Queue names are compared case-insensitively in the rebuild; the target uses exact
  `String.equals`.

## 10. Android APK follow-up (round 3)

The earlier rounds delivered the desktop build only. This round adds a separate Android
implementation in `app/android/`, because a native Windows binary cannot be repackaged as
an APK. The Android project uses the requested stack exactly:

- Kotlin 2.1.20 + Jetpack Compose (BOM 2025.04.01), one `MainActivity` and Compose
  Navigation;
- Media3 1.6.1 `ExoPlayer` inside a foreground `MediaSessionService`, with notification,
  lock-screen and media-button controls and ExoPlayer's noisy-headphone/audio-focus
  handling;
- Room 2.7.1 for songs, playlists, queues, history and folder rules; Repository + Flow
  feeds the Compose UI;
- minSdk 26, targetSdk 35, compileSdk 35, Gradle 8.13 wrapper;
- no INTERNET permission and no network, ad, analytics or crash SDK. Media3's optional
  ACCESS_NETWORK_STATE declaration is removed by a manifest rule.

Verified on an Android 15 API 35 emulator: real MediaStore scan of MP3/M4A/OGG/FLAC,
folder breadcrumbs, playback progress, gapless transitions, seek, notification and
lock-screen cards, notification pause and hardware media keys, two named queues with a
switch, M3U export/import with identical queue IDs, a 10-band equalizer that survives a
process restart, a 5-minute sleep timer that pauses at the deadline, include/exclude
folder rules, playlist create/add/play, Chinese UI, and both 1080×1920 and 720×1280
layouts. Evidence and commands are in `app/checks/round-3/README.md`.

Still open for Android: headset-unplug was blocked from adb (protected broadcast), real
phone audio routing was not available, and P1–P3 features (tag/lyrics editing,
ReplayGain writing, bookmarks, widgets, quick-settings tile, Android Auto, cast, trim,
backup) are not built.

### Desktop sound follow-up

The desktop build now has a **Sound output** card at the top of Settings: it lists the
real Windows output devices, defaults to the system default, and has a **Test** button
that plays a short confirmation tone on the selected device. `lilt --list-audio` prints
the same list to the terminal. The workspace itself cannot hear the tone; the user still
needs to confirm the chosen device and the Windows volume mixer.

