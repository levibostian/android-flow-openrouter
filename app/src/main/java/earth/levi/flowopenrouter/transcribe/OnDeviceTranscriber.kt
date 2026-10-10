package earth.levi.flowopenrouter.transcribe

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Transcription via the platform's on-device recognizer (API 31+). Audio is
 * processed locally and never leaves the device.
 *
 * Push-to-talk must not end on a pause, but the recognizer's endpointer does
 * exactly that. On API 33+ we ask for a *segmented* session, which reports each
 * phrase via [RecognitionListener.onSegmentResults] and only ends when the
 * caller stops it. Recognizers may ignore segmented mode, so if the session
 * still terminates while the user is holding we transparently restart it and
 * stitch the pieces together.
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

        // Silence up to this long only splits a segment; it must not end the hold.
        private const val SEGMENT_SILENCE_MS = 1_500L

        // Some recognizers answer stopListening() with a trailing segment on a later
        // callback. Wait briefly for it, then finalize whatever we have.
        private const val RELEASE_GRACE_MS = 2_000L

        // A restarted session cannot reuse the previous one instantly; give the
        // recognizer a moment to tear down or it answers ERROR_RECOGNIZER_BUSY.
        private const val RESTART_DELAY_MS = 300L

        // Transient ends that mean "nothing said yet", not "give up".
        private val RETRYABLE_ERRORS = setOf(
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_CLIENT,
        )

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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Final segments from one or more (restarted) sessions, plus the latest
    // non-final hypothesis, combined at release.
    private val segments = mutableListOf<String>()
    private var livePartial = ""

    private var stopping = false
    private var finished = false
    private var restartJob: Job? = null
    private var releaseJob: Job? = null

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        // Restarting here would cancel the session before its final hypothesis.
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            if (finished) return
            livePartial = firstResult(partialResults)
        }

        override fun onSegmentResults(segmentResults: Bundle) {
            if (finished) return
            appendSegment(firstResult(segmentResults))
        }

        override fun onEndOfSegmentedSession() {
            if (finished) return
            // Session ended on its own while the user still holds: keep listening.
            if (stopping) finalize() else restart()
        }

        override fun onResults(results: Bundle?) {
            if (finished) return
            appendSegment(firstResult(results))
            if (stopping) finalize() else restart()
        }

        override fun onError(error: Int) {
            if (finished) return
            Log.e(TAG, "Recognition error: $error")
            if (stopping) {
                // Release already happened; keep whatever we captured. A transient
                // end (e.g. ERROR_CLIENT from stopListening) is not a failure.
                if (merged().isNotBlank() || error in RETRYABLE_ERRORS) finalize()
                else fail(messageFor(error))
                return
            }
            if (error in RETRYABLE_ERRORS) restart() else fail(messageFor(error))
        }
    }

    init {
        recognizer.setRecognitionListener(listener)
    }

    override fun start() {
        restartJob?.cancel()
        releaseJob?.cancel()
        segments.clear()
        livePartial = ""
        stopping = false
        finished = false
        try {
            recognizer.startListening(recognizerIntent())
            Log.i(TAG, "Recognition started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recognition", e)
            fail("Could not start recognition: ${e.message}")
        }
    }

    override fun stop() {
        if (finished) return
        stopping = true
        try {
            recognizer.stopListening()
        } catch (e: Exception) {
            Log.e(TAG, "stopListening failed", e)
        }
        // If no trailing callback arrives, finalize so the bubble never hangs.
        releaseJob = scope.launch {
            delay(RELEASE_GRACE_MS)
            finalize()
        }
    }

    override fun release() {
        stopping = true
        restartJob?.cancel()
        releaseJob?.cancel()
        scope.cancel()
        recognizer.destroy()
    }

    private fun recognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Segmented mode: silence splits phrases instead of ending the session,
                // and it is required for punctuation/capitalization formatting.
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    SEGMENT_SILENCE_MS,
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    SEGMENT_SILENCE_MS,
                )
                putExtra(
                    RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                )
                putExtra(
                    RecognizerIntent.EXTRA_ENABLE_FORMATTING,
                    RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY,
                )
            }
        }

    /** Session ended but the user is still holding: start a fresh one and keep the text. */
    private fun restart() {
        if (stopping || finished) return
        restartJob?.cancel()
        restartJob = scope.launch {
            delay(RESTART_DELAY_MS)
            if (stopping || finished) return@launch
            try {
                recognizer.startListening(recognizerIntent())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart recognition", e)
                fail("Could not restart recognition: ${e.message}")
            }
        }
    }

    private fun appendSegment(text: String) {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) segments += trimmed
        // The reliable final supersedes the shaky partial for the same speech.
        livePartial = ""
    }

    private fun merged(): String {
        val parts = segments.toMutableList()
        val partial = livePartial.trim()
        if (partial.isNotEmpty() && parts.lastOrNull() != partial) parts += partial
        return parts.joinToString(" ").trim()
    }

    private fun finalize() {
        if (finished) return
        finished = true
        releaseJob?.cancel()
        val text = merged()
        Log.i(TAG, "Transcript: ${if (text.isBlank()) "(none)" else "'$text'"}")
        callback.onTranscript(text)
    }

    private fun fail(message: String) {
        if (finished) return
        finished = true
        releaseJob?.cancel()
        Log.e(TAG, "Recognition failed: $message")
        callback.onError(message)
    }

    private fun firstResult(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull().orEmpty()

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
