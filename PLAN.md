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

Whisper-class audio models are gone from OpenRouter's catalog; the audio→text path is the **GPT-Audio** models (`openai/gpt-audio`, `openai/gpt-audio-mini` — verified present in `GET /api/v1/models`, `input_modalities: [text, audio]`, `output_modalities: [text, audio]`, text-only output when requested as a normal chat completion). Base URL `https://openrouter.ai/api/v1/chat/completions`, OpenAI-compatible.

Payload (audio as `input_audio` content part):

```json
POST https://openrouter.ai/api/v1/chat/completions
Authorization: Bearer sk-or-...
Content-Type: application/json

{
  "model": "openai/gpt-audio-mini",
  "messages": [
    {
      "role": "user",
      "content": [
        { "type": "text", "text": "Transcribe the audio verbatim. Respond with only the transcript, no commentary." },
        { "type": "input_audio", "input_audio": { "data": "data:audio/wav;base64,<base64>", "format": "wav" } }
      ]
    }
  ]
}
```

Response transcript: `choices[0].message.content` (text string; also handle array-of-text-parts shape defensively — gpt-audio can return content as parts). Extract text parts, concatenate, paste.

⚠️ The exact `input_audio` part shape is the one live-API uncertainty — verifiable only with a key. Ticket 03 **validates it with a curl smoke test against the user's real key before wiring UI** (request minimal audio, e.g. a 2 s beep/speech).

## Flow (runtime)

1. User taps text field in any app → accessibility event → bubble appears (right edge)
2. Hold bubble: `AudioRecord` streams PCM → appended to in-memory buffer (`ByteArrayOutputStream`)
3. Release: stop mic → wrap PCM in 44-byte RIFF/WAV header → base64 → data URL → POST to OpenRouter (read timeout ~120 s for long clips; done off main thread)
4. Parse `choices[0].message.content` → clipboard + `ACTION_PASTE` into focused field
5. Errors (bad key, wrong model, network, empty transcript) → toast, bubble back to idle

## Decisions & tradeoffs

- **Buffer-then-request** instead of streaming: OpenRouter has no streaming STT; one request per utterance is the whole design. Memory is fine (60 s ≈ 1.9 MB WAV → ~2.5 MB base64).
- **`HttpURLConnection`** over OkHttp: one POST, no new dependency beyond coroutines (already present). If retries/multipart are ever needed, swap in OkHttp.
- **API key in `SharedPreferences`** (plaintext) — same pattern as flo's settings; acceptable for personal sideloaded app. Note: don't ship key in APK or VCS.
- **Default model `openai/gpt-audio-mini`** (cheapest audio input tier, `$0.60/M` input audio tokens per model listing). User-configurable field, no picker — text field keeps it future-proof.
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