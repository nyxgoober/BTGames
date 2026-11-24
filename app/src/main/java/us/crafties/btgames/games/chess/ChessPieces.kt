package us.crafties.btgames.games.chess

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform

/**
 * Flat, chunky piece silhouettes: one solid shape per part, a heavy outline in a darker shade of
 * the same colour, no gradients and no detail that disappears below about 30 dp. Every piece is
 * built once in unit space (it spans roughly -0.5..0.5 on both axes) and scaled to the square it
 * lands on, so the same art serves the board, the promotion row and the menu tile.
 *
 * The outline is drawn as a single silhouette under the whole piece: every part is filled and
 * stroked in the outline colour first, then the piece colour is painted on top. Overlapping parts
 * therefore share one continuous edge instead of showing the seam where they meet.
 */

/** Outline thickness in unit space. */
private const val OUTLINE = 0.05f

/** How much every corner is rounded, in unit space — the friendliest part of the look. */
private const val ROUND = 0.035f

/** One piece's shapes: [body] is filled, [marks] are details painted in the outline colour. */
private class Art(val body: List<Path>, val marks: List<Pair<Path, Float>> = emptyList())

/** type is `ChessPiece.code % 7`: 1 pawn … 6 king. Built once and shared; never mutated. */
private val artByType: Map<Int, Art> = mapOf(1 to pawnArt(), 2 to knightArt(), 3 to bishopArt(), 4 to rookArt(), 5 to queenArt(), 6 to kingArt())

/**
 * Draws [piece] centred at [center], scaled to a box of [box] px, in [fill] with a darker
 * outline of the same hue. [ChessPiece.EMPTY] draws nothing.
 */
fun DrawScope.drawChessPiece(piece: ChessPiece, fill: Color, center: Offset, box: Float) {
    if (piece == ChessPiece.EMPTY || box <= 0f) return
    val art = artByType[piece.code % 7] ?: return
    val edge = Color(fill.red * 0.62f, fill.green * 0.62f, fill.blue * 0.62f)
    // Pivot MUST be explicit: DrawTransform.scale's default pivot is size.center, which would
    // fling unit-space shapes far off-canvas once we have already translated to the square.
    withTransform({ translate(center.x, center.y); scale(box, box, pivot = Offset.Zero) }) {
        // 1. The silhouette: fill plus an outward stroke, so the union of all parts gets one
        //    edge of OUTLINE and every corner is rounded by ROUND.
        for (path in art.body) {
            drawPath(path, edge)
            drawPath(path, edge, style = Stroke(width = 2f * (OUTLINE + ROUND), join = StrokeJoin.Round))
        }
        // 2. The piece colour, grown by ROUND so the visible ring is exactly OUTLINE.
        for (path in art.body) {
            drawPath(path, fill)
            drawPath(path, fill, style = Stroke(width = 2f * ROUND, join = StrokeJoin.Round))
        }
        // 3. Details on top, in the outline colour.
        for ((path, width) in art.marks) {
            if (width <= 0f) {
                drawPath(path, edge)
            } else {
                drawPath(path, edge, style = Stroke(width = width, cap = StrokeCap.Round))
            }
        }
    }
}

// ---- unit-space shape builders ---------------------------------------------------------------

private fun oval(cx: Float, cy: Float, r: Float): Path = Path().apply {
    addOval(Rect(cx - r, cy - r, cx + r, cy + r))
}

private fun slab(x0: Float, y0: Float, x1: Float, y1: Float, r: Float): Path = Path().apply {
    addRoundRect(RoundRect(Rect(x0, y0, x1, y1), CornerRadius(r, r)))
}

private fun poly(vararg pts: Float): Path = Path().apply {
    moveTo(pts[0], pts[1])
    var i = 2
    while (i < pts.size) {
        lineTo(pts[i], pts[i + 1])
        i += 2
    }
    close()
}

// ---- the pieces ------------------------------------------------------------------------------

private fun pawnArt() = Art(
    body = listOf(
        oval(0f, -0.30f, 0.15f),
        Path().apply {
            moveTo(-0.11f, -0.17f)
            cubicTo(-0.20f, -0.02f, -0.26f, 0.10f, -0.27f, 0.29f)
            lineTo(0.27f, 0.29f)
            cubicTo(0.26f, 0.10f, 0.20f, -0.02f, 0.11f, -0.17f)
            close()
        },
        slab(-0.35f, 0.27f, 0.35f, 0.45f, 0.05f),
    ),
)

