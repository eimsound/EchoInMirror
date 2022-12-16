package cn.apisium.eim.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import cn.apisium.eim.EchoInMirror

@Composable
fun EditorGrid(
    noteWidth: Dp,
    horizontalScrollState: ScrollState,
    topPadding: Float? = null,
    modifier: Modifier = Modifier.fillMaxSize(),
    onDraw: (DrawScope.() -> Unit)? = null
) {
    val outlineColor = MaterialTheme.colorScheme.surfaceVariant
    val barsOutlineColor = MaterialTheme.colorScheme.outlineVariant
    val timeSigDenominator = EchoInMirror.currentPosition.timeSigDenominator
    val timeSigNumerator = EchoInMirror.currentPosition.timeSigNumerator
    val ppq = EchoInMirror.currentPosition.ppq
    Canvas(modifier) {
        val noteWidthPx = noteWidth.toPx()
        val horizontalScrollValue = horizontalScrollState.value
        val beatsWidth = noteWidthPx * ppq
        val drawBeats = noteWidthPx > 0.1F
        val horizontalDrawWidth = if (drawBeats) beatsWidth else beatsWidth * timeSigNumerator
        val highlightWidth = if (drawBeats) timeSigDenominator else timeSigDenominator * timeSigNumerator
        for (i in (horizontalScrollValue / horizontalDrawWidth).toInt()..((horizontalScrollValue + size.width) / horizontalDrawWidth).toInt()) {
            val x = i * horizontalDrawWidth - horizontalScrollState.value
            drawLine(
                color = if (i % highlightWidth == 0) barsOutlineColor else outlineColor,
                start = Offset(x, topPadding ?: 0F),
                end = Offset(x, size.height),
                strokeWidth = 1F
            )
        }

        if (onDraw != null) onDraw()
    }
}