package us.crafties.btgames.games.fourchips

import androidx.compose.animation.core.tween
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import us.crafties.btgames.R
import us.crafties.btgames.games.fourchips.FourChipsBoard.Companion.COLS
import us.crafties.btgames.games.fourchips.FourChipsBoard.Companion.ROWS
import us.crafties.btgames.ui.Haptics
import us.crafties.btgames.ui.Pal
import us.crafties.btgames.ui.RaisedButton
import us.crafties.btgames.ui.RaisedCard
import us.crafties.btgames.ui.Tone
import us.crafties.btgames.ui.Wording

/**
 * A disc is drawn in the colour of whoever owns it. Which colour that is comes from the match
 * (random, and always opposite for the two phones), NOT from "me = red".
 */
private fun colorFor(mark: Chip, s: FourChipsUi): Color =
    Pal.paint(if (mark == s.myMark) s.mySide else s.mySide.other())

@Composable
fun FourChipsScreen(
    state: FourChipsUi,
    onColumn: (Int) -> Unit,
    onRematch: () -> Unit,
    onLeave: () -> Unit,
) {
    val tap by rememberUpdatedState(onColumn)

    // The drop animation is local-only: every applied drop (mine or theirs) falls from just
    // above the board under gravity, hits the stack (or the floor) and bounces until it settles.
    //
    // The falling chip is created synchronously in composition (keyed on the drop counter), so
    // the landing cell is hidden from the very first frame: no static flash before the fall.
    // The physics runs in the screen's own scope, not in a LaunchedEffect keyed on the drop, so a
    // chip that is still bouncing keeps bouncing when the next drop arrives instead of snapping.
    val scope = rememberCoroutineScope()
    val trail = remember { mutableListOf<Falling>() }
    val falling = remember(state.dropSeq, state.round) {
        if (state.dropSeq == 0 || state.dropCol < 0) null
        else Falling(state.dropCol, state.dropRow)
    }
    if (falling != null && trail.none { it === falling }) trail.add(falling)
    LaunchedEffect(falling) {
        val f = falling ?: return@LaunchedEffect
        scope.launch {
            dropChip(f)
            trail.remove(f)
        }
    }
    LaunchedEffect(state.outcomeForMe) {
        when (state.outcomeForMe) {
            FourResult.WIN -> Haptics.win()
            FourResult.LOSS -> Haptics.lose()
            FourResult.DRAW -> Haptics.click()
            null -> Unit
        }
    }

    Column(
        Modifier.fillMaxSize().background(Pal.Paper).safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Scoreboard(state)
        Spacer(Modifier.height(16.dp))
        StatusLine(state)
        Spacer(Modifier.weight(1f))

        // The board sits on a white card, like the panels elsewhere in the app.
        RaisedCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(COLS.toFloat() / ROWS.toFloat())
                    .pointerInput(state.board.isOver) {
                        detectTapGestures { pos ->
                            val col = (pos.x / (size.width / COLS)).toInt().coerceIn(0, COLS - 1)
                            tap(col)
                        }
                    },
            ) { drawBoard(state, trail.toList()) }
        }

        Spacer(Modifier.weight(1f))
        Footer(state, onRematch, onLeave)
    }
}

/** A chip in flight: which slot it is heading for, and where it is now (fractional rows). */
private class Falling(val col: Int, val row: Int) {
    var y by mutableFloatStateOf(START_ROW)
    var settled by mutableStateOf(false)
}

/** The fall starts just above the top row: below the header, never over it. */
private const val START_ROW = -1.2f

/** Gravity in rows per second squared: a drop from the top of a full height takes ~0.55s. */
private const val GRAVITY = 48f

/** Share of the impact speed kept on the rebound; 0.3 is a dull plastic chip, not a ball. */
private const val RESTITUTION = 0.30f

/** Rebounds slower than this (rows per second) are not worth drawing: the chip comes to rest. */
private const val REST_SPEED = 1.6f

/**
 * Steps the chip with real kinematics, once per frame: constant gravity, and on reaching its slot
 * the velocity is reversed and scaled by [RESTITUTION]. Because it is speed-driven, a long drop
 * hits harder and bounces higher than a short one, and every bounce is smaller than the last.
 */
private suspend fun dropChip(f: Falling) {
    var v = 0f
    var impacts = 0
    var last = withFrameNanos { it }
    val floor = f.row.toFloat()
    while (true) {
        val now = withFrameNanos { it }
        // Clamp so a hitch (dropped frames) cannot teleport the chip through the floor.
        val dt = ((now - last) / 1_000_000_000f).coerceAtMost(1f / 30f)
        last = now
        v += GRAVITY * dt
        var y = f.y + v * dt
        if (y >= floor) {
            val impact = v
            y = floor
            impacts++
            if (impacts == 1) Haptics.thud() else if (impact > 3f) Haptics.tick()
            if (impact < REST_SPEED) { f.y = floor; break }
            v = -impact * RESTITUTION
        }
        f.y = y
    }
    f.settled = true
}

@Composable
private fun Scoreboard(s: FourChipsUi) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Tally(
            name = s.myNick, wins = s.myWins,
            sub = if (s.myMark == Chip.FIRST) stringResource(R.string.chips_starts, s.round + 1)
            else stringResource(R.string.chips_you_next),
            color = Pal.paint(s.mySide), align = Alignment.Start,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.chips_draws), fontSize = 12.sp, color = Pal.Muted)
            Text("${s.draws}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Pal.Ink)
        }
        Tally(
            name = s.theirNick, wins = s.theirWins,
            sub = if (s.myMark != Chip.FIRST) stringResource(R.string.chips_starts, s.round + 1)
            else stringResource(R.string.chips_they_next),
            color = Pal.paint(s.mySide.other()), align = Alignment.End,
        )
    }
}

