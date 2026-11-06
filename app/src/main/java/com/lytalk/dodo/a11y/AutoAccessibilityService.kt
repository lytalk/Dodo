package com.lytalk.dodo.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import java.util.IdentityHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

class AutoAccessibilityService : AccessibilityService() {
    private var picker: NodePicker? = null
    private var unlockRecorder: UnlockRecorder? = null
    private val handler = Handler(Looper.getMainLooper())
    private var ignoredPackages: Set<String>? = null

    @Volatile
    var lastExternalPackage: String? = null
        private set

    @Volatile
    var lastExternalClass: String? = null
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo.apply {
            flags = flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
    }

    fun showPicker() {
        val current = picker ?: NodePicker(this).also { picker = it }
        current.show()
    }

    fun showPickerDelayed() {
        handler.postDelayed({ showPicker() }, 500)
    }

    fun showUnlockRecorder() {
        val current = unlockRecorder ?: UnlockRecorder(this).also { unlockRecorder = it }
        current.show()
    }

    suspend fun playStroke(points: List<Pair<Float, Float>>, durationMs: Long): Boolean {
        if (points.isEmpty()) return false
        val path = Path()
        val first = points.first()
        path.moveTo(first.first, first.second)
        if (points.size == 1) {
            path.lineTo(first.first + 1f, first.second)
        } else {
            points.drop(1).forEach { path.lineTo(it.first, it.second) }
        }
        val duration = durationMs.coerceIn(40, 10_000)
        val result = withTimeoutOrNull(duration + 2_000) {
            suspendCancellableCoroutine { continuation ->
                val stroke = GestureDescription.StrokeDescription(path, 0, duration)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                val sent = dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) continuation.resume(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) continuation.resume(false)
                        }
                    },
                    null,
                )
                if (!sent && continuation.isActive) continuation.resume(false)
            }
        }
        return result == true
    }

    suspend fun tap(x: Float, y: Float): Boolean {
        val result = withTimeoutOrNull(2_000) {
            suspendCancellableCoroutine { continuation ->
                val path = Path().apply { moveTo(x, y) }
                val stroke = GestureDescription.StrokeDescription(path, 0, 80)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                val sent = dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) continuation.resume(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) continuation.resume(false)
                        }
                    },
                    null,
                )
                if (!sent && continuation.isActive) continuation.resume(false)
            }
        }
        return result == true
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        picker?.hide()
        picker = null
        unlockRecorder?.hide()
        unlockRecorder = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return
        if (pkg in ignoredPackages()) return
        val className = event.className?.toString()
        if (!className.isNullOrBlank() && className.startsWith(pkg)) {
            lastExternalClass = className
        }
        lastExternalPackage = pkg
    }

    override fun onInterrupt() = Unit

    fun inspect(): List<InspectRow> = NodeActions(this).inspect()

    fun foregroundPackage(): String? {
        val root = rootInActiveWindow ?: return null
        val name = root.packageName?.toString()
        runCatching { root.recycle() }
        return name
    }

    private fun ignoredPackages(): Set<String> {
        ignoredPackages?.let { return it }
        val homes = runCatching {
            packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY,
            ).map { it.activityInfo.packageName }
        }.getOrDefault(emptyList())
        val inputMethods = runCatching {
            getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }
        }.getOrDefault(emptyList())
        return (homes + inputMethods + packageName + "com.android.systemui" + "android")
            .toSet()
            .also { ignoredPackages = it }
    }

    companion object {
        @Volatile
        var instance: AutoAccessibilityService? = null
    }
}

data class InspectRow(
    val text: String,
    val description: String,
    val viewId: String?,
    val packageName: String,
)

data class PickedNode(
    val viewId: String,
    val text: String,
    val description: String,
    val className: String,
    val packageName: String,
    val xFraction: Float,
    val yFraction: Float,
)

private const val OWN_PACKAGE = "com.lytalk.dodo"

class NodeActions(private val service: AccessibilityService) {
    fun clickText(query: String): Boolean = withSession { session ->
        val matches = session.matching { node ->
            node.packageName?.toString() != OWN_PACKAGE && node.containsQuery(query)
        }
        matches.sortedWith(
            compareBy<AccessibilityNodeInfo> { it.textSpecificity(query) }
                .thenByDescending { it.isClickable },
        ).any { showAndClick(it) }
    }

    fun clickViewId(viewId: String): Boolean = withSession { session ->
        val matches = mutableListOf<AccessibilityNodeInfo>()
        session.roots.forEach { root ->
            if (root.packageName?.toString() == OWN_PACKAGE) return@forEach
            root.findAccessibilityNodeInfosByViewId(viewId).forEach { node ->
                session.track(node)
                matches += node
            }
            session.matchingIn(root, { it.viewIdResourceName == viewId }, matches)
        }
        matches.sortedByDescending { it.isClickable }.any { showAndClick(it) }
    }

