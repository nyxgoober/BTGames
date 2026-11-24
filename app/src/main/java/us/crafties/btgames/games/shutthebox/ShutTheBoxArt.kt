package us.crafties.btgames.games.shutthebox

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import us.crafties.btgames.ui.Pal
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** The wood and brass of the physical game. Fixed colours, same in light and dark mode. */
object BoxWood {
    val Light = Color(0xFFE2A862)
    val Mid = Color(0xFFC98A45)
    val Dark = Color(0xFF8E5A28)
    val Deep = Color(0xFF5A3517)
    val Slot = Color(0xFF3A2410)
    val Brass = Color(0xFFE8C766)
    val Engrave = Color(0xFF4A2B10)
}

/**
 * A six-sided die seen from above: ivory face, soft edge, and real pips for [value] (1..6).
 * Fills the DrawScope it is given.
 */
fun DrawScope.drawDie(value: Int, face: Color = Color(0xFFFFFBF2), pip: Color = Color(0xFF1E252B)) {
    val s = size.minDimension
    val r = s * 0.18f
    // soft shadow underneath
    drawRoundRect(
        Color.Black.copy(alpha = 0.22f), Offset(s * 0.03f, s * 0.06f),
        Size(s * 0.94f, s * 0.94f), CornerRadius(r),
    )
    drawRoundRect(face, Offset.Zero, Size(s * 0.94f, s * 0.94f), CornerRadius(r))
    drawRoundRect(
        Color(0xFFB9B2A3), Offset.Zero, Size(s * 0.94f, s * 0.94f), CornerRadius(r),
        style = Stroke(width = s * 0.03f),
    )
    val w = s * 0.94f
    val lo = w * 0.27f
    val mid = w * 0.5f
    val hi = w * 0.73f
    val pr = w * 0.085f
    fun dot(x: Float, y: Float) {
        drawCircle(pip, pr, Offset(x, y))
    }
    when (value.coerceIn(1, 6)) {
        1 -> dot(mid, mid)
        2 -> { dot(lo, lo); dot(hi, hi) }
        3 -> { dot(lo, lo); dot(mid, mid); dot(hi, hi) }
        4 -> { dot(lo, lo); dot(hi, lo); dot(lo, hi); dot(hi, hi) }
        5 -> { dot(lo, lo); dot(hi, lo); dot(mid, mid); dot(lo, hi); dot(hi, hi) }
        else -> { dot(lo, lo); dot(hi, lo); dot(lo, mid); dot(hi, mid); dot(lo, hi); dot(hi, hi) }
    }
}

/** The tile on the menu: rod-pivot tiles (the last two flipped down), green felt, two dice. */
fun DrawScope.drawShutTheBoxGlyph() {
    val s = size.width
    drawRoundRect(
        Brush.verticalGradient(listOf(BoxWood.Light, BoxWood.Dark), s * 0.1f, s * 0.9f),
        Offset(s * 0.02f, s * 0.10f), Size(s * 0.96f, s * 0.80f), CornerRadius(s * 0.08f),
    )
    drawRoundRect(
        BoxWood.Deep, Offset(s * 0.02f, s * 0.10f), Size(s * 0.96f, s * 0.80f), CornerRadius(s * 0.08f),
        style = Stroke(width = s * 0.035f),
    )
    // tile bay with the rod
    drawRoundRect(BoxWood.Slot, Offset(s * 0.08f, s * 0.16f), Size(s * 0.84f, s * 0.34f), CornerRadius(s * 0.03f))
    val rodY = s * 0.33f
    drawLine(BoxWood.Brass, Offset(s * 0.06f, rodY), Offset(s * 0.94f, rodY), strokeWidth = s * 0.02f)
    val tw = s * 0.13f
    val gap = s * 0.03f
    val x0 = s * 0.08f + (s * 0.84f - (5 * tw + 4 * gap)) / 2f
    val th = s * 0.28f
    for (i in 0 until 5) {
        val x = x0 + i * (tw + gap)
        val r = CornerRadius(tw * 0.18f)
        if (i < 3) {
            drawRoundRect(BoxWood.Light, Offset(x, rodY - th / 2f), Size(tw, th), r)
            drawRoundRect(BoxWood.Dark, Offset(x, rodY - th / 2f), Size(tw, th), r, style = Stroke(width = tw * 0.08f))
            drawCircle(BoxWood.Engrave, tw * 0.13f, Offset(x + tw / 2f, rodY + th * 0.08f))
        } else {
            // flipped down: only the lower half is left showing, sunk and dark
            drawRoundRect(Color(0xFF8A5A2A), Offset(x, rodY - th * 0.04f), Size(tw, th * 0.54f), r)
            drawRoundRect(BoxWood.Deep, Offset(x, rodY - th * 0.04f), Size(tw, th * 0.54f), r, style = Stroke(width = tw * 0.08f))
        }
        drawCircle(BoxWood.Brass, tw * 0.1f, Offset(x, rodY))
        drawCircle(BoxWood.Brass, tw * 0.1f, Offset(x + tw, rodY))
    }
    // felt with two dice
    drawRoundRect(
        Brush.verticalGradient(listOf(FeltTop, FeltBottom), s * 0.55f, s * 0.84f),
        Offset(s * 0.08f, s * 0.55f), Size(s * 0.84f, s * 0.29f), CornerRadius(s * 0.03f),
    )
    withTransform({ translate(s * 0.27f, s * 0.58f); rotate(-12f, Offset(s * 0.12f, s * 0.12f)) }) { drawGlyphDie(3, s * 0.24f) }
    withTransform({ translate(s * 0.55f, s * 0.59f); rotate(14f, Offset(s * 0.12f, s * 0.12f)) }) { drawGlyphDie(5, s * 0.24f) }
}

