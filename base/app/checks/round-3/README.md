# Round 3 checks — Android APK + PC sound

Everything here was produced in this workspace. The Android APK builds and was run on a
fresh Android 15 (API 35) emulator with four real audio files pushed into MediaStore:
MP3, M4A, OGG and FLAC.

## Build

```
cd app/android
./gradlew assembleDebug        # BUILD SUCCESSFUL
./gradlew testDebugUnitTest    # 5 tests, 0 failures
```

Installable debug APK: `app/android/app/build/outputs/apk/debug/app-debug.apk`
(also copied to `app/Lilt-1.0-debug.apk`, 21,115,179 bytes).

Manifest audit with `aapt2 dump badging`:

- package `dev.lilt.player`, minSdk 26, targetSdk 35
- READ_MEDIA_AUDIO, READ_EXTERNAL_STORAGE (≤32), POST_NOTIFICATIONS,
  FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PLAYBACK, WAKE_LOCK
- **no INTERNET** and no network SDK; Media3's optional ACCESS_NETWORK_STATE is
  removed by a manifest merge rule
- launcher `dev.lilt.player.MainActivity`
- foreground service `dev.lilt.player.playback.PlaybackService` with the Media3
  `MediaSessionService` action, plus `androidx.media3.session.MediaButtonReceiver`

## What the running build showed

| Check | Result | Evidence |
|---|---|---|
| MediaStore scan + folder tree | 4 tracks found, `Library / Music / LiltTest`, 0:20 + 0:14 + 0:16 + 0:18 | `01-home.png`, `02-folder.png`, `03-tracks.png` |
| Playback | state PLAYING, position advanced, AudioTrack started at 16 kHz | `05-player-audio.png`, `06-player-progress.png`, logcat |
| Gapless queue | Glass Horizon → Last Train → Northlight → Soft Static without a stop | dumpsys media_session timestamps, `06-player-progress.png` |
| Seek | dragged to 0:13 of 0:18; player position 11.6 s | `10-after-seek.png` |
| Media keys | KEYCODE_MEDIA_PLAY → PLAYING; KEYCODE_MEDIA_PAUSE → PAUSED | dumpsys media_session |
| Notification | foreground media notification, title/artist, previous / pause / next actions | `07-notification.png`, `37-notification-1080.png` |
| Notification button | tapping Pause → PAUSED at 12.7 s | `38-notification-paused.png` |
| Lock screen | media card with title, artist, previous, play/pause, waveform seek | `29-lock-screen.png` |
| Named queues | “Road” (4 tracks), “Single” (1 track), “Imported queue” (4 tracks); switching moves PLAYING | `13-two-queues.png`, `14-road-playing.png`, `17-imported-queue.png` |
| M3U round trip | exported `Road.m3u`, re-imported; queue IDs identical: `[1000000017, 1000000020, 1000000019, 1000000018]` | `Road-export.m3u`, SQLite query |
| Queue safety | ad-hoc folder playback no longer leaves a stale PLAYING badge | `ui-queues-adhoc.xml` |
| Equalizer | 10 bands 31.5 Hz–16 kHz, ±15 dB; 31 Hz set to +3.16 dB; persists after force-stop and shows +3.2 dB again | `23`–`25`, `shared_prefs/lilt_effects.xml` |
| Sleep timer | 5-minute timer set, countdown 4:57, deadline paused playback, countdown cleared | `26-sleep-timer-set.png`, `36-sleep-timer-paused.png` |
| Folder exclude | excluding `Music/LiltTest` hides all 4 tracks immediately; rules screen still lists folders, so it can be undone | `39-folder-rules.png`, `40-folder-excluded.png`, `41-folder-restored.png` |
| Include-only | no include rules hides everything; adding Include for `Music/LiltTest` shows exactly 4 tracks | `42-include-empty.png`, `43-include-visible.png` |
| Playlists | created “Favorites”, added Glass Horizon, opened detail and played | `30-playlist-created.png`, `31-playlist-detail.png` |
| 1080×1920 | home and player fit with no overlapping controls | `18-1080x1920-home.png`, `33-1080x1920-player.png` |
| 720×1280 | home, tracks, settings, queues fit; player labels were cut in the first run, fixed by an adaptive cover size | `19`–`22`, `27`, `28` |
| Chinese UI | whole settings screen switched to Chinese immediately | `28-720x1280-settings-zh.png` |
| PC sound output | new Sound output card with device picker and Test tone; system default detected | `44-pc-sound-output.png` |
| PC narrow layout | 720×1280 desktop screenshot | `45-pc-720x1280.png` |

## Not verified here

- **Headset unplug**: the `AUDIO_BECOMING_NOISY` broadcast is protected; `adb shell am
  broadcast` is refused with a SecurityException. The service enables ExoPlayer's
  `setHandleAudioBecomingNoisy(true)`, but the physical transition was not exercised.
- **Real phone audio routing**: the emulator reports a started AudioTrack; the actual
  speaker/headphone path on a physical phone was not available.
- **PC audible tone**: the workspace cannot hear its own output. The new device picker
  and Test button are the user-facing fix; the configured default here is the EDIFIER
  soundbar.
- **Not exercised**: same-name Replace/Merge through the UI, the 20-queue overflow
  eviction, a phone call/audio-focus interruption, and Opus/WAV/AAC files. The code paths
  exist but only the four formats and two distinct queue names above were run.
- Widgets, quick-settings tile, Android Auto, cast, tag editing, lyrics editing,
  ReplayGain writing, bookmarks/notes, backup/restore and audio trimming are outside
  this round (P1–P3), and are not faked.

## Commands used for the evidence

```
adb install -r app/android/app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant dev.lilt.player android.permission.READ_MEDIA_AUDIO
adb shell pm grant dev.lilt.player android.permission.POST_NOTIFICATIONS
adb shell content call --uri content://media --method scan_volume --arg external_primary
adb shell dumpsys media_session
adb shell dumpsys notification --noredact
adb shell wm size 720x1280 && adb shell wm density 320
adb shell wm size 1080x1920 && adb shell wm density 420
```
