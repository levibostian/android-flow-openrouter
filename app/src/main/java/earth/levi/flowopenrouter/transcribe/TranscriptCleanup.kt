package earth.levi.flowopenrouter.transcribe

import android.content.Context
import android.content.SharedPreferences

/**
 * User preference for post-processing a transcript through an OpenRouter chat
 * model to fix spelling and grammar before it is pasted.
 *
 * Off by default: enabling it sends transcript text (never audio) to OpenRouter,
 * even when transcription itself runs on-device.
 */
object TranscriptCleanup {

    const val DEFAULT_MODEL = "openai/gpt-4o-mini"

    private const val PREFS_NAME = "flow_prefs"
    private const val PREF_ENABLED = "flow_cleanup_enabled"
    private const val PREF_MODEL = "flow_cleanup_model"
    // Shared with OpenRouterTranscriber/MainActivity; cleanup needs the same key.
    private const val PREF_API_KEY = "flow_api_key"

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(PREF_ENABLED, false)

    fun model(context: Context): String =
        prefs(context).getString(PREF_MODEL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL

    fun apiKey(context: Context): String =
        prefs(context).getString(PREF_API_KEY, "").orEmpty()

    fun save(context: Context, enabled: Boolean, model: String) {
        prefs(context).edit()
            .putBoolean(PREF_ENABLED, enabled)
            .putString(PREF_MODEL, model.ifBlank { DEFAULT_MODEL })
            .apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
