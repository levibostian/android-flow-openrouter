package earth.levi.flowopenrouter.transcribe

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.speech.SpeechRecognizer

/** Which transcription backend the user picked. */
enum class TranscriptionRoute {
    ON_DEVICE,
    OPENROUTER;

    companion object {
        private const val PREFS_NAME = "flow_prefs"
        private const val PREF_ROUTE = "flow_route"

        fun isOnDeviceAvailable(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

        /** Routes this device can run, on-device first when supported. */
        fun available(context: Context): List<TranscriptionRoute> = buildList {
            if (isOnDeviceAvailable(context)) add(ON_DEVICE)
            add(OPENROUTER)
        }

        /** Saved choice, or the first available route when unset/stale. */
        fun selected(context: Context): TranscriptionRoute {
            val saved = prefs(context).getString(PREF_ROUTE, null)
            val available = available(context)
            return available.firstOrNull { it.name == saved } ?: available.first()
        }

        fun save(context: Context, route: TranscriptionRoute) {
            prefs(context).edit().putString(PREF_ROUTE, route.name).apply()
        }

        private fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
