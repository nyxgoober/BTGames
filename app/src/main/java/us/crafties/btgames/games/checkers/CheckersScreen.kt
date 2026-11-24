package us.crafties.btgames.games.checkers

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutQuad
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import us.crafties.btgames.R
import us.crafties.btgames.ui.BoardDrag
import us.crafties.btgames.ui.Haptics
import us.crafties.btgames.ui.Pal
import us.crafties.btgames.ui.RaisedButton
import us.crafties.btgames.ui.RaisedCard
import us.crafties.btgames.ui.Tone
import us.crafties.btgames.ui.Wording
import us.crafties.btgames.ui.awaitBoardGesture
import us.crafties.btgames.ui.boardCenter
import us.crafties.btgames.ui.drawCheckeredBoard
import us.crafties.btgames.ui.drawTargetDots

/**
 * Pieces are drawn in the colour of whoever owns them: mine in [Pal.paint] of my side,
 * theirs in the opposite. The board and the tap-or-drag gesture are shared with chess
 * (`ui/Board.kt`); only the pieces and the rules are checkers's.
 *
 * Every applied step — tapped, dragged, or the opponent's — then glides to its landing,
 * except a step just drag-dropped by this phone (the piece is already under the finger).
 */
private fun colorFor(piece: Piece, s: CheckersUi): Color =
    Pal.paint(if (piece.side() == s.myMark) s.mySide else s.mySide.other())

@Composable
fun CheckersScreen(
    state: CheckersUi,
    onCell: (Int) -> Unit,
    onDrop: (Int, Int) -> Boolean,
    onRematch: () -> Unit,
    onLeave: () -> Unit,
) {
    val tap by rememberUpdatedState(onCell)
    val drop by rememberUpdatedState(onDrop)
    var drag by remember { mutableStateOf<BoardDrag?>(null) }
    // The step a local drag-drop just played: the glide skips it (already placed by hand).
    var pendingDrop by remember { mutableStateOf<Step?>(null) }
    val ghost = remember { Animatable(1f) }
    var ghostOn by remember { mutableStateOf(false) }
    var ghostStep by remember { mutableStateOf<Step?>(null) }
    var ghostPiece by remember { mutableStateOf(Piece.EMPTY) }

    LaunchedEffect(state.lastStep) {
        if (state.lastStep != null) Haptics.thud()
    }
    LaunchedEffect(state.outcomeForMe) {
        when (state.outcomeForMe) {
            CheckersResult.WIN -> Haptics.win()
            CheckersResult.LOSS -> Haptics.lose()
            null -> Unit
        }
    }
    // One glide per applied step.
    LaunchedEffect(state.stepSeq) {
        val skip = pendingDrop
        pendingDrop = null
        val ls = state.lastStep
        if (ls != null && ls != skip) {
            ghostStep = ls
            ghostPiece = state.board.cells.getOrNull(ls.to) ?: Piece.EMPTY
            ghost.snapTo(0f)
            ghostOn = true
            ghost.animateTo(1f, tween(GHOST_MS, easing = EaseOutQuad))
            ghostOn = false
        } else {
            ghostOn = false
        }
    }

    Column(
        Modifier.fillMaxSize().background(Pal.Paper).safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Scoreboard(state)
        Spacer(Modifier.height(16.dp))
        StatusLine(state)
        Spacer(Modifier.weight(1f))

        RaisedCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .pointerInput(state.board, state.myTurn, state.flipped) {
                        awaitBoardGesture(
                            flipped = state.flipped,
                            liftable = { c ->
                                state.myTurn && state.board.cells[c].side() == state.myMark &&
                                    state.board.stepsFrom(c).isNotEmpty()
                            },
                            onDrag = { drag = it },
                            onTap = { tap(it) },
                            onDrop = { from, to ->
                                pendingDrop = Step(from, to)
                                if (!drop(from, to)) pendingDrop = null
                            },
                        )
                    },
            ) { drawBoard(state, drag, ghostOn, ghostStep, ghostPiece, ghost.value) }
        }

        Spacer(Modifier.weight(1f))
        Footer(state, onRematch, onLeave)
    }
}

