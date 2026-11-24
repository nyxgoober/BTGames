package us.crafties.btgames.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope

/**
 * The board both board games share: checkers and chess are the same eight-by-eight board with
 * the same tap-or-drag mechanics — only the pieces and the rules differ. The squares, the last
 * move tint, the selection outline, the landing dots and the gesture live here so the two
 * games cannot drift apart; each screen draws its own pieces, glide and drag on top.
 */

/** A piece mid-drag: which square it was picked up from, and where the finger is (canvas px). */
data class BoardDrag(val cell: Int, val pos: Offset)

/**
 * Where square [i] (0..63) is drawn. A flipped board is turned a half-turn (row r becomes 7-r,
 * column c becomes 7-c), so the player whose pieces start at the top sees them at the bottom
 * instead of playing upside-down. The turn is its own inverse, and it keeps every square's
 * colour (r+c keeps its parity), so the same call maps a drawn slot back to its square.
 */
fun viewCell(i: Int, flipped: Boolean): Int = if (flipped) 63 - i else i

/** Centre of square [i] (0..63), in canvas px; [flipped] draws the board from the other end. */
fun DrawScope.boardCenter(i: Int, flipped: Boolean = false): Offset {
    val sq = size.width / 8f
    val v = viewCell(i, flipped)
    return Offset((v % 8 + 0.5f) * sq, (v / 8 + 0.5f) * sq)
}

/**
 * The board itself: chalk and court squares (dark in both themes, like the paddle court), the
 * last move tinted so both players can follow it, and the selection outlined. Call before the
 * pieces; landing dots go after them (a chess landing may hold an enemy to be taken).
 */
fun DrawScope.drawCheckeredBoard(
    tintFrom: Int = -1,
    tintTo: Int = -1,
    selected: Int = -1,
    flipped: Boolean = false,
) {
    val sq = size.width / 8f
    for (r in 0..7) {
        for (c in 0..7) {
            val i = viewCell(r * 8 + c, flipped) // the square drawn in this slot
            val topLeft = Offset(c * sq, r * sq)
            drawRect(if ((r + c) % 2 == 0) Pal.Chalk else Pal.Court, topLeft, Size(sq, sq))
            if (i == tintFrom || i == tintTo) {
                drawRect(Pal.Teal.copy(alpha = 0.30f), topLeft, Size(sq, sq))
            }
            if (i == selected) {
                drawRect(Pal.Teal, topLeft, Size(sq, sq), style = Stroke(width = sq * 0.07f))
            }
        }
    }
}

/** Teal dots marking every square the current selection may land on. */
fun DrawScope.drawTargetDots(targets: Iterable<Int>, flipped: Boolean = false) {
    val sq = size.width / 8f
    for (t in targets) drawCircle(Pal.Teal, sq * 0.13f, boardCenter(t, flipped))
}

/**
 * The gesture both board games use: a press that never leaves the touch slop is a tap;
 * anything further is a drag whose piece rides the finger and drops where it lifts. A drop
 * back onto the square it came from counts as a tap (a sloppy selection), and a release off
 * the board snaps back with the selection kept. [liftable] says which squares can be picked
 * up; [onDrop] applies the move itself.
 */
suspend fun PointerInputScope.awaitBoardGesture(
    flipped: Boolean = false,
    liftable: (Int) -> Boolean,
    onDrag: (BoardDrag?) -> Unit,
    onTap: (Int) -> Unit,
    onDrop: (from: Int, to: Int) -> Unit,
) {
    awaitEachGesture {
        try {
            val down = awaitFirstDown()
            val sq = size.width / 8f
            fun cellOf(p: Offset): Int {
                val c = (p.x / sq).toInt()
                val r = (p.y / sq).toInt()
                return if (c in 0..7 && r in 0..7) viewCell(r * 8 + c, flipped) else -1
            }
            val downCell = cellOf(down.position)
            var drag = if (downCell >= 0 && liftable(downCell)) BoardDrag(downCell, down.position) else null
            onDrag(drag)
            var last = down.position
            var dist = 0f
            var pressed = true
            while (pressed) {
                val ev = awaitPointerEvent()
                val ch = ev.changes.firstOrNull { it.id == down.id }
                if (ch != null) {
                    dist += (ch.position - ch.previousPosition).getDistance()
                    last = ch.position
                    if (drag != null) {
                        drag = drag.copy(pos = ch.position)
                        onDrag(drag)
                    }
                    pressed = ch.pressed
                } else if (ev.changes.none { it.pressed }) {
                    pressed = false
                }
            }
            if (dist <= viewConfiguration.touchSlop) {
                if (downCell >= 0) onTap(downCell)
            } else if (downCell >= 0) {
                val to = cellOf(last)
                if (to == downCell) {
                    // Lifted and put straight back: that is a tap.
                    onTap(downCell)
                } else if (to >= 0) {
                    onDrop(downCell, to)
                }
                // Released off the board: the piece snaps back, selection kept.
            }
        } finally {
            onDrag(null)
        }
    }
}
