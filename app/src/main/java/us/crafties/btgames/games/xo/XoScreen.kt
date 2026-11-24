package us.crafties.btgames.games.xo

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import us.crafties.btgames.R
import us.crafties.btgames.ui.Haptics
import us.crafties.btgames.ui.Pal
import us.crafties.btgames.ui.RaisedButton
import us.crafties.btgames.ui.RaisedCard
import us.crafties.btgames.ui.Tone
import us.crafties.btgames.ui.Wording

/**
 * A mark is drawn in the colour of whoever owns it. Which colour that is comes from the match
 * (random, and always opposite for the two phones), NOT from "me = red".
 */
private fun colorFor(mark: Mark, s: XoUi): Color =
    Pal.paint(if (mark == s.myMark) s.mySide else s.mySide.other())

@Composable
fun XoScreen(
    state: XoUi,
    onCell: (Int) -> Unit,
    onRematch: () -> Unit,
    onLeave: () -> Unit,
) {
    val tap by rememberUpdatedState(onCell)

    // Board animation state. Everything that is already on the board when the screen opens (a
    // rejoin, a rotation) is shown finished; only marks that arrive afterwards draw themselves in.
    val anim = remember {
        BoardAnim(
            marks = state.board.cells.map { if (it == Mark.EMPTY) 0f else 1f },
            over = state.board.winningLine != null,
        )
    }
    val seed = remember { state.board.cells.toTypedArray() }
    for (i in 0 until 9) {
        val m = state.board.cells[i]
        LaunchedEffect(i, m) {
            val a = anim.marks[i]
            when {
                m == Mark.EMPTY -> { seed[i] = Mark.EMPTY; a.snapTo(0f) }
                seed[i] == m -> a.snapTo(1f)
                else -> a.animateTo(1f, tween(MARK_MS, easing = LinearEasing))
            }
        }
    }
    // The grid rules draw themselves in at the start of every round.
    LaunchedEffect(state.round) {
        anim.grid.snapTo(0f)
        anim.grid.animateTo(1f, tween(GRID_MS, easing = LinearEasing))
    }
    // A win: let the last mark finish, strike the line through, fade the rest, pulse the winners.
    val winLine = state.board.winningLine
    var winSeeded by remember { mutableStateOf(winLine != null) }
    LaunchedEffect(winLine) {
        if (winLine == null) {
            winSeeded = false
            anim.strike.snapTo(0f); anim.dim.snapTo(0f); anim.pulse.snapTo(1f)
        } else if (winSeeded) {
            anim.strike.snapTo(1f); anim.dim.snapTo(1f)
        } else {
            delay(MARK_MS.toLong())
            launch { anim.dim.animateTo(1f, tween(420)) }
            anim.strike.animateTo(1f, tween(STRIKE_MS, easing = FastOutSlowInEasing))
            anim.pulse.animateTo(1.14f, tween(110))
            anim.pulse.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 400f))
        }
    }
    // Touch feedback: a faint ghost of my mark in the cell under my finger.
    var pressing by remember { mutableStateOf(false) }
    var ghostCell by remember { mutableIntStateOf(-1) }
    val canGhost by rememberUpdatedState(state.myTurn)
    val ghost by animateFloatAsState(if (pressing) 0.28f else 0f, tween(120), label = "ghost")

    // Feel the game: a thump whenever a mark lands (yours or theirs, so you also feel it is your
    // turn), then a pattern for how the round ended. A rematch clears the board, which is fewer
    // marks, not more, so it stays quiet.
    val marks = state.board.moveCount
    var lastMarks by remember { mutableIntStateOf(marks) }
    LaunchedEffect(marks) {
        if (marks > lastMarks) Haptics.thud()
        lastMarks = marks
    }
    LaunchedEffect(state.outcomeForMe) {
        when (state.outcomeForMe) {
            Result.WIN -> Haptics.win()
            Result.LOSS -> Haptics.lose()
            Result.DRAW -> Haptics.click()
            null -> Unit
        }
    }

    Column(
        Modifier.fillMaxSize().background(Pal.Paper).safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Scoreboard(state)
        Spacer(Modifier.height(20.dp))
        StatusLine(state)
        Spacer(Modifier.weight(1f))

        // The board sits on a white card, like the panels elsewhere in the app.
        RaisedCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .pointerInput(state.board.isOver) {
                        fun cellAt(pos: Offset) =
                            (pos.y / (size.height / 3f)).toInt().coerceIn(0, 2) * 3 +
                                (pos.x / (size.width / 3f)).toInt().coerceIn(0, 2)
                        detectTapGestures(
                            onPress = { pos ->
                                if (canGhost) { ghostCell = cellAt(pos); pressing = true }
                                tryAwaitRelease()
                                pressing = false
                            },
                            onTap = { pos -> tap(cellAt(pos)) },
                        )
                    },
            ) { drawBoard(state, anim, ghostCell, ghost) }
        }

        Spacer(Modifier.weight(1f))
        Footer(state, onRematch, onLeave)
    }
}

