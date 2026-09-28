package earth.levi.flowopenrouter.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class FloAccessibilityService : AccessibilityService() {

    companion object {
        var instance: FloAccessibilityService? = null
            private set
        private const val TAG = "FlowAccessibility"
        private const val SAMPLE_RATE = 16000
        private const val BYTES_PER_SECOND = SAMPLE_RATE * 2 // 16-bit mono
        // Hold without moving this long before recording starts, so taps and drags do nothing.
        private const val HOLD_TO_RECORD_MS = 250L
        // 5 min cap ≈ 9.6 MB PCM; keeps in-memory recording bounded.
        private const val MAX_RECORD_SECONDS = 300
        private const val MAX_BUFFER_BYTES = BYTES_PER_SECOND * MAX_RECORD_SECONDS
    }

    private var focusedNode: AccessibilityNodeInfo? = null
    private var isTextFieldFocused = false
    private var bubbleView: BubbleView? = null
    private var windowManager: WindowManager? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    @Volatile
    private var isRecording = false
    private var audioRecord: AudioRecord? = null
    private var pcmBuffer = ByteArrayOutputStream()
    private var recordingJob: Job? = null
    private var holdJob: Job? = null
    private var pointerDown = false
    private var dragging = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @SuppressLint("ClickableViewAccessibility")
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")

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
                            stopRecordingAndBuffer()
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

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        if (isRecording || !micPermissionGranted()) {
            Toast.makeText(this, "Microphone permission missing — enable it in the Flow app settings", Toast.LENGTH_LONG).show()
            return
        }

        isRecording = true
        bubbleView?.setRecording(true)
        pcmBuffer = ByteArrayOutputStream()

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(BYTES_PER_SECOND)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            audioRecord!!.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioRecord", e)
            Toast.makeText(this, "Could not start recording: ${e.message}", Toast.LENGTH_LONG).show()
            isRecording = false
            audioRecord?.release()
            audioRecord = null
            bubbleView?.setRecording(false)
            return
        }

        Log.i(TAG, "Recording started, sample rate: ${audioRecord!!.sampleRate}")
        Toast.makeText(this, "Recording…", Toast.LENGTH_SHORT).show()

        recordingJob = scope.launch {
            val chunkSize = BYTES_PER_SECOND // 0.5 s of 16-bit mono
            val buffer = ByteArray(chunkSize)
            try {
                withContext(Dispatchers.IO) {
                    while (isRecording && isActive) {
                        val read = audioRecord!!.read(buffer, 0, chunkSize)
                        if (read > 0) {
                            pcmBuffer.write(buffer, 0, read)
                            if (pcmBuffer.size() >= MAX_BUFFER_BYTES) {
                                Log.i(TAG, "Recording hit $MAX_RECORD_SECONDS s cap, stopping")
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@FloAccessibilityService, "Max recording time reached", Toast.LENGTH_SHORT).show()
                                }
                                break
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recording error", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FloAccessibilityService, "Recording error: ${e.message}", Toast.LENGTH_LONG).show()
                }
                isRecording = false
                bubbleView?.post { bubbleView?.setRecording(false) }
            }
        }
    }

    private fun stopRecordingAndBuffer() {
        if (!isRecording) return
        isRecording = false

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        val size = pcmBuffer.size()
        Log.i(TAG, "Recording stopped, buffered $size bytes (${size / BYTES_PER_SECOND} s)")

        // Ticket 03 plugs the OpenRouter request in here; for now the buffer goes nowhere.
        bubbleView?.setProcessing(true)
        Toast.makeText(this, "Buffered ${size / 1024} kB of audio", Toast.LENGTH_SHORT).show()
        scope.launch {
            delay(1500)
            bubbleView?.setProcessing(false)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        isRecording = false
        holdJob?.cancel()
        recordingJob?.cancel()
        scope.cancel()
        audioRecord?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        bubbleView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        super.onDestroy()
    }
}