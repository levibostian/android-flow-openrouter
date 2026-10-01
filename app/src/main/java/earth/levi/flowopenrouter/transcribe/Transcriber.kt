package earth.levi.flowopenrouter.transcribe

/**
 * Push-to-talk transcription backend. The caller drives capture: [start] while
 * the bubble is held, [stop] on release. Each [stop] is answered by exactly one
 * callback — [Callback.onTranscript] or [Callback.onError].
 *
 * Implementations are created and used on the main thread and decide nothing
 * about UI; the caller owns the bubble and the paste.
 */
interface Transcriber {

    fun start()

    fun stop()

    /** Releases native/system resources. The instance is unusable afterwards. */
    fun release()

    interface Callback {
        /** [text] is blank when audio was captured but no speech was recognized. */
        fun onTranscript(text: String)

        /** [message] is user-facing. */
        fun onError(message: String)
    }
}
