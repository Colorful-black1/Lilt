# Android online lyrics 鈥?LRCLIB, candidates and AI preview

## Goal

Match the desktop behaviour on Android:

- when a song starts without lyrics, query LRCLIB in the background;
- store a high-confidence match in Room and update the notification/overlay/player;
- show a version picker for close matches;
- offer a manual AI button when LRCLIB has nothing;
- preview AI output before saving;
- keep audio and paths on the device, and keep the feature switchable.

## What was added

- **Manifest** 鈥?`INTERNET` and `ACCESS_NETWORK_STATE` for the optional lyrics feature,
  plus a network-security config that permits cleartext only for a loopback AI server.
  No advertising, analytics or crash-reporting SDK.
- **Room v9**
  - `lyrics.source` and `lyrics.fetchedAt`;
  - new `lyrics_fetch_state` (`status`, `lastAttemptAt`, `nextRetryAt`,
    `candidateCount`, `candidates`, `error`, `autoDisabled`);
  - migration 8鈫? and a migration test.
- **`library/OnlineLyrics.kt`** 鈥?pure JVM matching/model helpers:
  normalization, scoring, automatic/ambiguous/instrumental decisions, AI text cleanup,
  LRC inspection, base-URL normalization and the same provider preset list as the desktop
  app (DeepSeek, GLM, MiMo, OpenAI, OpenCode Go/Zen, Kimi, Qwen, SiliconFlow, custom).
- **`data/OnlineLyricsClient.kt`** 鈥?`HttpURLConnection` client:
  - LRCLIB search + fallback query, 350 ms spacing, bounded bodies;
  - OpenAI-compatible `/models` and `/chat/completions`;
  - `Authorization: Bearer`, custom User-Agent, OpenCode Go session header;
  - loopback HTTP allowed for a local model server.
- **`data/OnlineLyricsEngine.kt`** 鈥?one shared lookup path used by the playback service
  (automatic) and the UI (retry). It honours the cache TTLs, never overwrites existing
  lyrics and records misses, instrumentals, ambiguity and errors.
- **`data/SecretStore.kt`** 鈥?AES/GCM key in the Android Keystore; the ciphertext lives in
  `lilt_secrets.xml` and is excluded from backups.
- **`PlaybackService`** 鈥?triggers the background lookup on media-item transitions, so it
  also works with the screen off or the app in the background. Only one lookup runs at a
  time; a track change cancels the previous job.
- **Player screen** 鈥?source badge (`LRCLIB` / `AI` / `Local` / `Edited`), fetching/miss/
  ambiguous/error states, **AI lyrics**, **Choose version** and **Search again** actions.
  AI opens an editable preview; candidates open a picker with album/duration/LRC markers
  and a preview.
- **Settings** 鈥?master switch, provider, base URL, model + fetch models, encrypted API
  key, test connection, cache clear and the privacy/cost note.
- **Backup** 鈥?lyrics now carry source/fetchedAt; restore clears the transient fetch
  state. The AI key is deliberately not backed up.
- **User clears lyrics** 鈥?the fetch state records `autoDisabled`, so the next play does
  not silently re-add the song's lyrics. **Search again** / **Get lyrics** clears that flag.

## Verification

Unit tests:

```text
./gradlew testDebugUnitTest
# 56 tests passed
```

The new tests cover normalization/ranking/automatic/ambiguous/instrumental decisions,
AI text cleanup, LRC inspection, base-URL normalization, candidate JSON round-trips, and
the OpenAI-compatible HTTP client against a local `ServerSocket` (models + chat).

Room migrations:

```text
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.lilt.player.data.LiltDatabaseMigrationTest
# Starting 8 tests on lilt35(AVD)
# Finished 8 tests
```

The 8鈫? case checks the existing lyrics text/offset, the new `source`/`fetchedAt` defaults
and the new fetch-state table.

Emulator flow (Android 15, 1080脳1920):

1. Installed the debug APK and granted `READ_MEDIA_AUDIO` / notifications.
2. Added a 295 s `Hello.wav` to MediaStore and set the virtual tags to
   `Hello / Adele / 25`.
3. Played it. The service wrote:
   `1000000452|lrclib|0|2504|[00:05.84] Hello, it's me 鈥
   and deleted the fetch state.
4. Replaced the cached state with a not-found state and no lyrics; the panel showed
   `LRCLIB did not find a verified match for this track.` with **AI lyrics**.
5. Configured a loopback OpenAI-compatible stub (`adb reverse tcp:8765 tcp:8765`) and
   saved a test key through `SecretStore`; the stub received the model, prompt and Bearer
   token. The AI preview showed `4 line(s) 路 timed LRC`; saving wrote
   `1000000452|ai|0|114|鈥.
6. Seeded an ambiguous state with two real `Hello / Adele` LRCLIB records; the panel showed
   **Choose version**, the picker listed both versions with a preview, and choosing one
   wrote `1000000452|lrclib|0|2504|鈥.

Evidence:

| File | What it shows |
|---|---|
| `01-auto-lrclib-player.png` | Automatic LRCLIB lyrics, highlighted line and `LRCLIB` badge |
| `02-settings-online.png` | Provider/base/model, saved key, test/cache and the floating lyrics card |
| `03-ai-preview.png` | AI preview dialog with 4 lines, timed LRC and save/cancel |
| `04-candidate-picker.png` | Two candidate versions, album/duration/LRC marker and preview |
| `android-tests.txt` | Unit-test result |
| `android-migrations.txt` | Room 1鈫? migration test result |
| `android-permissions.txt` | `aapt dump permissions` showing only the expected permissions |
| `database.txt` | The observed Room rows for the three flows |

## Permissions

`aapt dump permissions app-debug.apk` reports the existing media/notification/service/
overlay permissions plus:

```text
android.permission.INTERNET
android.permission.ACCESS_NETWORK_STATE
```

There is no advertising, analytics or crash-reporting package in the APK. The app still
plays local files without a network connection; online lyrics simply fail quietly and can
be retried later.

## APK

```text
base/app/Lilt-1.0-debug.apk    22,894,438 bytes
```

`aapt dump permissions` output is in `android-permissions.txt`. The APK still plays local
files offline; online lyrics simply fail quietly without a network.

## Notes / limits

- The provider list is the one used by Tool_box's 鏄熻建閾捐矾 settings.
- A model without web search can only answer from its trained knowledge, so lyrics may be
  incomplete. The preview dialog marks this and requires confirmation.
- The AI key is device-bound; reinstalling or restoring on another device requires
  re-entering it.
- A loopback `http://127.0.0.1` AI server is allowed by the network-security config for
  local testing/Ollama; all remote endpoints must use HTTPS.
- Candidate data is cached in Room so the picker does not need a second network request;
  it is cleared when the lyrics are saved or the cache is cleared.