private fun rookArt() = Art(
    body = listOf(
        // Crenellated top: three merlons, two gaps.
        poly(
            -0.38f, -0.45f, -0.26f, -0.45f, -0.26f, -0.34f, -0.10f, -0.34f, -0.10f, -0.45f,
            0.10f, -0.45f, 0.10f, -0.34f, 0.26f, -0.34f, 0.26f, -0.45f, 0.38f, -0.45f,
            0.38f, -0.27f, -0.38f, -0.27f,
        ),
        // Body, waisted slightly inward at the middle.
        poly(
            -0.30f, -0.29f, -0.26f, 0.00f, -0.25f, 0.26f,
            0.25f, 0.26f, 0.26f, 0.00f, 0.30f, -0.29f,
        ),
        slab(-0.38f, 0.24f, 0.38f, 0.42f, 0.05f),
    ),
)

private fun knightArt() = Art(
    body = listOf(
        Path().apply {
            moveTo(-0.06f, 0.26f)
            // Front of the neck, up to the jaw.
            cubicTo(-0.16f, 0.18f, -0.24f, 0.10f, -0.26f, -0.02f)
            // Mouth line out to the muzzle.
            lineTo(-0.44f, -0.13f)
            // Round the nose.
            cubicTo(-0.48f, -0.18f, -0.47f, -0.23f, -0.44f, -0.26f)
            lineTo(-0.30f, -0.33f)
            // Ear, then the dip between the ears.
            lineTo(-0.15f, -0.47f)
            lineTo(-0.04f, -0.33f)
            // Back of the skull into the mane.
            cubicTo(0.04f, -0.36f, 0.12f, -0.34f, 0.17f, -0.24f)
            cubicTo(0.24f, -0.10f, 0.28f, 0.04f, 0.33f, 0.26f)
            close()
        },
        slab(-0.36f, 0.24f, 0.36f, 0.42f, 0.05f),
    ),
    // The eye: a dot reads at any size a knight is drawn at.
    marks = listOf(oval(-0.15f, -0.25f, 0.045f) to 0f),
)

private fun bishopArt() = Art(
    body = listOf(
        oval(0f, -0.42f, 0.06f),
        Path().apply {
            moveTo(0f, -0.37f)
            cubicTo(-0.16f, -0.30f, -0.27f, -0.14f, -0.22f, 0.13f)
            lineTo(0.22f, 0.13f)
            cubicTo(0.27f, -0.14f, 0.16f, -0.30f, 0f, -0.37f)
            close()
        },
        slab(-0.26f, 0.11f, 0.26f, 0.22f, 0.04f),
        slab(-0.35f, 0.20f, 0.35f, 0.40f, 0.05f),
    ),
    // The mitre's slit.
    marks = listOf(
        Path().apply {
            moveTo(-0.06f, -0.16f)
            lineTo(0.10f, 0.00f)
        } to 0.06f,
    ),
)

private fun queenArt() = Art(
    body = listOf(
        // Crown: five peaks and four valleys, with a ball on every peak.
        poly(
            -0.38f, -0.30f, -0.27f, -0.08f, -0.19f, -0.36f, -0.09f, -0.12f, 0f, -0.44f,
            0.09f, -0.12f, 0.19f, -0.36f, 0.27f, -0.08f, 0.38f, -0.30f, 0.34f, 0.02f, -0.34f, 0.02f,
        ),
        oval(-0.38f, -0.30f, 0.055f),
        oval(-0.19f, -0.36f, 0.055f),
        oval(0f, -0.44f, 0.055f),
        oval(0.19f, -0.36f, 0.055f),
        oval(0.38f, -0.30f, 0.055f),
        // Body flaring into the base.
        poly(-0.22f, -0.04f, -0.32f, 0.26f, 0.32f, 0.26f, 0.22f, -0.04f),
        slab(-0.40f, 0.24f, 0.40f, 0.44f, 0.05f),
    ),
)

private fun kingArt() = Art(
    body = listOf(
        // Cross: the vertical bar reaches down into the crown so the two read as one shape.
        slab(-0.045f, -0.50f, 0.045f, -0.10f, 0.02f),
        slab(-0.14f, -0.42f, 0.14f, -0.31f, 0.02f),
        // Crown with a dip in the middle where the cross rises, then the body.
        poly(
            -0.32f, -0.10f, -0.18f, -0.26f, 0f, -0.14f, 0.18f, -0.26f, 0.32f, -0.10f,
            0.34f, 0.04f, 0.30f, 0.28f, -0.30f, 0.28f, -0.34f, 0.04f,
        ),
        slab(-0.40f, 0.26f, 0.40f, 0.46f, 0.05f),
    ),
)
