package earth.levi.flowopenrouter.client

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal OpenRouter client.
 *
 * - POST /api/v1/audio/transcriptions — dedicated STT endpoint. Takes STT-class
 *   models (openai/whisper-1, openai/gpt-4o-transcribe, microsoft/mai-transcribe-2,
 *   qwen/qwen3-asr-*, ...); chat-audio models like openai/gpt-audio-mini are
 *   rejected with "does not exist". Verified against the official docs
 *   (guides/overview/multimodal/audio + .../stt).
 * - POST /api/v1/chat/completions — cleans up a transcript with a chat model
 *   before it is pasted.
 */
object OpenRouterClient {

    private const val TAG = "OpenRouterClient"
    private const val TRANSCRIPTION_ENDPOINT = "https://openrouter.ai/api/v1/audio/transcriptions"
    private const val CHAT_ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
    private const val SAMPLE_RATE = 16000
    // Long clips take a while to transcribe; chat cleanup is fast.
    private const val TRANSCRIBE_READ_TIMEOUT_MS = 120_000
    private const val CLEANUP_READ_TIMEOUT_MS = 60_000

    private const val CLEANUP_SYSTEM_PROMPT =
        "Fix spelling, grammar, and punctuation in the user's text. " +
            "Preserve the original meaning, wording, tone, and language. " +
            "Do not add, remove, answer, or explain anything. Return only the corrected text."

    // Distinct failure modes so the service can show tailored toasts.
    sealed class OpenRouterException(message: String, cause: Throwable? = null) : Exception(message, cause) {
        class InvalidKey : OpenRouterException("Invalid API key")
        class InsufficientCredits : OpenRouterException("Out of OpenRouter credits")
        class InvalidModel(val serverMessage: String) :
            OpenRouterException("Model not found: $serverMessage")
        class NetworkError(cause: Throwable?) : OpenRouterException("Network error", cause)
        class BadResponse : OpenRouterException("Unexpected API response")
        class ApiError(val code: Int, serverMessage: String) :
            OpenRouterException("OpenRouter error HTTP $code: $serverMessage")
    }

    /**
     * Transcribes 16 kHz mono 16-bit PCM. Returns the transcript (may be blank
     * when no speech was detected) or throws [OpenRouterException].
     */
    fun transcribe(pcm: ByteArray, apiKey: String, model: String): String {
        if (apiKey.isBlank()) throw OpenRouterException.InvalidKey()
        Log.i(TAG, "Transcribing with model='$model', ${pcm.size} PCM bytes")

        val base64 = Base64.encodeToString(wrapWav(pcm), Base64.NO_WRAP)
        // input_audio.data must be raw base64, not a data URI (per docs).
        val payload = buildString {
            append("{\"model\":\"").append(jsonEscape(model))
                .append("\",\"input_audio\":{\"data\":\"").append(base64)
                .append("\",\"format\":\"wav\"}}")
        }

        val body = postJson(TRANSCRIPTION_ENDPOINT, payload, apiKey, TRANSCRIBE_READ_TIMEOUT_MS)
        return parseTranscript(body)
    }

    /**
     * Runs [text] through a chat model to fix spelling and grammar. Returns the
     * cleaned text or throws [OpenRouterException].
     */
    fun cleanup(text: String, apiKey: String, model: String): String {
        if (apiKey.isBlank()) throw OpenRouterException.InvalidKey()
        Log.i(TAG, "Cleaning up ${text.length} chars with model='$model'")

        // JSONObject (not hand-built JSON) so transcript quotes/newlines stay valid.
        val payload = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", CLEANUP_SYSTEM_PROMPT))
                put(JSONObject().put("role", "user").put("content", text))
            })
        }.toString()

        val body = postJson(CHAT_ENDPOINT, payload, apiKey, CLEANUP_READ_TIMEOUT_MS)
        return parseCleanup(body)
    }

    /** Single POST helper shared by [transcribe] and [cleanup]; throws [OpenRouterException]. */
    private fun postJson(endpoint: String, payload: String, apiKey: String, readTimeoutMs: Int): String {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 15_000
                readTimeout = readTimeoutMs
                doOutput = true
            }
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            }

            val errorBody = connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            throw mapHttpError(code, errorBody)
        } catch (e: OpenRouterException) {
            throw e
        } catch (e: IOException) {
            Log.e(TAG, "Request failed", e)
            throw OpenRouterException.NetworkError(e)
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseTranscript(body: String): String = try {
        val text = JSONObject(body).optString("text")
        Log.i(TAG, "Got transcript (${text.length} chars)")
        text
    } catch (e: Exception) {
        Log.e(TAG, "Malformed response: ${body.take(500)}", e)
        throw OpenRouterException.BadResponse()
    }

    private fun parseCleanup(body: String): String = try {
        val content = JSONObject(body).getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .optString("content")
        Log.i(TAG, "Got cleaned text (${content.length} chars)")
        content.trim()
    } catch (e: Exception) {
        Log.e(TAG, "Malformed chat response: ${body.take(500)}", e)
        throw OpenRouterException.BadResponse()
    }

    private fun mapHttpError(code: Int, errorBody: String): OpenRouterException {
        // OpenRouter error envelope: {"error": {"message": "..."}}
        val serverMessage = try {
            JSONObject(errorBody).optJSONObject("error")?.optString("message") ?: errorBody
        } catch (_: Exception) {
            errorBody
        }
        Log.e(TAG, "OpenRouter HTTP $code, body: ${errorBody.take(500)}")
        // "Model x does not exist" comes back as HTTP 400 on OpenRouter.
        if (code == 400 && serverMessage.contains("does not exist")) {
            return OpenRouterException.InvalidModel(serverMessage)
        }
        return when (code) {
            401 -> OpenRouterException.InvalidKey()
            402 -> OpenRouterException.InsufficientCredits()
            404 -> OpenRouterException.InvalidModel(serverMessage)
            else -> OpenRouterException.ApiError(code, serverMessage)
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
