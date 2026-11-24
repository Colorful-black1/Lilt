# Online lyrics

Lilt can fill missing lyrics from LRCLIB and, when the user explicitly asks, from an
OpenAI-compatible AI provider. The feature is optional and can be turned off in Settings.

## Data sent

For LRCLIB:

- track title
- artist
- album
- duration (used for matching)

For the AI fallback:

- the same metadata
- the prompt shown in the preview description

No audio file, file path, listening history, account or device identifier is uploaded.
There is no advertising, analytics or crash-reporting SDK in either build.

## Flow

1. A track starts without local lyrics (no sidecar/embedded text on desktop, no `lyrics`
   row on Android).
2. The app checks a local lookup cache. Not-found results are kept for 7 days,
   instrumentals for 30 days, ambiguous results for 1 day and network errors for 10
   minutes. A user who cleared a song's lyrics is remembered separately and is not
   queried again automatically.
3. LRCLIB `/api/search` is queried, with a second `q=` search if the structured search has
   no results.
4. Candidates are scored by normalized title, artist and duration:
   - a title+artist exact match within 2.5 s is written automatically;
   - close versions become a picker;
   - an instrumental record is marked as instrumental;
   - nothing close is cached as not found.
5. If nothing is found, the lyrics panel offers **AI lyrics**. The user must press it; AI
   is never called automatically. The result is previewed and validated before saving.

Desktop uses a `.lrc` sidecar plus the state cache. Android stores text in Room and keeps
lookup state in `lyrics_fetch_state` (Room v9).

## LRCLIB requirements

LRCLIB asks clients to identify themselves. Lilt sends:

```text
User-Agent: Lilt/0.1.0 (https://github.com/Colorful-black1/Lilt)
```

Requests are serialized on desktop and spaced by at least 350 ms; Android uses the same
spacing between the primary and fallback search. A `429` response is honoured with its
`Retry-After` value. Response bodies are bounded (2 MB for LRCLIB, 1 MB for AI).

LRCLIB occasionally returns `null` for fields such as `duration`. These fields are
treated as their default values instead of rejecting the whole search result.

LRCLIB is a free community service, not an official lyrics store. A miss does not mean the
track has no lyrics; it can be added to LRCLIB later. The local cache is deliberately
short for misses.

## AI providers

The provider list matches Tool_box's **设置 → 星轨链路**:

| Provider | Default base URL |
|---|---|
| DeepSeek | `https://api.deepseek.com/v1` |
| 智谱 GLM | `https://open.bigmodel.cn/api/paas/v4` |
| 小米 MiMo | `https://api.xiaomimimo.com/v1` |
| OpenAI GPT | `https://api.openai.com/v1` |
| OpenCode Go | `https://opencode.ai/zen/go/v1` |
| OpenCode Zen | `https://opencode.ai/zen/v1` |
| Kimi | `https://api.moonshot.cn/v1` |
| 通义千问 Qwen | `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| SiliconFlow | `https://api.siliconflow.cn/v1` |
| 自定义 | user supplied |

All use the OpenAI-compatible `POST {base}/chat/completions` and `GET {base}/models`
shape. Base URLs are normalized:

- a trailing `/chat/completions` is removed;
- `/v1` is added when the path has no version segment;
- remote HTTP is rejected; HTTP is accepted only for loopback (`localhost`, `127.0.0.1`,
  `::1`).

OpenCode Go additionally receives a stable `x-opencode-session` header; without it the
gateway returns 400.

### AI prompt, model coverage and logs

These are chat-completion endpoints, not a lyrics database. Lilt asks for a **draft** LRC
from the model's own knowledge, with approximate `[mm:ss.xx]` timestamps, and tells the
model that the user will review and edit the result. It does not require the model to
perform a web search or prove that every line was verified; a model without web access can
still return what it knows. `NOT_FOUND` is reserved for songs the model does not know.

The app therefore:

- only calls AI from a button;
- shows the provider and a warning;
- detects short copyright refusals and reports them instead of showing a refusal as lyrics;
- validates line count/timestamps and shows a timestamp/plain-text marker;
- requires the user to save the preview.

Model coverage varies. If one model returns nothing for a track, another model in the same
provider list may know it. The provider list is fetched from `/models`; if that fails, the
provider's built-in suggestion list is shown. “Test connection” also sends a minimal chat
completion, so it verifies generation rather than only authentication. The API key is
stored with the OS user protection (Windows DPAPI; Android Keystore AES/GCM) and is
excluded from backups.

On desktop, **Settings → Developer → Developer mode** writes AI request/result lines to
`developer.log`: provider, model, endpoint, elapsed time, HTTP status or error, and the
returned character/line count. The API key is never logged, and the log is appended rather
than truncated when developer mode is re-enabled.

## Privacy and control

- **Settings → Online lyrics** has a master switch. Turning it off stops both automatic
  LRCLIB lookups and the AI button.
- The AI key can be removed at any time.
- The lookup cache can be cleared; clearing it does not delete lyrics.
- Clearing a song's lyrics marks it as user-cleared, so it is not silently re-fetched.
- A backup includes lyrics text/source and AI provider/model/base URL, but never the API
  key.

## Copyright and service terms

Lyrics are user-requested content. The app does not bundle lyrics and does not publish
them. Users are responsible for complying with local copyright rules and with LRCLIB's and
their AI provider's terms. LRCLIB is queried as a free service; do not use Lilt to bulk
scrape it.

## Verification

See:

- `base/app/checks/round-8-a-online-lyrics-pc/`
- `base/app/checks/round-8-b-online-lyrics-android/`
- `base/app/checks/ai-lyrics-prompt-fix/`
