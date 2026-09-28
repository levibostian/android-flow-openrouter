package earth.levi.flowopenrouter.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import earth.levi.flowopenrouter.overlay.BubbleView

class FloAccessibilityService : AccessibilityService() {

    companion object {
        var instance: FloAccessibilityService? = null
            private set
        private const val TAG = "FlowAccessibility"
    }

    private var focusedNode: AccessibilityNodeInfo? = null
    private var isTextFieldFocused = false
    private var bubbleView: BubbleView? = null
    private var windowManager: WindowManager? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

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

        bubbleView!!.setOnTouchListener(object : View.OnTouchListener {
            private var initialY = 0
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialY = bubbleParams!!.y
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        bubbleParams!!.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager?.updateViewLayout(bubbleView, bubbleParams)
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
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

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        bubbleView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        super.onDestroy()
    }
}