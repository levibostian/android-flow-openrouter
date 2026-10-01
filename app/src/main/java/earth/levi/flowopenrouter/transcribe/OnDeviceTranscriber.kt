package earth.levi.flowopenrouter.transcribe

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Transcription via the platform's on-device recognizer (API 31+). Audio is
 * processed locally and never leaves the device.
 *
 * [create] returns null when the device has no on-device recognizer; callers
 * fall back to [OpenRouterTranscriber] then.
 */
class OnDeviceTranscriber private constructor(
    private val recognizer: SpeechRecognizer,
    private val callback: Transcriber.Callback,
) : Transcriber {

    companion object {
        private const val TAG = "OnDeviceTranscriber"

        // Push-to-talk: silence must not end the session before the user releases
        // the bubble, so the recognizer's defaults (~1 s) are overridden.
        private const val SILENCE_TIMEOUT_MS = 300_000L

        fun create(context: Context, callback: Transcriber.Callback): OnDeviceTranscriber? {
            // Inline SDK guard (short-circuits) so lint accepts the API-31 calls.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            ) {
                return null
            }
            val recognizer = try {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create on-device recognizer", e)
                return null
            }
            return OnDeviceTranscriber(recognizer, callback)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onResults(results: Bundle?) {
            // Formatted hypothesis is first when EXTRA_ENABLE_FORMATTING is set.
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            Log.i(TAG, "Transcript: ${if (text.isBlank()) "(none)" else "'$text'"}")
            callback.onTranscript(text)
        }

        override fun onError(error: Int) {
            Log.e(TAG, "Recognition error: $error")
            callback.onError(messageFor(error))
        }
    }

    init {
        recognizer.setRecognitionListener(listener)
    }

    override fun start() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_TIMEOUT_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_TIMEOUT_MS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Punctuation + capitalization.
                putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
            }
        }
        try {
            recognizer.startListening(intent)
            Log.i(TAG, "Recognition started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recognition", e)
            callback.onError("Could not start recognition: ${e.message}")
        }
    }

    override fun stop() {
        recognizer.stopListening()
    }

    override fun release() {
        recognizer.destroy()
    }

    private fun messageFor(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "Microphone permission missing — enable it in the Flow app settings"
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network error"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy — try again"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "On-device model for your language isn't downloaded"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
            "Language not supported by the on-device recognizer"
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
        else -> "Recognition failed (error $error)"
    }
}
