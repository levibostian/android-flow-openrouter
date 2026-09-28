# Plan — Flow (flo fork with OpenRouter speech-to-text)

## Goal

Android app: floating mic bubble over any app. Hold to speak, release → speech transcribed and pasted into the focused text field.

**Based on** [zackify/flo](https://github.com/zackify/flo) (MIT-free, public). Same UX, same structure — but flo streams audio to a self-hosted Wyoming Whisper server over TCP; **we replace that with OpenRouter's API** so the user supplies their own OpenRouter API key + model, no server to run.

## What we keep from flo (upstream)

- `BubbleView` — custom bubble with 3 states: idle (purple), recording (red + pulse), processing (orange)
- `FloAccessibilityService` — shows bubble when a text field is focused, hold-to-record, drag to reposition, paste transcript via clipboard + `ACTION_PASTE`
- `MainActivity` + layout — settings + permission status screen (mic, overlay, accessibility)
- Gradle setup (Kotlin, AGP 8.7.3, coroutines, appcompat, material3)
- Record loop: mic → 16 kHz mono 16-bit PCM via `AudioRecord`

## What changes

| flo | Flow |
|---|---|
| `WyomingClient` (TCP socket, custom protocol) | `OpenRouterClient` (HTTP POST, JSON) |
| Settings: host, port, language | Settings: API key, model (default `openai/gpt-audio-mini`) |
| Streams PCM chunks to server during recording | Buffers PCM in memory; one request on release |
| — | WAV header wrap + base64 data URL |

**Removals:** `FloOverlayService` (never declared in upstream manifest — dead code), Wyoming protocol code, host/port/language settings. Language is dropped: gpt-audio auto-detects language.

**Rename:** package `com.flo.whisper` → `earth.levi.flowopenrouter`, app label → "Flow". Done at scaffold time (tiny blast radius, 5 files).

## How transcription works (OpenRouter)

OpenRouter's dedicated STT endpoint is `POST /api/v1/audio/transcriptions` (per official docs `guides/overview/multimodal/stt` + collection `speech-to-text-models`). STT models (`openai/whisper-1`, `openai/gpt-4o-transcribe`, `microsoft/mai-transcribe-2`, `qwen/qwen3-asr-*`, `deepgram/nova-3`, …) are **not returned by `GET /api/v1/models`** — they only resolve against the STT endpoint (live check: `openai/gpt-audio-mini` → 400 "does not exist"). Chat-audio models (`gpt-audio`/`gpt-audio-mini`) are NOT STT models and don't work here. Payload shape confirmed by official docs + open-source clients (`stt-bench`, `pyvideotrans`):

```json
POST https://openrouter.ai/api/v1/audio/transcriptions
Authorization: Bearer sk-or-...
Content-Type: application/json

{
  "model": "openai/whisper-1",
  "input_audio": {
    "data": "<base64, no data: URL prefix>",
    "format": "wav"
  }
}
```

Response transcript: `{ "text": "..." }` (plain string). `input_audio.data` is raw base64, NOT a `data:` URI (docs are explicit). Per-minute model pricing (`whisper-1`); note docs: split recordings longer than ~1 min processing time — upstream providers time out after 60 s/request.

## Flow (runtime)

1. User taps text field in any app → accessibility event → bubble appears (right edge)
2. Hold bubble: `AudioRecord` streams PCM → appended to in-memory buffer (`ByteArrayOutputStream`)
3. Release: stop mic → wrap PCM in 44-byte RIFF/WAV header → base64 → POST `input_audio` to `/audio/transcriptions` (read timeout ~120 s for long clips; done off main thread)
4. Parse response `text` → clipboard + `ACTION_PASTE` into focused field
5. Errors (bad key, wrong model, network, empty transcript) → toast, bubble back to idle

## Decisions & tradeoffs

- **Buffer-then-request** instead of streaming: OpenRouter has no streaming STT; one request per utterance is the whole design. Memory is fine (60 s ≈ 1.9 MB WAV → ~2.5 MB base64).
- **`HttpURLConnection`** over OkHttp: one POST, no new dependency beyond coroutines (already present). If retries/multipart are ever needed, swap in OkHttp.
- **API key in `SharedPreferences`** (plaintext) — same pattern as flo's settings; acceptable for personal sideloaded app. Note: don't ship key in APK or VCS.
- **Default model `openai/whisper-1`** (canonical per docs, priced per audio minute, cheapest reliable STT). User-configurable field, no picker — text field keeps it future-proof.
- **Keep flo's dual-path clipboard+paste** for transcript insertion — works across apps without IME hacks.
- **Dead code removed, not ported**: upstream `FloOverlayService` is absent from its manifest, so only the accessibility service runs. One bubble, one service.

## Non-goals

- Streaming/server-side partial transcripts (mic → → gpt-audio is request/response)
- Language selection UI (auto-detect; re-add as prompt tweak later if needed)
- Release/build signing, Play Store packaging

## Ticket map (`.scratch/flow-openrouter/issues/`)

1. **Scaffold fork + OpenRouter settings UI** — builds, installs, key/model saved
2. **Bubble UX + in-memory recording** — bubble lifecycle, hold-to-record, buffer
3. **OpenRouter transcription end-to-end** — WAV wrap, POST, parse, paste, error UX

Linear chain: 01 → 02 → 03.