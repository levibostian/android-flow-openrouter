# Flow

Voice-to-text Android app: a floating mic bubble over any app. Hold to speak, release — speech is transcribed and pasted into the focused text field.

Transcription runs through [OpenRouter](https://openrouter.ai) using a GPT-Audio model (`openai/gpt-audio-mini` by default). You supply your own OpenRouter API key — no server to run.

Fork of [zackify/flo](https://github.com/zackify/flo): same bubble UX and accessibility-service approach, but flo's Wyoming Whisper TCP client is replaced with a single HTTP POST to OpenRouter.

## How it works

1. An accessibility service detects when you focus a text field and shows a floating mic bubble on the right edge
2. Press and hold the bubble to record; release to transcribe
3. The transcript is pasted at your cursor position (clipboard + paste action)

## Setup

1. Build and install the APK
2. Open Flow and enter your OpenRouter API key (and model, if not the default)
3. Grant microphone and overlay permissions
4. Enable the Flow accessibility service
5. Open any app, tap a text field — the purple mic bubble appears

## Building

Requires Android SDK with platform 35 and Java 17.

```bash
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

## Architecture

```
app/src/main/java/earth/levi/flowopenrouter/
├── ui/MainActivity.kt              — Settings (API key, model, permissions)
├── service/FloAccessibilityService.kt — Text field detection, bubble, recording, paste
└── overlay/BubbleView.kt           — Custom bubble with recording/processing states
```
