# AI lyrics failure, prompt and logs

## Symptom

Every manual **AI lyrics** request ended in “AI did not find verified lyrics”.
The PC developer log had no AI lines at all.

## What was checked

Configuration on the test machine:

```text
provider = opencode-go
base_url = https://opencode.ai/zen/go/v1
model    = deepseek-v4.1-flash
```

The already stored API key was decrypted only in memory. It was never printed,
written to a log or included in this evidence.

```text
GET /models: HTTP 200, 37 models, configured model present
```

The old prompt required the model to “verify” lyrics and to use web search when
available. OpenAI-compatible `chat/completions` has no web-search tool, so a
cautious model could answer `NOT_FOUND` for every track, and Lilt treated that
as a normal “no lyrics” result rather than an error. Missing tags also sent
`Artist - Title` as the title and `Unknown artist` as the artist, which made the
request harder to match.

## Fix

- The prompt now asks for a **draft** LRC from the model's own knowledge, with
  approximate `[mm:ss.xx]` timestamps and an explicit statement that the user
  will review and edit it. It no longer requires web search or verified timing.
- `NOT_FOUND` is reserved for a song the model does not know at all.
- Requests use `temperature: 0`.
- Short copyright refusals are detected and returned as an error instead of
  being shown as lyrics.
- The PC developer log records every AI request and result:
  provider, model, endpoint, elapsed time, HTTP status/error, and returned
  character/line count. The API key is redacted.
- `developer.log` is appended instead of truncated when developer mode is
  re-enabled.
- “Test connection” now sends a real minimal chat completion, not just
  `GET /models`.
- LRCLIB records with a `null` field (for example `duration`) are now accepted
  instead of rejecting the whole search response.

## Evidence

`ai-prompt-check.txt` records a live check with the real endpoint. The same Rust
`ureq` client used by the app returned:

```text
"晴天" / "周杰伦" -> LRC, 33 lines, 1350 characters
```

"After Rain" / "Aimer" still returned `NOT_FOUND`; the model does not know that
track, so model coverage remains a limit. This is not a blocker for the normal
flow: LRCLIB search with the corrected metadata returns 20 records, including a
293-second synced version that matches the 4:53 file. LRCLIB remains the primary
source; AI is only the manual fallback when LRCLIB has nothing.

The unit tests cover the draft prompt, `temperature: 0`, refusal handling,
`NOT_FOUND` cleaning and null-tolerant LRCLIB parsing:

```text
PC:      67 passed, 0 failed, 1 ignored
Android: 60 passed, 0 failed
```

## Evidence files

| File | What it shows |
|---|---|
| `ai-prompt-check.txt` | Real `/models` and draft-prompt result; key and lyrics text are not included |
| `pc-tests.txt` | PC unit-test summary |
| `android-tests.txt` | Android unit-test summary |