@Composable
private fun Tally(name: String, wins: Int, sub: String, color: Color, align: Alignment.Horizontal) {
    Column(horizontalAlignment = align, modifier = Modifier.padding(horizontal = 2.dp)) {
        Text(name.ifBlank { stringResource(R.string.common_player) }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
        Text("$wins", fontSize = 40.sp, fontWeight = FontWeight.Black, color = Pal.Ink)
        Text(sub, fontSize = 12.sp, color = Pal.Muted)
    }
}

@Composable
private fun StatusLine(s: FourChipsUi) {
    val opponent = stringResource(R.string.common_opponent)
    val text = when (s.outcomeForMe) {
        FourResult.WIN -> stringResource(R.string.chips_you_connect)
        FourResult.LOSS -> Wording.subject(
            s.theirNick, R.string.chips_they_connect_named, R.string.chips_they_connect_anon,
        )
        FourResult.DRAW -> stringResource(R.string.chips_full)
        null -> if (s.myTurn) stringResource(R.string.chips_your_move)
        else stringResource(R.string.chips_waiting, s.theirNick.ifBlank { opponent })
    }
    val color = when (s.outcomeForMe) {
        FourResult.WIN -> Pal.paint(s.mySide)
        FourResult.LOSS -> Pal.paint(s.mySide.other())
        else -> Pal.Ink
    }
    Crossfade(targetState = text to color, animationSpec = tween(220), label = "status") { (t, c) ->
        Text(t, fontSize = 30.sp, fontWeight = FontWeight.Black, color = c)
    }
}

@Composable
private fun Footer(s: FourChipsUi, onRematch: () -> Unit, onLeave: () -> Unit) {
    // A minimum height, not a fixed one: the button label may wrap to two lines for a long name.
    Column(Modifier.fillMaxWidth().heightIn(min = 124.dp), horizontalAlignment = Alignment.Start) {
        if (s.board.isOver) {
            if (s.theyWantRematch && !s.iWantRematch) {
                // Said above the button, not inside it: a long sentence does not fit a button.
                Text(
                    Wording.subject(s.theirNick, R.string.subj_wants_named, R.string.subj_wants_anon),
                    fontSize = 15.sp, color = Pal.Muted, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                )
            } else {
                Spacer(Modifier.height(26.dp))
            }
            RaisedButton(
                text = if (s.iWantRematch) stringResource(
                    R.string.common_waiting_rematch,
                    s.theirNick.ifBlank { stringResource(R.string.common_opponent) },
                ) else stringResource(R.string.chips_play_again),
                onClick = onRematch,
                enabled = !s.iWantRematch,
                tone = Tone.Primary,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Spacer(Modifier.height(80.dp))
        }
        TextButton(onClick = onLeave) { Text(stringResource(R.string.common_leave), color = Pal.Muted, fontWeight = FontWeight.SemiBold) }
    }
}

private fun DrawScope.drawBoard(s: FourChipsUi, chips: List<Falling>) {
    val cell = size.width / COLS
    val r = cell * 0.40f
    fun centre(i: Int) = Offset((i % COLS + 0.5f) * cell, (i / COLS + 0.5f) * cell)

    val winning = s.board.winningCells.orEmpty().toSet()
    // While a chip is falling or bouncing its landing cell stays empty; it appears the moment
    // the chip comes to rest.
    val inFlight = chips.filter { !it.settled }
    val hidden = inFlight.map { it.row * COLS + it.col }.toSet()

    // Like the real thing, the chips sit BEHIND the grid. So: the back of every hole first, then
    // the chips, then the grid plate (with the holes cut out) over the top.
    for (i in 0 until COLS * ROWS) drawCircle(Pal.Groove, r, centre(i))

    s.board.cells.forEachIndexed { i, mark ->
        if (mark == Chip.EMPTY || i in hidden) return@forEachIndexed
        // On a finished round, fade the discs that were not part of the winning four.
        val dim = s.board.winningCells != null && i !in winning
        drawDisc(centre(i), r, colorFor(mark, s).copy(alpha = if (dim) 0.28f else 1f))
    }

    // The chips in flight. They start above the board, so they are seen dropping in and then
    // pass behind the plate, showing only through the holes they go past.
    for (f in inFlight) {
        val mark = s.board.cells.getOrNull(f.row * COLS + f.col) ?: Chip.EMPTY
        if (mark == Chip.EMPTY) continue
        drawDisc(Offset((f.col + 0.5f) * cell, (f.y + 0.5f) * cell), r, colorFor(mark, s))
    }

    // The grid plate: the card's own face colour with a round hole per slot (even-odd fill), so it
    // only covers what is between the holes and blends into the card around the board.
    val plate = Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(Rect(0f, 0f, size.width, size.height))
        for (i in 0 until COLS * ROWS) {
            val c = centre(i)
            addOval(Rect(c.x - r, c.y - r, c.x + r, c.y + r))
        }
    }
    drawPath(plate, Pal.Face)

    // A bezel round every hole: hides the seam between plate and chip and gives the plate depth.
    for (i in 0 until COLS * ROWS) {
        drawCircle(Pal.EdgeShade, r * 0.91f, centre(i), style = Stroke(width = r * 0.18f))
    }
}

private fun DrawScope.drawDisc(center: Offset, r: Float, color: Color) {
    drawCircle(color, r, center)
    // A lighter hub so the discs read as chips, not dots.
    drawCircle(Color.White.copy(alpha = 0.30f), r * 0.45f, center)
    drawCircle(color, r * 0.45f, center, style = androidx.compose.ui.graphics.drawscope.Stroke(width = r * 0.12f))
}
