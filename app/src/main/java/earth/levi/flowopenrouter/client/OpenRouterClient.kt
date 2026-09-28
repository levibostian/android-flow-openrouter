package earth.levi.flowopenrouter.client

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal OpenRouter transcription client.
 *
 * Endpoint: POST /api/v1/audio/transcriptions (Whisper-style, not chat completions).
 * Shape verified against open-source clients (stt-bench, pyvideotrans) — see ticket 03.
 * One request per utterance; no streaming.
 */
object OpenRouterClient {

    private const val TAG = "OpenRouterClient"
    private const val ENDPOINT = "https://openrouter.ai/api/v1/audio/transcriptions"
    private const val SAMPLE_RATE = 16000

    // Distinct failure modes so the service can show tailored toasts.
    sealed class TranscriptionException(message: String, cause: Throwable? = null) : Exception(message, cause) {
        class InvalidKey : TranscriptionException("Invalid API key")
        class InsufficientCredits : TranscriptionException("Out of OpenRouter credits")
        class InvalidModel : TranscriptionException("Model not found")
        class NetworkError(cause: Throwable?) : TranscriptionException("Network error", cause)
        class BadResponse : TranscriptionException("Unexpected API response")
        class ApiError(val code: Int, serverMessage: String) :
            TranscriptionException("OpenRouter error HTTP $code: $serverMessage")
    }

    /**
     * Transcribes 16 kHz mono 16-bit PCM. Returns the transcript (may be blank
     * when no speech was detected) or throws [TranscriptionException].
     */
    fun transcribe(pcm: ByteArray, apiKey: String, model: String): String {
        if (apiKey.isBlank()) throw TranscriptionException.InvalidKey()

        val base64 = Base64.encodeToString(wrapWav(pcm), Base64.NO_WRAP)
        val payload = buildString {
            append("{\"model\":\"").append(jsonEscape(model))
                .append("\",\"input_audio\":{\"data\":\"").append(base64)
                .append("\",\"format\":\"wav\"}}")
        }

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
                // Long clips take a while to transcribe; reads wait generously.
                connectTimeout = 15_000
                readTimeout = 120_000
                doOutput = true
            }
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                val body = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                return parseTranscript(body)
            }

            val errorBody = connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            throw mapHttpError(code, errorBody)
        } catch (e: TranscriptionException) {
            throw e
        } catch (e: IOException) {
            Log.e(TAG, "Request failed", e)
            throw TranscriptionException.NetworkError(e)
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseTranscript(body: String): String = try {
        val text = JSONObject(body).optString("text")
        Log.i(TAG, "Got transcript (${text.length} chars)")
        text
    } catch (e: Exception) {
        Log.e(TAG, "Malformed response: $body", e)
        throw TranscriptionException.BadResponse()
    }

    private fun mapHttpError(code: Int, errorBody: String): TranscriptionException {
        // OpenRouter error envelope: {"error": {"message": "..."}}
        val serverMessage = try {
            JSONObject(errorBody).optJSONObject("error")?.optString("message") ?: errorBody
        } catch (_: Exception) {
            errorBody
        }
        return when (code) {
            401 -> TranscriptionException.InvalidKey()
            402 -> TranscriptionException.InsufficientCredits()
            404 -> TranscriptionException.InvalidModel()
            else -> TranscriptionException.ApiError(code, serverMessage)
        }
    }

    /** Prepends a 44-byte RIFF/WAVE header to raw PCM (16 kHz, mono, 16-bit). */
    internal fun wrapWav(pcm: ByteArray): ByteArray {
        val wav = ByteArrayOutputStream(pcm.size + 44)
        fun writeString(s: String) = wav.write(s.toByteArray(Charsets.US_ASCII))
        fun writeIntLE(v: Int) {
            wav.write(v and 0xFF)
            wav.write((v shr 8) and 0xFF)
            wav.write((v shr 16) and 0xFF)
            wav.write((v shr 24) and 0xFF)
        }
        fun writeShortLE(v: Int) {
            wav.write(v and 0xFF)
            wav.write((v shr 8) and 0xFF)
        }

        writeString("RIFF")
        writeIntLE(36 + pcm.size)
        writeString("WAVE")
        writeString("fmt ")
        writeIntLE(16)            // fmt chunk size
        writeShortLE(1)           // PCM
        writeShortLE(1)           // mono
        writeIntLE(SAMPLE_RATE)
        writeIntLE(SAMPLE_RATE * 2) // byte rate
        writeShortLE(2)           // block align
        writeShortLE(16)          // bits per sample
        writeString("data")
        writeIntLE(pcm.size)
        wav.write(pcm)
        return wav.toByteArray()
    }

    private fun jsonEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
}