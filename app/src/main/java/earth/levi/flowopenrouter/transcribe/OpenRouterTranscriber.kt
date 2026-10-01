package earth.levi.flowopenrouter.transcribe

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import earth.levi.flowopenrouter.client.OpenRouterClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Transcription via OpenRouter's STT endpoint. Buffers 16 kHz mono PCM while the
 * bubble is held, then POSTs a single WAV on release — OpenRouter has no
 * streaming STT, so one request per utterance is the whole design.
 *
 * Only used when the device has no on-device recognizer; see [OnDeviceTranscriber].
 */
class OpenRouterTranscriber(
    private val context: Context,
    private val callback: Transcriber.Callback,
) : Transcriber {

    companion object {
        private const val TAG = "OpenRouterTranscriber"
        private const val SAMPLE_RATE = 16000
        private const val BYTES_PER_SECOND = SAMPLE_RATE * 2 // 16-bit mono
        // 5 min cap ≈ 9.6 MB PCM; keeps in-memory recording bounded.
        private const val MAX_RECORD_SECONDS = 300
        private const val MAX_BUFFER_BYTES = BYTES_PER_SECOND * MAX_RECORD_SECONDS
        private const val PREFS_NAME = "flow_prefs"
        private const val PREF_API_KEY = "flow_api_key"
        private const val PREF_MODEL = "flow_model"
        private const val DEFAULT_MODEL = "openai/whisper-1"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var audioRecord: AudioRecord? = null
    private var pcmBuffer = ByteArrayOutputStream()
    private var captureJob: Job? = null

    @Volatile
    private var capturing = false

    @SuppressLint("MissingPermission")
    override fun start() {
        capturing = true
        pcmBuffer = ByteArrayOutputStream()

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(BYTES_PER_SECOND)

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            ).also { it.startRecording() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioRecord", e)
            capturing = false
            callback.onError("Could not start recording: ${e.message}")
            return
        }
        audioRecord = record
        Log.i(TAG, "Recording started at ${record.sampleRate} Hz")

        captureJob = scope.launch {
            val chunkSize = BYTES_PER_SECOND // 0.5 s of 16-bit mono
            val buffer = ByteArray(chunkSize)
            try {
                withContext(Dispatchers.IO) {
                    while (capturing && isActive) {
                        val read = record.read(buffer, 0, chunkSize)
                        if (read > 0) {
                            pcmBuffer.write(buffer, 0, read)
                            if (pcmBuffer.size() >= MAX_BUFFER_BYTES) {
                                Log.i(TAG, "Recording hit $MAX_RECORD_SECONDS s cap")
                                break
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recording error", e)
                capturing = false
                callback.onError("Recording error: ${e.message}")
            }
        }
    }

    override fun stop() {
        if (!capturing) return
        capturing = false

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        val pcm = pcmBuffer.toByteArray()
        Log.i(TAG, "Recording stopped, ${pcm.size} bytes (${pcm.size / BYTES_PER_SECOND} s)")

        scope.launch {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val apiKey = prefs.getString(PREF_API_KEY, "") ?: ""
                val model = prefs.getString(PREF_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL

                val transcript = withContext(Dispatchers.IO) {
                    OpenRouterClient.transcribe(pcm, apiKey, model)
                }
                callback.onTranscript(transcript)
            } catch (e: OpenRouterClient.OpenRouterException) {
                Log.e(TAG, "Transcription failed", e)
                callback.onError(e.message ?: "Transcription failed")
            }
        }
    }

    override fun release() {
        capturing = false
        captureJob?.cancel()
        scope.cancel()
        audioRecord?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        audioRecord = null
    }
}