private fun DrawScope.drawGlyphDie(value: Int, side: Float) {
    val k = side / size.minDimension
    withTransform({ scale(k, k, Offset.Zero) }) { drawDie(value) }
}

/** Width of one tile on a board of width [w] with [tiles] tiles (frame pad 5%, inner margin 1.2%, gaps 0.8%). */
fun stbTileWidth(w: Float, tiles: Int): Float = (w - 2 * w * 0.05f - 2 * w * 0.012f - (tiles - 1) * w * 0.008f) / tiles

/** Which tile (1-based) is under horizontal position [x] on a board of width [w], or 0 for none. */
fun stbTileAt(x: Float, w: Float, tiles: Int): Int {
    val tw = stbTileWidth(w, tiles)
    val step = tw + w * 0.008f
    val rel = x - (w * 0.05f + w * 0.012f)
    if (rel < 0f) return 0
    val i = (rel / step).toInt()
    return if (i < tiles && rel - i * step <= tw) i + 1 else 0
}

/** Board proportions: width 1, height [BOARD_ASPECT]. */
const val BOARD_ASPECT = 0.74f

private val FeltTop = Color(0xFF3E9148)
private val FeltBottom = Color(0xFF2A7836)

/**
 * One player's box, like the wooden original: nine tiles that each turn on a brass rod through
 * their middle, a wooden rail, and a flat green felt dice area in front. Fills the DrawScope.
 *
 * [folds] is 0 = tile flat and readable (open) .. 1 = tile turned over on its rod (shut).
 * [tumble] is 0..1 while the dice are being thrown, 1 when they have landed.
 */
