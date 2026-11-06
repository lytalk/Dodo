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
    val target = service.lastExternalPackage
        ?: return "请先打开要选点的应用，再回到这里点开始点选"
    val resumed = service.lastExternalClass?.let { className ->
        runCatching {
            context.startActivity(
                Intent().setComponent(ComponentName(target, className)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
    } == true
    if (!resumed) {
        val launch = context.packageManager.getLaunchIntentForPackage(target)
            ?: return "无法回到刚才的应用"
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = launch.component
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            return "无法回到刚才的应用"
        }
    }
    service.showPickerDelayed()
    return null
}

object PickEvents {
    private val listeners = CopyOnWriteArrayList<(PickedNode) -> Unit>()

    @Volatile
    var pending: PickedNode? = null

    fun listen(listener: (PickedNode) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
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
            overlay = buildScrim()
            windowManager.addView(overlay, overlayLayout())
        }
    }

    fun hide() {
        handler.post {
            val view = overlay ?: return@post
            overlay = null
            runCatching { windowManager.removeView(view) }
        }
    }

    private fun buildScrim(): View {
        val root = FrameLayout(ui)
        root.setBackgroundColor(0x33000000)
        root.addView(instructionBar(root), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP,
        ))
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
        root.removeAllViews()
        root.setBackgroundColor(0x66000000)
        val picked = runCatching { NodeActions(service).hitTest(x, y) }.getOrNull()
            ?: blankPick(x, y)
        root.addView(resultCard(picked), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ).apply {
            val margin = dp(16)
            setMargins(margin, margin, margin, margin)
        })
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
        card.addView(text("点到的控件", 18f, Color.parseColor("#E7C27A")))
        val idLine = if (picked.viewId.isBlank()) "控件 id：没有。没有文字的图标经常这样，仍可按这个位置点击。" else "控件 id：${picked.viewId}"
        card.addView(text(idLine, 15f, Color.parseColor("#E3E8E4")))
        if (picked.text.isNotBlank()) card.addView(text("文字：${picked.text}", 14f, Color.parseColor("#C5D0CB")))
        if (picked.description.isNotBlank() && picked.description != picked.text) {
            card.addView(text("描述：${picked.description}", 14f, Color.parseColor("#C5D0CB")))
        }
        if (picked.packageName.isNotBlank()) {
            card.addView(text(picked.packageName, 12f, Color.parseColor("#8FA399")))
        }
        val actions = LinearLayout(ui).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("添加步骤") {
            val listened = PickEvents.hasListeners()
            PickEvents.deliver(picked)
            val message = if (listened) "已添加点击步骤" else "已记录，打开任务编辑后会加入步骤"
            Toast.makeText(service, message, Toast.LENGTH_SHORT).show()
            bringAppToFront()
            hide()
        })
        actions.addView(button("重选") { reshow() })
        actions.addView(button("取消") {
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
        }
    }

    private fun instructionBar(root: FrameLayout): View {
        return text("点一下要操作的图标、按钮或文字。点此取消", 16f, Color.parseColor("#003824")).apply {
            setBackgroundColor(Color.parseColor("#8FDBB2"))
            setPadding(dp(16), dp(28), dp(16), dp(16))
            setOnClickListener {
                root.setOnTouchListener(null)
                bringAppToFront()
                hide()
            }
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