    fun scrollLargest(forward: Boolean): Boolean = withSession { session ->
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        fun walk(node: AccessibilityNodeInfo) {
            if (node.packageName?.toString() == OWN_PACKAGE) return
            if (node.isScrollable) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val area = rect.width() * rect.height()
                if (area > bestArea) {
                    best = node
                    bestArea = area
                }
            }
            session.children(node).forEach { walk(it) }
        }
        session.roots.forEach { walk(it) }
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        best?.performAction(action) == true
    }

    fun hitTest(x: Int, y: Int): PickedNode? = withSession { session ->
        var best: AccessibilityNodeInfo? = null
        var bestArea = Int.MAX_VALUE
        fun walk(node: AccessibilityNodeInfo) {
            if (node.packageName?.toString() == OWN_PACKAGE) return
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.contains(x, y) && rect.width() > 0 && rect.height() > 0) {
                val area = rect.width() * rect.height()
                if (area < bestArea) {
                    best = node
                    bestArea = area
                }
            }
            session.children(node).forEach { walk(it) }
        }
        session.roots.forEach { walk(it) }
        val node = best
        if (node == null) return@withSession null
        val metrics = service.resources.displayMetrics
        PickedNode(
            viewId = node.viewIdResourceName.orEmpty(),
            text = node.text?.toString()?.trim().orEmpty(),
            description = node.contentDescription?.toString()?.trim().orEmpty(),
            className = node.className?.toString().orEmpty(),
            packageName = node.packageName?.toString().orEmpty(),
            xFraction = x / metrics.widthPixels.toFloat(),
            yFraction = y / metrics.heightPixels.toFloat(),
        )
    }

    fun hasText(query: String): Boolean = withSession { session ->
        session.roots.any { root ->
            val found = root.findAccessibilityNodeInfosByText(query)
            found.forEach { session.track(it) }
            found.isNotEmpty()
        }
    }

    fun setText(text: String, targetText: String, targetViewId: String): Boolean = withSession { session ->
        val matches = mutableListOf<AccessibilityNodeInfo>()
        when {
            targetViewId.isNotBlank() -> session.roots.forEach { root ->
                root.findAccessibilityNodeInfosByViewId(targetViewId).forEach { node ->
                    session.track(node)
                    matches += node
                }
            }
            targetText.isNotBlank() -> session.roots.forEach { root ->
                root.findAccessibilityNodeInfosByText(targetText).forEach { node ->
                    session.track(node)
                    matches += node
                }
            }
            else -> session.roots.forEach { root ->
                val focused = session.find(root) { it.isEditable && it.isFocused }
                val editable = focused ?: session.find(root) { it.isEditable }
                if (editable != null) matches += editable
            }
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        matches.any { node ->
            val target = if (node.isEditable) node else session.find(node) { it.isEditable } ?: node
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
    }

    fun inspect(): List<InspectRow> = withSession { session ->
        val rows = mutableListOf<InspectRow>()
        val seen = HashSet<String>()
        fun walk(node: AccessibilityNodeInfo) {
            if (rows.size >= 200) return
            val text = node.text?.toString()?.trim().orEmpty()
            val description = node.contentDescription?.toString()?.trim().orEmpty()
            val viewId = node.viewIdResourceName
            if (text.isNotEmpty() || description.isNotEmpty() || !viewId.isNullOrBlank()) {
                val key = "$text|$description|$viewId"
                if (seen.add(key)) {
                    rows += InspectRow(text, description, viewId, node.packageName?.toString().orEmpty())
                }
            }
            session.children(node).forEach { walk(it) }
        }
        session.roots.forEach { walk(it) }
        rows
    }

    private fun showAndClick(node: AccessibilityNodeInfo): Boolean {
        node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        return clickNode(node)
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < 8) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            current = current.parent
            hops++
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private inline fun <T> withSession(block: (Session) -> T): T {
        val session = Session(service)
        return try {
            block(session)
        } finally {
            session.close()
        }
    }
}

private class Session(service: AccessibilityService) {
    val roots = mutableListOf<AccessibilityNodeInfo>()
    private val obtained = ArrayDeque<AccessibilityNodeInfo>()

    init {
        service.rootInActiveWindow?.let { roots += it }
        service.windows?.forEach { window -> window.root?.let { roots += it } }
    }

    fun track(node: AccessibilityNodeInfo) {
        obtained += node
    }

    fun children(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val list = ArrayList<AccessibilityNodeInfo>(node.childCount)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            obtained += child
            list += child
        }
        return list
    }

    fun matching(predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val matches = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo) {
            if (matches.size >= 250) return
            if (predicate(node)) matches += node
            children(node).forEach { walk(it) }
        }
        roots.forEach { walk(it) }
        return matches
    }

    fun matchingIn(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
        out: MutableList<AccessibilityNodeInfo>,
    ) {
        if (out.size >= 250) return
        if (predicate(node)) out += node
        children(node).forEach { matchingIn(it, predicate, out) }
    }

    fun find(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        children(node).forEach { child ->
            val found = find(child, predicate)
            if (found != null) return found
        }
        return null
    }

    fun close() {
        val seen = IdentityHashMap<AccessibilityNodeInfo, Boolean>()
        for (index in obtained.indices.reversed()) {
            val node = obtained[index]
            if (seen.put(node, true) == null) runCatching { node.recycle() }
        }
        roots.forEach { node ->
            if (seen.put(node, true) == null) runCatching { node.recycle() }
        }
    }
}

private fun AccessibilityNodeInfo.containsQuery(query: String): Boolean {
    val text = text?.toString().orEmpty()
    val description = contentDescription?.toString().orEmpty()
    return text.contains(query, ignoreCase = true) || description.contains(query, ignoreCase = true)
}

private fun AccessibilityNodeInfo.textSpecificity(query: String): Int {
    val text = text?.toString().orEmpty()
    val description = contentDescription?.toString().orEmpty()
    return when {
        text.equals(query, true) || description.equals(query, true) -> 0
        text.startsWith(query, true) || description.startsWith(query, true) -> 1
        else -> 2 + minOf(text.length.coerceAtLeast(1), description.length.coerceAtLeast(1))
    }
}