@Composable
private fun Scoreboard(s: XoUi) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Tally(name = s.myNick, mark = s.myMark, wins = s.myWins, color = Pal.paint(s.mySide), align = Alignment.Start)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.xo_draws), fontSize = 12.sp, color = Pal.Muted)
            Text("${s.draws}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Pal.Ink)
        }
        Tally(name = s.theirNick, mark = s.myMark.other(), wins = s.theirWins, color = Pal.paint(s.mySide.other()), align = Alignment.End)
    }
}

@Composable
private fun Tally(name: String, mark: Mark, wins: Int, color: Color, align: Alignment.Horizontal) {
    Column(horizontalAlignment = align, modifier = Modifier.padding(horizontal = 2.dp)) {
        Text(name.ifBlank { stringResource(R.string.common_player) }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
        // The score pops when it goes up.
        val bump = remember { Animatable(1f) }
        var firstWins by remember { mutableStateOf(true) }
        LaunchedEffect(wins) {
            if (firstWins) { firstWins = false; return@LaunchedEffect }
            bump.snapTo(1.35f)
            bump.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 350f))
        }
        Text(
            "$wins", fontSize = 40.sp, fontWeight = FontWeight.Black, color = Pal.Ink,
            modifier = Modifier.graphicsLayer { scaleX = bump.value; scaleY = bump.value },
        )
        Text(stringResource(R.string.xo_plays, if (mark == Mark.X) "X" else "O"), fontSize = 12.sp, color = Pal.Muted)
    }
}

@Composable
private fun StatusLine(s: XoUi) {
    val opponent = stringResource(R.string.common_opponent)
    val text = when (s.outcomeForMe) {
        Result.WIN -> stringResource(R.string.common_you_win)
        Result.LOSS -> Wording.subject(s.theirNick, R.string.subj_wins_named, R.string.subj_wins_anon)
        Result.DRAW -> stringResource(R.string.common_draw)
        null -> if (s.myTurn) stringResource(R.string.xo_your_move)
        else stringResource(R.string.xo_waiting, s.theirNick.ifBlank { opponent })
    }
    val color = when (s.outcomeForMe) {
        Result.WIN -> Pal.paint(s.mySide)
        Result.LOSS -> Pal.paint(s.mySide.other())
        else -> Pal.Ink
    }
    Crossfade(targetState = text to color, animationSpec = tween(220), label = "status") { (t, c) ->
        Text(t, fontSize = 30.sp, fontWeight = FontWeight.Black, color = c)
    }
}

@Composable
private fun Footer(s: XoUi, onRematch: () -> Unit, onLeave: () -> Unit) {
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
                ) else stringResource(R.string.common_play_again),
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

private const val MARK_MS = 380
private const val GRID_MS = 520
private const val STRIKE_MS = 360

/** Everything that animates on the board, as values the draw phase reads without recomposing. */
@Stable
private class BoardAnim(marks: List<Float>, over: Boolean) {
    val grid = Animatable(0f)
    val marks = marks.map { Animatable(it) }
    val strike = Animatable(if (over) 1f else 0f)
    val dim = Animatable(if (over) 1f else 0f)
    val pulse = Animatable(1f)
}

private fun ease(t: Float) = FastOutSlowInEasing.transform(t.coerceIn(0f, 1f))

