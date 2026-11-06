package com.lytalk.dodo.a11y

import android.app.ActivityManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.lytalk.dodo.data.StepPayloads
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.hypot

object UnlockEvents {
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    @Volatile
    var pending: String? = null

    fun listen(listener: (String) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    fun deliver(payload: String) {
        if (listeners.isEmpty()) pending = payload else listeners.forEach { it(payload) }
    }

    fun consumePending(): String? = pending.also { pending = null }
}

class UnlockRecorder(private val service: AutoAccessibilityService) {
    private val ui = android.view.ContextThemeWrapper(service, android.R.style.Theme_DeviceDefault)
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private val strokes = mutableListOf<JSONObject>()
    private val current = mutableListOf<JSONObject>()
    private var strokeStart = 0L
    private var lastStrokeEnd = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var banner: TextView? = null

    fun show() {
        handler.post {
            if (overlay != null) return@post
            strokes.clear()
            current.clear()
            lastStrokeEnd = 0L
            overlay = buildOverlay()
            windowManager.addView(overlay, overlayLayout())
        }
    }

    fun hide() {
        handler.post {
            val view = overlay ?: return@post
            overlay = null
            banner = null
            runCatching { windowManager.removeView(view) }
        }
    }

    private fun buildOverlay(): View {
        val root = FrameLayout(ui)
        root.setBackgroundColor(0x22000000)
        val bar = TextView(ui).apply {
            text = "正在录制解锁动作。滑屏或点击，做完点这里保存"
            textSize = 16f
            setTextColor(Color.parseColor("#003824"))
            setBackgroundColor(Color.parseColor("#8FDBB2"))
            setPadding(dp(16), dp(28), dp(16), dp(16))
            setOnClickListener { save() }
        }
        banner = bar
        root.addView(bar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP,
        ))
        root.setOnTouchListener { _, event ->
            record(event)
            true
        }
        return root
    }

    private fun record(event: MotionEvent) {
        if (event.actionIndex != 0 && event.actionMasked != MotionEvent.ACTION_DOWN) return
        val metrics = service.resources.displayMetrics
        val x = event.rawX / metrics.widthPixels
        val y = event.rawY / metrics.heightPixels
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                current.clear()
                strokeStart = event.eventTime
                lastX = event.rawX
                lastY = event.rawY
                current += point(x, y, 0)
            }
            MotionEvent.ACTION_MOVE -> {
                if (current.isEmpty()) return
                if (hypot(event.rawX - lastX, event.rawY - lastY) < 12f) return
                lastX = event.rawX
                lastY = event.rawY
                current += point(x, y, event.eventTime - strokeStart)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (current.isEmpty()) return
                current += point(x, y, event.eventTime - strokeStart)
                val delayBefore = if (lastStrokeEnd == 0L) 0L else (strokeStart - lastStrokeEnd).coerceIn(0L, 5_000L)
                val stroke = JSONObject()
                    .put("delayBefore", delayBefore)
                    .put("points", JSONArray(current.toList()))
                strokes += stroke
                lastStrokeEnd = event.eventTime
                current.clear()
                banner?.text = "已录下 ${strokes.size} 段。还可以继续滑或点，做完点这里保存"
            }
        }
    }

    private fun point(x: Float, y: Float, time: Long): JSONObject =
        JSONObject()
            .put("x", x.toDouble().coerceIn(0.0, 1.0))
            .put("y", y.toDouble().coerceIn(0.0, 1.0))
            .put("t", time.coerceAtLeast(0))

    private fun save() {
        if (strokes.isEmpty()) {
            Toast.makeText(service, "还没有录到动作", Toast.LENGTH_SHORT).show()
            return
        }
        UnlockEvents.deliver(StepPayloads.unlock(JSONArray(strokes.toList())))
        Toast.makeText(service, "已保存解锁动作", Toast.LENGTH_SHORT).show()
        bringAppToFront()
        hide()
    }

    private fun bringAppToFront() {
        runCatching {
            service.getSystemService(ActivityManager::class.java).appTasks.firstOrNull()?.moveToFront()
        }
    }

    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    private fun overlayLayout(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT,
        )
    }
}
