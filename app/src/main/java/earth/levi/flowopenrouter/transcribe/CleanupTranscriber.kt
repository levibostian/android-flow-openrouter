package earth.levi.flowopenrouter.transcribe

import android.content.Context
import android.util.Log
import earth.levi.flowopenrouter.client.OpenRouterClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Wraps another [Transcriber] and, when the user enabled cleanup, sends the
 * transcript through an OpenRouter chat model to fix spelling and grammar before
 * forwarding it. On any cleanup failure the raw transcript is forwarded instead,
 * so transcription never fails because of cleanup.
 *
 * [delegateFactory] receives the callback the wrapped transcriber must report to;
 * callers build the real backend (on-device or OpenRouter STT) with it.
 */
class CleanupTranscriber(
    private val context: Context,
    private val callback: Transcriber.Callback,
    delegateFactory: (Transcriber.Callback) -> Transcriber,
) : Transcriber {

    companion object {
        private const val TAG = "CleanupTranscriber"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val delegateCallback = object : Transcriber.Callback {
        override fun onTranscript(text: String) {
            if (text.isBlank() || !TranscriptCleanup.isEnabled(context)) {
                callback.onTranscript(text)
                return
            }
            // Stay in the caller's processing state until cleanup finishes.
            scope.launch {
                val cleaned = try {
                    withContext(Dispatchers.IO) {
                        OpenRouterClient.cleanup(
                            text,
                            TranscriptCleanup.apiKey(context),
                            TranscriptCleanup.model(context),
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Cleanup failed, pasting raw transcript", e)
                    text
                }
                callback.onTranscript(cleaned.ifBlank { text })
            }
        }

        override fun onError(message: String) = callback.onError(message)
    }

    private val delegate = delegateFactory(delegateCallback)

    override fun start() = delegate.start()

    override fun stop() = delegate.stop()

    override fun release() {
        scope.cancel()
        delegate.release()
    }
}
