# 01 — Scaffold fork + OpenRouter settings UI

**What to build:** the app's own identity, buildable APK, and the settings screen where the user enters their OpenRouter API key and model. Copy flo's project into this repo with the package renamed (`com.flo.whisper` → `earth.levi.flowopenrouter`, label "Flow"), delete the Wyoming TCP client and the dead `FloOverlayService`, and convert the settings screen from host/port/language to API key (masked input) + model (text input, default `openai/gpt-audio-mini`), persisted in SharedPreferences. `./gradlew assembleDebug` succeeds and the APK installs; opening the app shows the permission status screen (mic, overlay, accessibility) working as in flo.

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] Project builds with `./gradlew assembleDebug` (compileSdk 35, Java 17) and APK installs
- [ ] Package + applicationId renamed to `earth.levi.flowopenrouter` (accessibility service constant in MainActivity updated to match), no `com.flo.whisper` references remain (except icon/vector resources reused as-is)
- [ ] Wyoming client code and `FloOverlayService` removed from source and manifest (manifest never declared the overlay service upstream; keep only the accessibility service)
- [ ] Settings screen: API key input (masked, `password` input type) + model input (default `openai/gpt-audio-mini`), saved to SharedPreferences, loaded on open; host/port/language fields gone
- [ ] Permission status panel + enable buttons (mic, overlay, accessibility) behave as in flo; app still opens without crash when services/permissions missing