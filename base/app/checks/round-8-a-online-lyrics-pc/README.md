# PC online lyrics — automatic LRCLIB and manual AI

## Goal

Most of the library had no `.lrc` files. Typing lyrics one by one was not practical, so
Lilt now performs a background LRCLIB lookup when a track starts without lyrics, and offers
a manual AI fallback when LRCLIB has no verified match.

The player remains local-first:

- only title, artist, album and duration are sent;
- audio files, paths and listening history never leave the machine;
- the feature can be disabled in **Settings → Online lyrics**;
- no advertising, analytics or crash-reporting SDK was added.

## What was added

- `src/lyrics_fetch.rs`
  - LRCLIB `/api/search` client with a required `User-Agent`;
  - 350 ms spacing between requests, `429 Retry-After` handling and a bounded response
    size;
  - title/artist/duration scoring with an automatic-match threshold, an ambiguous
    candidate list and an instrumental result;
  - a persistent lookup cache for not-found, ambiguous, instrumental and error outcomes;
  - an OpenAI-compatible AI client covering the same providers as Tool_box's 星轨链路:
    DeepSeek, GLM, MiMo, OpenAI, OpenCode Go, OpenCode Zen, Kimi, Qwen, SiliconFlow and a
    custom endpoint;
  - OpenCode Go sends `x-opencode-session` and a non-generic User-Agent; base URLs are
    normalized (`/v1` added when missing, `/chat/completions` stripped, remote HTTP
    rejected);
  - AI results are cleaned, timestamp-checked and always previewed before saving.
- `src/secret.rs`
  - the AI key is encrypted with Windows DPAPI and stored outside `state.json`; it is not
    part of backups.
- `src/app.rs` / `src/ui/dialogs.rs`
  - the worker thread never blocks playback;
  - the now-playing lyrics panel shows fetching / not-found / instrumental / ambiguous /
    error states and source badges (`LRCLIB`, `AI`, `本地`, `已编辑`);
  - ambiguous results open a version picker with a preview and "use this version";
  - AI results open an editable preview dialog; saving writes the `.lrc` and marks the
    source as AI;
  - a manual edit marks the source as `已编辑`; clearing the lyrics stops future automatic
    lookups for that song;
  - existing lyrics are never overwritten by the automatic path.
- `src/ui/pages.rs`
  - an **Online lyrics** settings card: master switch, provider, base URL, model, model
    fetching, API-key save/remove, connection test, cache clear and a privacy note.
- New settings fields default safely for old `state.json` files; new `SongExtra` fields
  default to `local` / `autofetch = true`.

## Verification

```text
cargo test
# running 62 tests
# 61 passed; 0 failed; 1 ignored
```

The ignored test is a live LRCLIB smoke test:

```text
cargo test live_lrclib_smoke -- --ignored
# 1 passed
```

It queries `晴天 / 周杰伦 / 叶惠美 / 270 s`, receives synced LRCLIB records and selects a
high-confidence candidate.

The AI client was exercised against a local OpenAI-compatible stub through
`ureq`;
the request contained the expected model, system prompt, metadata and
`Authorization: Bearer sk-test`. The stub returned a 4-line LRC, which appeared in the
preview and was saved with source `ai`.

## Screenshots

| File | What it shows |
|---|---|
| `01-lrclib-auto.png` | First play of `晴天`: LRCLIB hit, synced lyrics and the `LRCLIB` source badge |
| `02-not-found.png` | A fabricated track: LRCLIB miss with AI/search-again actions |
| `03-ai-preview.png` | AI result preview with line count, timestamp check and provider label |
| `04-ai-saved.png` | AI result saved; the lyrics panel shows the `AI` badge and synced lines |
| `05-candidate-status.png` | A 285 s local edit produces 18 close LRCLIB versions instead of an automatic write |
| `06-candidate-picker.png` | Candidate picker with album, duration, LRC marker and preview |
| `07-settings-online-top.png` | Online lyrics switch, provider, base URL and model |
| `08-settings-online-ai.png` | Encrypted key state, test/cache actions and the cost/AI-accuracy note |

## Evidence details

- The synthetic library used a 270 s silent WAV so the duration matched the LRCLIB record.
- The automatic path wrote a `.lrc` sidecar and `SongExtra.lyrics`, `lyrics_source =
  lrclib`, `lyrics_fetched_at` and `lyrics_autofetch = true`.
- The ambiguous test used a 285 s file with the same title/artist; the top candidates were
  270 s and 299 s, so no automatic write happened.
- The AI test used a local stub at `127.0.0.1`; no real paid API was called.
- `alignment`/layout is unchanged from the previous round; the new card follows the
  existing settings card style.

## Packaged artifacts

```text
dist/Lilt-0.1.0-win64/Lilt.exe        11,838,976 bytes  (PE subsystem 2)
dist/Lilt-0.1.0-win64-portable.zip    18,440,382 bytes
```

Android is covered in `../round-8-b-online-lyrics-android/README.md`.
