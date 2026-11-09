package com.lytalk.dodo.a11y

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.CopyOnWriteArrayList

fun beginNodePick(context: android.content.Context): String? {
    val service = AutoAccessibilityService.instance
        ?: return "请先在权限页打开无障碍服务，才能点选控件"
    val target = service.lastExternalPackage?.takeIf { service.isPickTarget(it) }
        ?: return "请先打开要选点的应用，再回到这里点开始点选"
    service.setRetrievalActive(true)
    val resumed = service.lastExternalClass?.let { className ->
        runCatching {
            context.startActivity(
                Intent().setComponent(ComponentName(target, className)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
    } == true
    if (!resumed) {
        val launch = context.packageManager.getLaunchIntentForPackage(target)
        if (launch == null) {
            service.setRetrievalActive(false)
            return "无法回到刚才的应用"
        }
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = launch.component
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            service.setRetrievalActive(false)
            return "无法回到刚才的应用"
        }
    }
    service.showPickerDelayed()
    return null
}

object PickEvents {
    private val listeners = CopyOnWriteArrayList<(PickedNode) -> Unit>()
    private val cancelListeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    var pending: PickedNode? = null

    fun listen(listener: (PickedNode) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    fun listenCancel(listener: () -> Unit): () -> Unit {
        cancelListeners += listener
        return { cancelListeners -= listener }
    }

    fun cancel() {
        cancelListeners.forEach { it() }
    }

    fun hasListeners(): Boolean = listeners.isNotEmpty()

    fun deliver(node: PickedNode) {
        if (listeners.isEmpty()) {
            pending = node
        } else {
            listeners.forEach { it(node) }
        }
    }

    fun consumePending(): PickedNode? = pending.also { pending = null }
}

class NodePicker(private val service: AutoAccessibilityService) {
    private val ui = android.view.ContextThemeWrapper(service, android.R.style.Theme_DeviceDefault)
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: View? = null

    fun show() {
        handler.post {
            if (overlay != null) return@post
            service.setRetrievalActive(true)
            overlay = buildScrim()
            windowManager.addView(overlay, overlayLayout())
            Toast.makeText(service, "点一下要操作的控件", Toast.LENGTH_SHORT).show()
        }
    }

    fun hide() {
        handler.post {
            val view = overlay
            overlay = null
            if (view != null) runCatching { windowManager.removeView(view) }
            service.setRetrievalActive(false)
        }
    }

    private fun buildScrim(): View {
        val root = FrameLayout(ui)
        root.setBackgroundColor(Color.TRANSPARENT)
        root.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                showResult(root, event.rawX.toInt(), event.rawY.toInt())
            }
            true
        }
        return root
    }

    private fun showResult(root: FrameLayout, x: Int, y: Int) {
        root.setOnTouchListener(null)
        runCatching { windowManager.removeViewImmediate(root) }
        overlay = null
        val picked = runCatching { NodeActions(service).hitTest(x, y) }.getOrNull()
            ?: blankPick(x, y)
        val result = FrameLayout(ui)
        result.setBackgroundColor(0x66000000)
        result.addView(resultCard(picked), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ).apply {
            val margin = dp(16)
            setMargins(margin, margin, margin, margin)
        })
        overlay = result
        windowManager.addView(result, overlayLayout())
    }

    private fun resultCard(picked: PickedNode): View {
        val card = LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1C2420"))
                cornerRadius = dp(16).toFloat()
            }
        }
        val x = (picked.xFraction * 100).toInt()
        val y = (picked.yFraction * 100).toInt()
        val here = picked.text.ifBlank { picked.description }
        card.addView(text("点到的控件", 18f, Color.parseColor("#E7C27A")))
        card.addView(
            text(
                if (picked.viewId.isBlank()) "控件 id：没有" else "控件 id：${picked.viewId}",
                15f,
                Color.parseColor("#E3E8E4"),
            ),
        )
        if (here.isNotBlank()) card.addView(text("文字：$here", 14f, Color.parseColor("#C5D0CB")))
        card.addView(text("位置 $x%, $y%。到点先点这个控件，找不到再按位置点。", 14f, Color.parseColor("#8FA399")))
        val actions = LinearLayout(ui).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("用这个控件") {
            val listened = PickEvents.hasListeners()
            PickEvents.deliver(picked)
            val message = if (listened) "已记下这个控件" else "已记录，打开任务编辑后会加入步骤"
            Toast.makeText(service, message, Toast.LENGTH_SHORT).show()
            bringAppToFront()
            hide()
        })
        actions.addView(button("重选") { reshow() })
        actions.addView(button("取消") {
            PickEvents.cancel()
            bringAppToFront()
            hide()
        })
        card.addView(actions)
        return card
    }

    private fun reshow() {
        handler.post {
            val view = overlay
            overlay = null
            if (view != null) runCatching { windowManager.removeView(view) }
            overlay = buildScrim()
            windowManager.addView(overlay, overlayLayout())
            Toast.makeText(service, "点一下要操作的控件", Toast.LENGTH_SHORT).show()
        }
    }

    private fun blankPick(x: Int, y: Int): PickedNode {
        val metrics = service.resources.displayMetrics
        return PickedNode(
            viewId = "",
            text = "",
            description = "",
            className = "",
            packageName = "",
            xFraction = x / metrics.widthPixels.toFloat(),
            yFraction = y / metrics.heightPixels.toFloat(),
        )
    }

    private fun bringAppToFront() {
        runCatching {
            service.getSystemService(ActivityManager::class.java).appTasks.firstOrNull()?.moveToFront()
        }
    }

    private fun text(value: String, sizeSp: Float, color: Int): TextView {
        return TextView(ui).apply {
            text = value
            textSize = sizeSp
            setTextColor(color)
            setPadding(0, dp(4), 0, dp(4))
        }
    }

    private fun button(label: String, onClick: () -> Unit): Button {
        return Button(ui).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }
    }

    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).toInt()

    private fun overlayLayout(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
    }
}
