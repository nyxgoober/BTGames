package us.crafties.btgames.games.last

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import us.crafties.btgames.ui.Pal
import kotlin.math.cos
import kotlin.math.sin

/** The flat paint of a card colour. A wild has none of its own, so it is drawn dark. */
fun lastPaint(c: LastColor?): Color = when (c) {
    LastColor.RED -> Pal.CardRed
    LastColor.YELLOW -> Pal.CardYellow
    LastColor.GREEN -> Pal.CardGreen
    LastColor.BLUE -> Pal.CardBlue
    null -> Pal.Court
}

/** Text and icons on a card: dark on yellow, off-white on everything else. */
fun lastInk(c: LastColor?): Color = if (c == LastColor.YELLOW) Pal.Court else Pal.Chalk

/**
 * One flat card: a solid colour, a white edge, and a big mark in the middle. No oval and no
 * tilted numerals: numbers are plain, and Ban, Spin and Color Change are drawn icons of our own.
 */
@Composable
fun LastCardView(
    card: LastCard,
    modifier: Modifier = Modifier,
    width: Dp = 64.dp,
    dimmed: Boolean = false,
) {
    val height = width * 1.45f
    val ink = lastInk(card.color)
    Box(
        modifier = modifier
            .size(width, height)
            .alpha(if (dimmed) 0.45f else 1f)
            .clip(RoundedCornerShape(width * 0.14f))
            .background(lastPaint(card.color))
            .border(width * 0.05f, Pal.Chalk, RoundedCornerShape(width * 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        when (card.kind) {
            LastKind.NUMBER -> Text(card.number.toString(), color = ink, fontSize = (width.value * 0.55f).sp, fontWeight = FontWeight.Black)
            LastKind.PLUS2 -> Text("+2", color = ink, fontSize = (width.value * 0.42f).sp, fontWeight = FontWeight.Black)
            LastKind.COLOR_PLUS2_SQ -> Box(contentAlignment = Alignment.Center) {
                CardIcon(width * 0.62f) { drawColorChange() }
                Text("+2²", color = Pal.Chalk, fontSize = (width.value * 0.30f).sp, fontWeight = FontWeight.Black)
            }
            LastKind.BAN -> CardIcon(width * 0.55f) { drawBan(ink) }
            LastKind.SPIN -> CardIcon(width * 0.60f) { drawSpin(ink) }
            LastKind.COLOR_CHANGE -> CardIcon(width * 0.62f) { drawColorChange() }
        }
    }
}

@Composable
private fun CardIcon(size: Dp, draw: DrawScope.() -> Unit) {
    Canvas(Modifier.size(size)) { draw() }
}

/** The back of a card: dark, with a teal frame and "LAST!" running diagonally across it. */
@Composable
fun LastCardBack(modifier: Modifier = Modifier, width: Dp = 64.dp) {
    val edge = RoundedCornerShape(width * 0.14f)
    Box(
        modifier = modifier
            .size(width, width * 1.45f)
            .clip(edge)
            .background(Pal.Court)
            .border(width * 0.05f, Pal.Teal, edge),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(width * 0.11f)
                .border(width * 0.018f, Pal.Teal.copy(alpha = 0.55f), RoundedCornerShape(width * 0.10f)),
        )
        Text(
            "LAST!",
            color = Pal.Teal,
            fontSize = (width.value * 0.24f).sp,
            fontWeight = FontWeight.Black,
            maxLines = 1,
            modifier = Modifier.graphicsLayer { rotationZ = -45f },
        )
    }
}

// ---- icons, drawn in a square of side `size.width` -------------------------------------------

/** Ban: a bold X. */
fun DrawScope.drawBan(color: Color) {
    val s = size.width
    val stroke = s * 0.17f
    drawLine(color, Offset(s * 0.16f, s * 0.16f), Offset(s * 0.84f, s * 0.84f), stroke, StrokeCap.Round)
    drawLine(color, Offset(s * 0.16f, s * 0.84f), Offset(s * 0.84f, s * 0.16f), stroke, StrokeCap.Round)
}

/** Spin: one open loop with a single arrowhead (clockwise). */
fun DrawScope.drawSpin(color: Color) {
    val s = size.width
    val stroke = s * 0.14f
    val r = s * 0.34f
    val c = Offset(s / 2f, s / 2f)
    val start = 30f
    val sweep = 285f
    drawArc(
        color, startAngle = start, sweepAngle = sweep, useCenter = false,
        topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2f, r * 2f),
        style = Stroke(width = stroke, cap = StrokeCap.Round),
    )
    val a = Math.toRadians((start + sweep).toDouble())
    val end = Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
    val tangent = Offset(-sin(a).toFloat(), cos(a).toFloat())   // direction of travel (clockwise)
    val normal = Offset(cos(a).toFloat(), sin(a).toFloat())
    val head = s * 0.26f
    val half = s * 0.20f
    val tip = Offset(end.x + tangent.x * head, end.y + tangent.y * head)
    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(end.x + normal.x * half, end.y + normal.y * half)
        lineTo(end.x - normal.x * half, end.y - normal.y * half)
        close()
    }
    drawPath(path, color)
}

/** Color Change: four rounded squares, one per colour. */
fun DrawScope.drawColorChange() {
    val s = size.width
    val gap = s * 0.08f
    val q = (s - gap) / 2f
    val r = CornerRadius(q * 0.22f)
    drawRoundRect(Pal.CardRed, Offset(0f, 0f), Size(q, q), r)
    drawRoundRect(Pal.CardYellow, Offset(q + gap, 0f), Size(q, q), r)
    drawRoundRect(Pal.CardGreen, Offset(0f, q + gap), Size(q, q), r)
    drawRoundRect(Pal.CardBlue, Offset(q + gap, q + gap), Size(q, q), r)
}

/** The tile on the menu: three flat cards fanned out. */
fun DrawScope.drawLastGlyph() {
    val s = size.width
    val w = s * 0.46f
    val h = w * 1.45f
    val r = CornerRadius(w * 0.14f)
    val fan = listOf(
        Triple(-20f, Pal.CardRed, Offset(s * 0.12f, s * 0.14f)),
        Triple(0f, Pal.CardYellow, Offset(s * 0.27f, s * 0.08f)),
        Triple(20f, Pal.CardBlue, Offset(s * 0.42f, s * 0.14f)),
    )
    for ((deg, color, topLeft) in fan) {
        withTransform({ rotate(deg, Offset(topLeft.x + w / 2f, topLeft.y + h)) }) {
            drawRoundRect(color, topLeft, Size(w, h), r)
            drawRoundRect(Pal.Chalk, topLeft, Size(w, h), r, style = Stroke(width = w * 0.06f))
        }
    }
}
