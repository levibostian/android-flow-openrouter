# 03 — OpenRouter transcription end-to-end

**What to build:** the complete voice-to-text path. On bubble release, the buffered PCM is wrapped in a WAV header, base64-encoded into a `data:audio/wav;base64,...` URL, and POSTed to `https://openrouter.ai/api/v1/chat/completions` (key + model from the settings ticket 01) as an `input_audio` content part with a verbatim-transcription prompt. The transcript is parsed from the response and pasted into the focused text field (clipboard + `ACTION_PASTE`, flo's approach). Empty transcript → toast; bad key / wrong model / network failure → clear error toast; bubble returns to idle in every path. Request runs off the main thread with a generous read timeout (~120 s) for long clips.

**Blocked by:** 02 — Bubble UX + in-memory recording (also reads settings written by 01)

**Status:** ready-for-agent

- [ ] Payload shape validated first with a curl smoke test against the user's real OpenRouter key (short audio clip) — `input_audio` part shape per live API, not assumed; fix PLAN.md payload if the API differs
- [ ] Release → WAV-wrapped base64 audio → POST → transcript extracted from `choices[0].message.content` (handle both plain string and array-of-text-parts forms; network, non-2xx, and malformed responses covered)
- [ ] Non-blank transcript pasted into the focused text field; app-in-background paste works
- [ ] Error UX: bad key/insufficient credits, invalid model, network failure, empty transcript each produce a distinct toast; bubble returns to idle (orange state never stuck)
- [ ] Full loop verified: real key + real model in a real app's text field → hold, speak, release → text appears at cursor