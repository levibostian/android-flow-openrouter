package earth.levi.flowopenrouter.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import earth.levi.flowopenrouter.overlay.BubbleView
import earth.levi.flowopenrouter.transcribe.OnDeviceTranscriber
import earth.levi.flowopenrouter.transcribe.OpenRouterTranscriber
import earth.levi.flowopenrouter.transcribe.Transcriber
import earth.levi.flowopenrouter.transcribe.TranscriptionRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Floating mic bubble over any app: detects a focused text field, shows the
 * bubble, drives a [Transcriber] on hold/release, and pastes the result.
 *
 * Transcription itself lives in `transcribe/` — this class knows nothing about
 * mics, recognizers, or OpenRouter.
 */
class FloAccessibilityService : AccessibilityService() {

    companion object {
        var instance: FloAccessibilityService? = null
            private set
        private const val TAG = "FlowAccessibility"
        // Hold without moving this long before recording starts, so taps and drags do nothing.
        private const val HOLD_TO_RECORD_MS = 250L
    }

    private var focusedNode: AccessibilityNodeInfo? = null
    private var isTextFieldFocused = false
    private var bubbleView: BubbleView? = null
    private var windowManager: WindowManager? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var transcriber: Transcriber? = null
    private var currentRoute: TranscriptionRoute? = null

    @Volatile
    private var isRecording = false
    private var holdJob: Job? = null
    private var pointerDown = false
    private var dragging = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val transcriptionCallback = object : Transcriber.Callback {
        override fun onTranscript(text: String) {
            endSession()
            if (text.isBlank()) {
                Log.w(TAG, "Empty transcript")
                showToast("No speech detected")
            } else {
                Log.i(TAG, "Transcript: '$text'")
                pasteText(text)
                showToast("Pasted transcript")
            }
        }

        override fun onError(message: String) {
            endSession()
            Log.e(TAG, "Transcription failed: $message")
            showToast(message)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")

        transcriberForCurrentRoute()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        bubbleView = BubbleView(this)

        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = 16
        }

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        bubbleView!!.setOnTouchListener(object : View.OnTouchListener {
            private var initialY = 0
            private var initialTouchY = 0f
            private var downX = 0f
            private var downY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        pointerDown = true
                        dragging = false
                        initialY = bubbleParams!!.y
                        initialTouchY = event.rawY
                        downX = event.rawX
                        downY = event.rawY
                        holdJob = scope.launch {
                            delay(HOLD_TO_RECORD_MS)
                            if (pointerDown && !dragging && !isRecording) {
                                startRecording()
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dist = kotlin.math.hypot(
                            event.rawX - downX,
                            event.rawY - downY
                        )
                        if (dist > touchSlop) dragging = true
                        bubbleParams!!.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager?.updateViewLayout(bubbleView, bubbleParams)
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        pointerDown = false
                        holdJob?.cancel()
                        if (isRecording) {
                            stopRecording()
                        }
                        dragging = false
                        return true
                    }
                }
                return false
            }
        })

        bubbleView!!.visibility = View.GONE
        try {
            windowManager?.addView(bubbleView, bubbleParams)
            Log.i(TAG, "Bubble view added")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add bubble view", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                checkForTextField(event)
            }
        }
    }

    private fun checkForTextField(event: AccessibilityEvent) {
        val source = event.source ?: return

        val isEditable = source.isEditable
        val wasFocused = isTextFieldFocused
        isTextFieldFocused = isEditable && source.isFocused

        if (isTextFieldFocused) {
            focusedNode = source
        } else if (!isEditable) {
            val root = rootInActiveWindow
            if (root != null) {
                val editableNode = findFocusedEditable(root)
                if (editableNode != null) {
                    isTextFieldFocused = true
                    focusedNode = editableNode
                }
            }
        }

        if (wasFocused != isTextFieldFocused) {
            bubbleView?.post {
                bubbleView?.visibility = if (isTextFieldFocused) View.VISIBLE else View.GONE
            }
        }
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isFocused) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFocusedEditable(child)
            if (result != null) return result
        }
        return null
    }

    fun pasteText(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("flow", text))

        val node = findFocusedEditableInActiveWindow() ?: focusedNode
        if (node != null) {
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            Log.i(TAG, "Paste action performed")
        } else {
            Log.e(TAG, "No focused editable node found for paste")
        }
    }

    private fun findFocusedEditableInActiveWindow(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findFocusedEditable(root)
    }

    private fun micPermissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun startRecording() {
        if (isRecording || !micPermissionGranted()) {
            showToast("Microphone permission missing — enable it in the Flow app settings")
            return
        }
        isRecording = true
        bubbleView?.setRecording(true)
        transcriberForCurrentRoute().start()
    }

    /** Rebuilds the backend only when the saved route changed, so the UI toggle applies on next press. */
    private fun transcriberForCurrentRoute(): Transcriber {
        val route = TranscriptionRoute.selected(this)
        val existing = transcriber
        if (existing != null && route == currentRoute) return existing

        existing?.release()
        val next = when (route) {
            TranscriptionRoute.ON_DEVICE ->
                OnDeviceTranscriber.create(this, transcriptionCallback)
                    ?: OpenRouterTranscriber(this, transcriptionCallback)
            TranscriptionRoute.OPENROUTER -> OpenRouterTranscriber(this, transcriptionCallback)
        }
        transcriber = next
        currentRoute = route
        Log.i(TAG, "Transcription route: $route")
        return next
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        // Transcript arrives asynchronously via transcriptionCallback.
        bubbleView?.setProcessing(true)
        transcriber?.stop()
    }

    private fun endSession() {
        isRecording = false
        bubbleView?.setProcessing(false)
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        isRecording = false
        holdJob?.cancel()
        scope.cancel()
        transcriber?.release()
        bubbleView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        super.onDestroy()
    }
}