private fun DrawScope.drawBoard(s: XoUi, anim: BoardAnim, ghostCell: Int, ghostAlpha: Float) {
    val w = size.width
    val cell = w / 3f
    val grid = Pal.Ink.copy(alpha = 0.85f)
    val line = 5.dp.toPx()

    // Grid: two vertical and two horizontal rules, inset so they don't touch the outer edge.
    // Each rule is drawn from its start to its end, one after the other.
    val g = anim.grid.value
    for (k in 0..3) {
        val p = ease((g - k * 0.12f) / 0.52f)
        if (p <= 0f) continue
        val i = k % 2 + 1
        val a = if (k < 2) Offset(cell * i, cell * 0.08f) else Offset(cell * 0.08f, cell * i)
        val b = if (k < 2) Offset(cell * i, w - cell * 0.08f) else Offset(w - cell * 0.08f, cell * i)
        drawLine(grid, a, lerp(a, b, p), line, StrokeCap.Round)
    }

    val winningLine = s.board.winningLine
    val winning = winningLine.orEmpty().toSet()
    s.board.cells.forEachIndexed { i, m ->
        if (m == Mark.EMPTY) return@forEachIndexed
        val cx = (i % 3 + 0.5f) * cell
        val cy = (i / 3 + 0.5f) * cell
        val isWin = i in winning
        // On a finished game, the marks that were not part of the winning line fade out.
        val alpha = if (winningLine != null && !isWin) 1f - 0.72f * anim.dim.value else 1f
        val color = colorFor(m, s).copy(alpha = alpha)
        drawMark(m, Offset(cx, cy), cell * 0.28f, color, anim.marks[i].value, if (isWin) anim.pulse.value else 1f)
    }

    // A ghost of my mark under the finger, on an empty cell.
    if (ghostAlpha > 0.01f && ghostCell in 0..8 && s.board.cells[ghostCell] == Mark.EMPTY) {
        val c = Offset((ghostCell % 3 + 0.5f) * cell, (ghostCell / 3 + 0.5f) * cell)
        drawMark(s.myMark, c, cell * 0.28f, colorFor(s.myMark, s).copy(alpha = ghostAlpha), 1f, 1f)
    }

    // The strike-through, drawn along the winning line.
    if (winningLine != null && anim.strike.value > 0f) {
        fun centre(i: Int) = Offset((i % 3 + 0.5f) * cell, (i / 3 + 0.5f) * cell)
        val a = centre(winningLine.first())
        val b = centre(winningLine.last())
        val d = b - a
        val u = d / d.getDistance()
        val from = a - u * (cell * 0.36f)
        val to = b + u * (cell * 0.36f)
        drawLine(Pal.Ink.copy(alpha = 0.9f), from, lerp(from, to, anim.strike.value), 8.dp.toPx(), StrokeCap.Round)
    }
}

/**
 * [t] is how much of the mark has been drawn: an X is two strokes one after the other, an O is
 * a circle swept round from the top. [boost] only scales the winners' pulse; it is 1 otherwise.
 */
private fun DrawScope.drawMark(mark: Mark, center: Offset, r: Float, color: Color, t: Float, boost: Float) {
    if (t <= 0f || mark == Mark.EMPTY) return
    val stroke = 10.dp.toPx()
    // No pop or fade-in: the strokes themselves are the animation, like a pen on paper.
    val c = color
    withTransform({ scale(boost, boost, center) }) {
        when (mark) {
            Mark.X -> {
                val p1 = ease(t / 0.55f)
                val p2 = ease((t - 0.45f) / 0.55f)
                val a = Offset(center.x - r, center.y - r)
                val b = Offset(center.x + r, center.y + r)
                val d = Offset(center.x - r, center.y + r)
                val e = Offset(center.x + r, center.y - r)
                if (p1 > 0f) drawLine(c, a, lerp(a, b, p1), stroke, StrokeCap.Round)
                if (p2 > 0f) drawLine(c, d, lerp(d, e, p2), stroke, StrokeCap.Round)
            }
            Mark.O -> {
                val rr = r * 1.05f
                drawArc(
                    c, startAngle = -90f, sweepAngle = 360f * ease(t), useCenter = false,
                    topLeft = Offset(center.x - rr, center.y - rr), size = Size(rr * 2f, rr * 2f),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
            Mark.EMPTY -> Unit
        }
    }
}