@Composable
private fun Scoreboard(s: CheckersUi) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Tally(
            name = s.myNick, wins = s.myWins,
            sub = if (s.myMark == CheckerSide.FIRST) stringResource(R.string.check_starts, s.round + 1)
            else stringResource(R.string.check_you_next),
            color = Pal.paint(s.mySide), align = Alignment.Start,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.check_round), fontSize = 12.sp, color = Pal.Muted)
            Text("${s.round + 1}", fontSize = 26.sp, fontWeight = FontWeight.Black, color = Pal.Ink)
        }
        Tally(
            name = s.theirNick, wins = s.theirWins,
            sub = if (s.myMark != CheckerSide.FIRST) stringResource(R.string.check_starts, s.round + 1)
            else stringResource(R.string.check_they_next),
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
private fun StatusLine(s: CheckersUi) {
    val opponent = stringResource(R.string.common_opponent)
    val (text, color) = when (s.outcomeForMe) {
        CheckersResult.WIN -> stringResource(R.string.check_you_take) to Pal.paint(s.mySide)
        CheckersResult.LOSS -> Wording.subject(
            s.theirNick, R.string.check_they_take_named, R.string.check_they_take_anon,
        ) to Pal.paint(s.mySide.other())
        null -> when {
            s.board.pending >= 0 && s.myTurn -> stringResource(R.string.check_jump) to Pal.paint(s.mySide)
            s.mustJump -> stringResource(R.string.check_must_jump) to Pal.paint(s.mySide)
            s.myTurn -> stringResource(R.string.check_your_move) to Pal.Ink
            else -> stringResource(R.string.check_waiting, s.theirNick.ifBlank { opponent }) to Pal.Ink
        }
    }
    Crossfade(targetState = text to color, animationSpec = tween(220), label = "status") { (t, c) ->
        Text(t, fontSize = 30.sp, fontWeight = FontWeight.Black, color = c)
    }
}

@Composable
private fun Footer(s: CheckersUi, onRematch: () -> Unit, onLeave: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 124.dp), horizontalAlignment = Alignment.Start) {
        if (s.board.isOver) {
            if (s.theyWantRematch && !s.iWantRematch) {
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
                ) else stringResource(R.string.check_play_again),
                onClick = onRematch,
                enabled = !s.iWantRematch,
                tone = Tone.Primary,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Spacer(Modifier.height(54.dp))
        }
        TextButton(onClick = onLeave) { Text(stringResource(R.string.common_leave), color = Pal.Muted, fontWeight = FontWeight.SemiBold) }
    }
}

private fun DrawScope.drawBoard(
    s: CheckersUi,
    drag: BoardDrag?,
    ghostOn: Boolean,
    ghostStep: Step?,
    ghostPiece: Piece,
    ghostT: Float,
) {
    val sq = size.width / 8f
    val selectedCell = drag?.cell ?: s.selected
    val targets = (drag?.let { d -> s.board.stepsFrom(d.cell).map { it.to } } ?: s.targets).toSet()
    val ghostTo = if (ghostOn) ghostStep?.to else null
    val flip = s.flipped
    drawCheckeredBoard(
        tintFrom = s.lastStep?.from ?: -1,
        tintTo = s.lastStep?.to ?: -1,
        selected = selectedCell,
        flipped = flip,
    )
    s.board.cells.forEachIndexed { i, piece ->
        if (piece == Piece.EMPTY) return@forEachIndexed
        if (i == drag?.cell) return@forEachIndexed // rides under the finger instead
        if (i == ghostTo) return@forEachIndexed // the glide draws it instead
        drawPiece(boardCenter(i, flip), sq * 0.38f, colorFor(piece, s), piece.isKing())
    }
    // Legal landings: teal dots on the squares.
    drawTargetDots(targets, flip)
    // The latest step, gliding from origin to landing.
    if (ghostOn && ghostStep != null && ghostPiece != Piece.EMPTY) {
        val a = boardCenter(ghostStep.from, flip)
        val b = boardCenter(ghostStep.to, flip)
        drawPiece(
            Offset(a.x + (b.x - a.x) * ghostT, a.y + (b.y - a.y) * ghostT),
            sq * 0.38f, colorFor(ghostPiece, s), ghostPiece.isKing(),
        )
    }
    // The dragged piece, lifted and following the finger.
    if (drag != null) {
        val piece = s.board.cells[drag.cell]
        drawCircle(Color.Black.copy(alpha = 0.25f), sq * 0.40f, drag.pos + Offset(0f, sq * 0.06f))
        drawPiece(drag.pos, sq * 0.44f, colorFor(piece, s), piece.isKing())
    }
}

/** How long one step glide takes. */
private const val GHOST_MS = 250

private fun DrawScope.drawPiece(center: Offset, r: Float, color: Color, king: Boolean) {
    drawCircle(color, r, center)
    drawCircle(Color.White.copy(alpha = 0.30f), r * 0.45f, center)
    drawCircle(color, r * 0.45f, center, style = Stroke(width = r * 0.12f))
    if (king) {
        // A chalk crown dot: a king reads at a glance, in both modes and both colours.
        drawCircle(Pal.Chalk, r * 0.30f, center)
        drawCircle(color, r * 0.30f, center, style = Stroke(width = r * 0.10f))
    }
}
