# 02 — Bubble UX + in-memory recording

**What to build:** the floating bubble experience. The accessibility service shows the bubble when a text field is focused and hides it otherwise; press-and-hold starts `AudioRecord` (16 kHz mono 16-bit PCM), dragging moves the bubble, release stops recording. Instead of streaming chunks to a socket (as flo did), PCM goes into an in-memory buffer (`ByteArrayOutputStream`) capped to a sane max (e.g. 5 min). Bubble states: idle purple, recording red + pulse, processing orange. Release leaves the device in the processing (orange) state with a toast/log of buffered size — actual transcription wiring is ticket 03.

**Blocked by:** 01 — Scaffold fork + OpenRouter settings UI

**Status:** ready-for-agent

- [ ] Bubble appears on right edge when a text field is focused, disappears when focus leaves (same accessibility-event detection as flo)
- [ ] Hold = record (red + pulse), drag = reposition, release = stop; accidental taps don't record
- [ ] PCM buffered in memory on release (buffer size logged), not streamed anywhere
- [ ] Buffer capped (e.g. 5 min) — exceeding cap stops recording with a toast, release path still works
- [ ] No crash when mic permission missing mid-session; recording flag prevents concurrent records