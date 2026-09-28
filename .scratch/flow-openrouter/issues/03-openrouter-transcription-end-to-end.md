# 03 — OpenRouter transcription end-to-end

**What to build:** the complete voice-to-text path. On bubble release, the buffered PCM is wrapped in a WAV header, base64-encoded, and POSTed to OpenRouter as an `input_audio` payload (key + model from the settings ticket 01). The transcript is parsed from the response and pasted into the focused text field (clipboard + `ACTION_PASTE`, flo's approach). Empty transcript → toast; bad key / wrong model / network failure → clear error toast; bubble returns to idle in every path. Request runs off the main thread with a generous read timeout (~120 s) for long clips.

**Blocked by:** 02 — Bubble UX + in-memory recording (also reads settings written by 01)

**Status:** ready-for-agent

- [x] **Payload shape validated from official docs, not assumed** — the curl smoke test was replaced by official docs + open-source client inspection (see below); user does live API+app testing
- [ ] Release → WAV-wrapped base64 audio → POST → transcript extracted from response (network, non-2xx, and malformed responses covered)
- [ ] Non-blank transcript pasted into the focused text field; app-in-background paste works
- [ ] Error UX: bad key, insufficient credits, invalid model, network failure, empty transcript each produce a distinct toast; bubble returns to idle (orange state never stuck)
- [ ] Full loop user-verified: real key + real model in a real app's text field → hold, speak, release → text appears at cursor

## API shape (verified against official docs)

**Endpoint: `POST https://openrouter.ai/api/v1/audio/transcriptions`** — OpenRouter's dedicated STT endpoint.

Confirmed by official docs (`/docs/guides/overview/multimodal/stt` + `/docs/guides/overview/multimodal/audio`) and open-source clients (`chrisbennight/stt-bench`, `jianchang512/pyvideotrans` — same endpoint + `input_audio` payload).

Request body:

```json
POST https://openrouter.ai/api/v1/audio/transcriptions
Authorization: Bearer sk-or-...
Content-Type: application/json

{
  "model": "openai/whisper-1",
  "input_audio": {
    "data": "<base64, NO data: URL prefix>",
    "format": "wav"
  },
  "language": "optional ISO-639-1 code"
}
```

Response — Whisper-style object, NOT chat completion:

```json
{ "text": "the transcribed text" }
```

⚠️ **Model catalog trap**: `GET /api/v1/models` does NOT list STT models (whisper-1, gpt-4o-transcribe, mai-transcribe-2, qwen3-asr, nova-3, …) — they exist and only resolve against the STT endpoint. They're visible in the `speech-to-text-models` collection page. Chat-audio models (`openai/gpt-audio-mini`) are NOT valid here — verified live: HTTP 400 "Model openai/gpt-audio-mini does not exist". This is why the default model moved from `gpt-audio-mini` (chat-audio) to `openai/whisper-1` (STT).

## Error handling (per-response toasts)

| Condition | Toast |
|---|---|
| HTTP 401 | Invalid API key |
| HTTP 402 | Out of OpenRouter credits |
| HTTP 404, or HTTP 400 `"does not exist"` in body | Model not found |
| Network/connect/timeout | Network error, try again |
| Parse error / malformed body | Unexpected API response |
| Success, empty text | No speech detected |
| Success, non-blank | paste text, brief success toast |