package com.lytalk.dodo.ui

import android.view.View
import android.view.ViewParent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/**
 * 页面里的竖向滚动。
 *
 * 嘟嘟自己开着无障碍。滑动时如果改了布局位置，Compose 会在主线程重排无障碍树，列表就会一顿一顿。
 * 这里先把内容画进一层，手指滑动时只平移这一层，点击位置仍跟着画面走。
 * 新页面要滚动就用这个。只有长到放不进一层的内容（例如带图标的全部应用）才用 LazyColumn。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LayerScrollColumn(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    val layer = rememberGraphicsLayer()
    val view = LocalView.current
    val offset = remember(layer, view) { SlideOffset(layer, view) }
    var viewport by remember { mutableIntStateOf(0) }
    var contentHeight by remember { mutableIntStateOf(0) }
    val maxOffset = (contentHeight - viewport).coerceAtLeast(0).toFloat()
    val scrollState = rememberScrollableState { delta ->
        offset.scrollBy(delta, maxOffset)
    }
    LaunchedEffect(maxOffset) { offset.clamp(maxOffset) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides NoChaseBringIntoView) {
        Box(
            modifier
                .fillMaxSize()
                .onSizeChanged { viewport = it.height }
                .clipToBounds()
                .scrollable(
                    state = scrollState,
                    orientation = Orientation.Vertical,
                    overscrollEffect = null,
                    bringIntoViewSpec = NoChaseBringIntoView,
                ),
        ) {
            Column(
                Modifier
                    .wrapContentHeight(align = Alignment.Top, unbounded = true)
                    .slideBy(layer)
                    .onSizeChanged { contentHeight = it.height }
                    .padding(contentPadding),
                verticalArrangement = verticalArrangement,
                content = content,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private object NoChaseBringIntoView : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

private class SlideOffset(
    private val layer: GraphicsLayer,
    private val view: View,
) {
    var value = 0f

    fun scrollBy(delta: Float, max: Float): Float {
        val next = (value + delta).coerceIn(-max, 0f)
        val consumed = next - value
        if (consumed != 0f) moveTo(next)
        return consumed
    }

    fun clamp(max: Float) {
        val next = value.coerceIn(-max, 0f)
        if (next != value) moveTo(next)
    }

    private fun moveTo(next: Float) {
        value = next
        layer.translationY = next
        val parent: ViewParent? = view.parent
        parent?.onDescendantInvalidated(view, view)
    }
}

private fun Modifier.slideBy(layer: GraphicsLayer): Modifier = this.then(SlideElement(layer))

private class SlideElement(
    private val layer: GraphicsLayer,
) : ModifierNodeElement<SlideNode>() {
    override fun create(): SlideNode = SlideNode(layer)

    override fun update(node: SlideNode) {
        node.layer = layer
    }

    override fun equals(other: Any?): Boolean = other is SlideElement && other.layer === layer

    override fun hashCode(): Int = layer.hashCode()
}

private class SlideNode(
    var layer: GraphicsLayer,
) : Modifier.Node(), LayoutModifierNode {
    override val shouldAutoInvalidate: Boolean
        get() = false

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        val drawn = layer
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0, drawn)
        }
    }
}