fun DrawScope.drawStbBoard(
    folds: List<State<Float>>,
    picked: Set<Int>,
    die1: Int,
    die2: Int,
    rollId: Int,
    tumble: Float,
    paint: android.graphics.Paint,
    label: String,
    active: Boolean,
    out: Boolean,
    tiles: Int,
) {
    val w = size.width
    val h = size.height
    val pad = w * 0.05f
    val frame = CornerRadius(w * 0.05f)

    // wooden frame
    drawRoundRect(Brush.verticalGradient(listOf(BoxWood.Light, BoxWood.Mid), 0f, h), Offset.Zero, size, frame)
    drawRoundRect(
        if (active) Pal.Teal else BoxWood.Deep, Offset.Zero, size, frame,
        style = Stroke(width = w * (if (active) 0.022f else 0.012f)),
    )

    // tile bay with the rod
    val bayH = h * 0.40f
    drawRoundRect(BoxWood.Slot, Offset(pad, pad), Size(w - 2 * pad, bayH), CornerRadius(w * 0.02f))
    val rodY = pad + bayH / 2f
    drawLine(Color(0xFFB9A25A), Offset(pad * 0.7f, rodY), Offset(w - pad * 0.7f, rodY), strokeWidth = w * 0.014f)
    drawLine(BoxWood.Brass, Offset(pad * 0.7f, rodY - w * 0.003f), Offset(w - pad * 0.7f, rodY - w * 0.003f), strokeWidth = w * 0.005f)

    val m = w * 0.012f
    val gap = w * 0.008f
    val tw = stbTileWidth(w, tiles)
    val th = bayH * 0.88f
    for (i in 0 until tiles) {
        val f = folds[i].value.coerceIn(0f, 1f)
        val x = pad + m + i * (tw + gap)
        val cx = x + tw / 2f
        // Flipping down: the upper half of the tile drops into the slot while the lower half stays,
        // so the tile folds away toward the player instead of lifting off the box.
        val upper = (th / 2f) * cos(f * 1.45f).coerceAtLeast(0.08f)
        val top = rodY - upper
        val hh = upper + th / 2f
        val tr = CornerRadius(tw * 0.14f)
        drawRoundRect(BoxWood.Deep, Offset(x, top + hh * 0.03f), Size(tw, hh), tr)
        drawRoundRect(
            Brush.verticalGradient(
                listOf(lerp(BoxWood.Light, Color(0xFF8A5A2A), f), lerp(BoxWood.Mid, Color(0xFF6E4520), f)), top, top + hh,
            ),
            Offset(x, top), Size(tw, hh * 0.96f), tr,
        )
        drawRoundRect(BoxWood.Dark, Offset(x, top), Size(tw, hh * 0.96f), tr, style = Stroke(width = tw * 0.05f))
        // shadow cast into the slot above the fallen tile
        if (f > 0f) drawRect(Color.Black.copy(alpha = 0.35f * f), Offset(x, rodY - th / 2f), Size(tw, th / 2f - upper))
        if ((i + 1) in picked) {
            drawRoundRect(Pal.Teal, Offset(x, top), Size(tw, hh * 0.96f), tr, style = Stroke(width = tw * 0.12f))
        }
        val a = ((1f - f * 1.5f).coerceIn(0f, 1f) * 255).toInt()
        if (a > 0) {
            paint.color = android.graphics.Color.argb(a, 0x4A, 0x2B, 0x10)
            paint.textSize = if (tiles > 9) min(th * 0.46f, tw * 0.8f) else th * 0.46f
            paint.textAlign = android.graphics.Paint.Align.CENTER
            paint.isFakeBoldText = true
            drawContext.canvas.nativeCanvas.drawText((i + 1).toString(), cx, rodY + th * 0.16f, paint)
        }
        // brass pins where the rod passes through each tile
        drawCircle(BoxWood.Brass, w * 0.0075f, Offset(x, rodY))
        drawCircle(BoxWood.Brass, w * 0.0075f, Offset(x + tw, rodY))
    }

    // rail between the tiles and the dice area
    val railTop = pad + bayH + pad * 0.25f
    drawRoundRect(BoxWood.Dark, Offset(pad, railTop), Size(w - 2 * pad, pad * 0.8f), CornerRadius(w * 0.01f))
    drawRect(Color.White.copy(alpha = 0.25f), Offset(pad, railTop), Size(w - 2 * pad, pad * 0.18f))

    // flat green felt
    val feltTop = railTop + pad * 0.8f
    val feltH = h - pad * 1.6f - feltTop
    val feltX = pad
    val feltW = w - 2 * pad
    drawRoundRect(Brush.verticalGradient(listOf(FeltTop, FeltBottom), feltTop, feltTop + feltH), Offset(feltX, feltTop), Size(feltW, feltH), CornerRadius(w * 0.015f))
    val rnd = java.util.Random(7)
    repeat(110) {
        val gx = feltX + rnd.nextFloat() * feltW
        val gy = feltTop + w * 0.014f + rnd.nextFloat() * (feltH - w * 0.014f)
        val lean = (rnd.nextFloat() - 0.5f) * w * 0.012f
        val tone = if (rnd.nextBoolean()) Color(0x33C8F0A0) else Color(0x33103A12)
        drawLine(tone, Offset(gx, gy), Offset(gx + lean, gy - w * 0.013f), strokeWidth = w * 0.0035f)
    }
    drawRoundRect(Color(0x66103A12), Offset(feltX, feltTop), Size(feltW, feltH), CornerRadius(w * 0.015f), style = Stroke(width = w * 0.01f))

    // dice thrown onto the felt
    val ds = min(feltH * 0.62f, w * 0.17f)
    val cy = feltTop + feltH / 2f
    val rolled = die1 != 0
    val values = if (rolled) listOf(die1, die2) else listOf(4, 2)
    val twoDice = !rolled || die2 != 0
    val centers = if (twoDice) listOf(w * 0.38f, w * 0.62f) else listOf(w * 0.5f)
    centers.forEachIndexed { idx, bx ->
        val e = if (rolled) 1f - tumble.coerceIn(0f, 1f) else 0f
        val dir = if (idx == 0) -1f else 1f
        val px = bx + dir * e * w * 0.5f
        val py = cy - sin((1f - e) * PI.toFloat()) * feltH * 0.35f * e
        val rot = (if (idx == 0) -9f else 8f) + e * 540f * -dir
        val face = if (rolled && e > 0f) (((rollId * 7 + (tumble * 12).toInt() * 5 + idx * 3) % 6) + 1) else values[idx]
        withTransform({ translate(px - ds / 2f, py - ds / 2f); rotate(rot, Offset(ds / 2f, ds / 2f)) }) {
            val k = ds / size.minDimension
            withTransform({ scale(k, k, Offset.Zero) }) {
                if (rolled) drawDie(face) else drawDie(face, face = Color(0xFFD9D4C8), pip = Color(0xFF8C867A))
            }
        }
    }

    // nameplate in the lower rim
    paint.color = android.graphics.Color.argb(255, 0x4A, 0x2B, 0x10)
    paint.textSize = pad * 0.85f
    paint.textAlign = android.graphics.Paint.Align.CENTER
    paint.isFakeBoldText = true
    drawContext.canvas.nativeCanvas.drawText(label, w / 2f, h - pad * 0.45f, paint)

    if (out) drawRoundRect(Color(0x66000000), Offset.Zero, size, frame)
}
