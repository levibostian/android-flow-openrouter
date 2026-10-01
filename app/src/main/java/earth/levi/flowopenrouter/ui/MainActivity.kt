package earth.levi.flowopenrouter.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch
import earth.levi.flowopenrouter.R
import earth.levi.flowopenrouter.service.FloAccessibilityService
import earth.levi.flowopenrouter.transcribe.TranscriptCleanup
import earth.levi.flowopenrouter.transcribe.TranscriptionRoute

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val PREFS_NAME = "flow_prefs"
        private const val DEFAULT_MODEL = "openai/whisper-1"
    }

    private lateinit var routeGroup: RadioGroup
    private lateinit var routeOnDevice: RadioButton
    private lateinit var openRouterSection: View
    private lateinit var apiKeyInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var browseModelsButton: Button
    private lateinit var cleanupSwitch: MaterialSwitch
    private lateinit var cleanupModelInput: EditText
    private lateinit var browseCleanupModelsButton: Button
    private lateinit var statusText: TextView
    private lateinit var saveButton: Button
    private lateinit var enableAccessibilityButton: Button
    private lateinit var enableOverlayButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        apiKeyInput = findViewById(R.id.api_key_input)
        modelInput = findViewById(R.id.model_input)
        browseModelsButton = findViewById(R.id.browse_models_button)
        cleanupSwitch = findViewById(R.id.cleanup_switch)
        cleanupModelInput = findViewById(R.id.cleanup_model_input)
        browseCleanupModelsButton = findViewById(R.id.browse_cleanup_models_button)
        statusText = findViewById(R.id.status_text)
        saveButton = findViewById(R.id.save_button)
        enableAccessibilityButton = findViewById(R.id.enable_accessibility_button)
        enableOverlayButton = findViewById(R.id.enable_overlay_button)
        routeGroup = findViewById(R.id.route_group)
        routeOnDevice = findViewById(R.id.route_on_device)
        openRouterSection = findViewById(R.id.openrouter_section)

        // loadPrefs before setupRouteUi: applyRouteUi reads the cleanup switch.
        loadPrefs()
        setupRouteUi()

        cleanupSwitch.setOnCheckedChangeListener { _, checked ->
            TranscriptCleanup.save(this, checked, cleanupModelInput.text.toString().trim())
            applyRouteUi(TranscriptionRoute.selected(this))
        }

        browseCleanupModelsButton.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://openrouter.ai/models")))
        }

        saveButton.setOnClickListener { savePrefs() }

        enableAccessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        enableOverlayButton.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        browseModelsButton.setOnClickListener {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://openrouter.ai/collections/speech-to-text-models")
                )
            )
        }

        requestPermissions()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun setupRouteUi() {
        if (!TranscriptionRoute.isOnDeviceAvailable(this)) {
            routeOnDevice.isEnabled = false
            routeOnDevice.text = "On-device (not available on this device)"
        }

        val selected = TranscriptionRoute.selected(this)
        routeGroup.check(
            if (selected == TranscriptionRoute.ON_DEVICE) R.id.route_on_device else R.id.route_openrouter
        )
        applyRouteUi(selected)

        routeGroup.setOnCheckedChangeListener { _, checkedId ->
            val route = if (checkedId == R.id.route_on_device) {
                TranscriptionRoute.ON_DEVICE
            } else {
                TranscriptionRoute.OPENROUTER
            }
            // Route changes apply immediately; the OpenRouter credentials still need Save Settings.
            TranscriptionRoute.save(this, route)
            applyRouteUi(route)
        }
    }

    private fun applyRouteUi(route: TranscriptionRoute) {
        val onDevice = route == TranscriptionRoute.ON_DEVICE
        val cleanup = cleanupSwitch.isChecked
        // Cleanup needs the API key even when transcription itself is on-device.
        val keyNeeded = !onDevice || cleanup
        openRouterSection.alpha = if (keyNeeded) 1f else 0.4f
        apiKeyInput.isEnabled = keyNeeded
        modelInput.isEnabled = !onDevice
        browseModelsButton.isEnabled = !onDevice

        cleanupModelInput.isEnabled = cleanup
        browseCleanupModelsButton.isEnabled = cleanup
    }

    private fun loadPrefs() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        apiKeyInput.setText(prefs.getString(getString(R.string.pref_api_key), ""))
        modelInput.setText(prefs.getString(getString(R.string.pref_model), DEFAULT_MODEL))
        cleanupSwitch.isChecked = TranscriptCleanup.isEnabled(this)
        cleanupModelInput.setText(TranscriptCleanup.model(this))
    }

    private fun savePrefs() {
        val apiKey = apiKeyInput.text.toString().trim()
        val model = modelInput.text.toString().trim().ifEmpty { DEFAULT_MODEL }

        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putString(getString(R.string.pref_api_key), apiKey)
            putString(getString(R.string.pref_model), model)
            apply()
        }
        TranscriptCleanup.save(this, cleanupSwitch.isChecked, cleanupModelInput.text.toString().trim())
        applyRouteUi(TranscriptionRoute.selected(this))
        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
    }

    private fun updateStatus() {
        val checks = mutableListOf<String>()

        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        checks.add(if (micOk) "✓ Microphone permission" else "✗ Microphone permission needed")

        val overlayOk = Settings.canDrawOverlays(this)
        checks.add(if (overlayOk) "✓ Overlay permission" else "✗ Overlay permission needed")

        val accessibilityOk = isAccessibilityServiceEnabled()
        checks.add(if (accessibilityOk) "✓ Accessibility service enabled" else "✗ Accessibility service not enabled")

        val allOk = micOk && overlayOk && accessibilityOk
        checks.add("")
        checks.add(if (allOk) "Ready! Open any app and tap a text field." else "Please enable all permissions above.")

        statusText.text = checks.joinToString("\n")

        enableAccessibilityButton.isEnabled = !accessibilityOk
        enableOverlayButton.isEnabled = !overlayOk
    }

    private fun requestPermissions() {
        val perms = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.RECORD_AUDIO)
        }
        if (perms.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updateStatus()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabledServices.any {
            it.resolveInfo.serviceInfo.packageName == packageName &&
            it.resolveInfo.serviceInfo.name == FloAccessibilityService::class.java.name
        }
    }
}