package us.crafties.btgames.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.sin

/**
 * The few icons the app needs, drawn with paths so it needs no icon library. Each one fills the
 * DrawScope it is given, so size it with the Canvas modifier.
 */

/** A gear: a ring with eight stubby teeth. */
fun DrawScope.drawGear(color: Color) {
    val r = size.minDimension / 2f
    val teeth = 8
    for (i in 0 until teeth) {
        val a = (i * 2.0 * Math.PI / teeth).toFloat()
        val dx = cos(a)
        val dy = sin(a)
        drawLine(
            color,
            Offset(center.x + dx * r * 0.55f, center.y + dy * r * 0.55f),
            Offset(center.x + dx * r * 0.80f, center.y + dy * r * 0.80f),
            strokeWidth = r * 0.26f, cap = StrokeCap.Round,
        )
    }
    drawCircle(color, radius = r * 0.50f, style = Stroke(width = r * 0.30f))
}

/** A left arrow: shaft and chevron head. */
fun DrawScope.drawBack(color: Color) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = w * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    drawPath(
        Path().apply {
            moveTo(w * 0.86f, h * 0.5f); lineTo(w * 0.16f, h * 0.5f)
            moveTo(w * 0.46f, h * 0.2f); lineTo(w * 0.16f, h * 0.5f); lineTo(w * 0.46f, h * 0.8f)
        },
        color, style = stroke,
    )
}
