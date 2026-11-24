package us.crafties.btgames.games.chess

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
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
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
 * theirs in the opposite. Which colour that is comes from the match (random, and always
 * opposite for the two phones), NOT from "me = red". The board and the tap-or-drag gesture
 * are shared with checkers (`ui/Board.kt`); only the pieces and the rules are chess's.
 *
 * Every applied move — tapped, dragged, or the opponent's — then glides to its landing,
 * except one just drag-dropped by this phone (the piece is already under the finger).
 */
private fun colorFor(piece: ChessPiece, s: ChessUi): Color =
    Pal.paint(if (piece.side == s.myChessSide) s.mySide else s.mySide.other())

/** How long one move glide takes. */
private const val GLIDE_MS = 250

@Composable
fun ChessScreen(
    state: ChessUi,
    onCell: (Int) -> Unit,
    onDrop: (Int, Int) -> Boolean,
    onPromote: (Int) -> Unit,
    onRematch: () -> Unit,
    onLeave: () -> Unit,
) {
    val tap by rememberUpdatedState(onCell)
    val drop by rememberUpdatedState(onDrop)
    var drag by remember { mutableStateOf<BoardDrag?>(null) }
    // The move a local drag-drop just played: its glide is skipped (already placed by hand).
    var pendingDrop by remember { mutableStateOf<ChessMove?>(null) }
    val glide = remember { Animatable(0f) }
    var glideOn by remember { mutableStateOf(false) }
    var glideMove by remember { mutableStateOf<ChessMove?>(null) }
    var glidePiece by remember { mutableStateOf(ChessPiece.EMPTY) }
    var glideRook by remember { mutableStateOf<ChessMove?>(null) }
    var glideRookPiece by remember { mutableStateOf(ChessPiece.EMPTY) }

    // Feel the game: a thud whenever a move lands (yours or theirs), then a pattern for
    // how the round ended. A rematch resets the board, which is fewer moves, not more.
    val ply = state.ply
    var lastPly by remember { mutableIntStateOf(ply) }
    LaunchedEffect(ply) {
        if (ply > lastPly) Haptics.thud()
        lastPly = ply
    }
    LaunchedEffect(state.outcomeForMe) {
        when (state.outcomeForMe) {
            ChessResult.WIN -> Haptics.win()
            ChessResult.LOSS -> Haptics.lose()
            ChessResult.DRAW -> Haptics.click()
            null -> Unit
        }
    }
    // One glide per applied move. Castling takes its rook along; a local drop plays none.
    LaunchedEffect(ply) {
        val skip = pendingDrop
        pendingDrop = null
        val move = state.last
        if (move != null && move != skip) {
            val piece = state.board.cells[move.to]
            glideMove = move
            glidePiece = piece
            glideRook = if (piece != ChessPiece.EMPTY) rookSlide(move, piece) else null
            glideRookPiece = glideRook?.let { state.board.cells[it.to] } ?: ChessPiece.EMPTY
            glide.snapTo(0f)
            glideOn = true
            glide.animateTo(1f, tween(GLIDE_MS, easing = EaseOutQuad))
            glideOn = false
        } else {
            glideOn = false
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
                    .pointerInput(state.board, state.myTurn, state.promotionPending, state.flipped) {
                        awaitBoardGesture(
                            flipped = state.flipped,
                            liftable = { c ->
                                state.myTurn && !state.promotionPending &&
                                    state.board.cells[c].side == state.myChessSide &&
                                    state.board.movesFrom(c).isNotEmpty()
                            },
                            onDrag = { drag = it },
                            onTap = { tap(it) },
                            onDrop = { from, to ->
                                pendingDrop = ChessMove(from, to)
                                if (!drop(from, to)) pendingDrop = null
                            },
                        )
                    },
            ) { drawBoard(state, drag, glideOn, glideMove, glidePiece, glideRook, glideRookPiece, glide.value) }
        }

        Spacer(Modifier.weight(1f))
        Footer(state, onPromote, onRematch, onLeave)
    }
}

/**
 * The rook's half of castling: a king that slid two files takes its rook along with it.
 * Null when [m] is not a castle.
 */
private fun rookSlide(m: ChessMove, king: ChessPiece): ChessMove? {
    if (king != ChessPiece.WK && king != ChessPiece.BK) return null
    if (abs(m.to - m.from) != 2) return null
    val rank = m.from / 8 * 8
    return if (m.to % 8 == 6) ChessMove(rank + 7, rank + 5) else ChessMove(rank, rank + 2)
}

@Composable
private fun Scoreboard(s: ChessUi) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Tally(
            name = s.myNick,
            wins = s.myWins,
            sub = if (s.whiteMe) stringResource(R.string.chess_white) else stringResource(R.string.chess_black),
            color = Pal.paint(s.mySide),
            align = Alignment.Start,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.chess_draws), fontSize = 12.sp, color = Pal.Muted)
            Text("${s.draws}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Pal.Ink)
        }
        Tally(
            name = s.theirNick,
            wins = s.theirWins,
            sub = if (s.whiteMe) stringResource(R.string.chess_black) else stringResource(R.string.chess_white),
            color = Pal.paint(s.mySide.other()),
            align = Alignment.End,
        )
    }
}

@Composable
private fun Tally(name: String, wins: Int, sub: String, color: Color, align: Alignment.Horizontal) {
    Column(horizontalAlignment = align, modifier = Modifier.padding(horizontal = 2.dp)) {
        Text(
            name.ifBlank { stringResource(R.string.common_player) },
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1,
        )
        Text("$wins", fontSize = 40.sp, fontWeight = FontWeight.Black, color = Pal.Ink)
        Text(sub, fontSize = 12.sp, color = Pal.Muted)
    }
}

@Composable
private fun StatusLine(s: ChessUi) {
    val opponent = stringResource(R.string.common_opponent)
    val text = when (s.outcomeForMe) {
        ChessResult.WIN -> stringResource(R.string.common_you_win)
        ChessResult.LOSS -> Wording.subject(s.theirNick, R.string.subj_wins_named, R.string.subj_wins_anon)
        ChessResult.DRAW -> when {
            s.board.stalemate -> stringResource(R.string.chess_stalemate)
            s.board.drawByFiftyMoves -> stringResource(R.string.chess_fifty)
            else -> stringResource(R.string.chess_repetition)
        }
        null -> when {
            s.promotionPending -> stringResource(R.string.chess_promote)
            s.board.inCheck() -> stringResource(R.string.chess_check)
            s.myTurn -> stringResource(R.string.chess_your_move)
            else -> stringResource(R.string.chess_waiting, s.theirNick.ifBlank { opponent })
        }
    }
    val color = when (s.outcomeForMe) {
        ChessResult.WIN -> Pal.paint(s.mySide)
        ChessResult.LOSS -> Pal.paint(s.mySide.other())
        else -> Pal.Ink
    }
    Crossfade(targetState = text to color, animationSpec = tween(220), label = "status") { (t, c) ->
        Text(t, fontSize = 30.sp, fontWeight = FontWeight.Black, color = c)
    }
}

@Composable
private fun Footer(s: ChessUi, onPromote: (Int) -> Unit, onRematch: () -> Unit, onLeave: () -> Unit) {
    // A minimum height, not a fixed one: the button label may wrap to two lines for a long name.
    Column(Modifier.fillMaxWidth().heightIn(min = 124.dp), horizontalAlignment = Alignment.Start) {
        if (s.promotionPending && !s.isOver) {
            Text(stringResource(R.string.chess_promote), fontSize = 15.sp, color = Pal.Ink)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                PromotionButton(R.string.chess_queen, 5, s, onPromote)
                PromotionButton(R.string.chess_rook, 4, s, onPromote)
                PromotionButton(R.string.chess_bishop, 3, s, onPromote)
                PromotionButton(R.string.chess_knight, 2, s, onPromote)
            }
        } else if (s.isOver) {
            if (s.theyWantRematch && !s.iWantRematch) {
                Text(
                    Wording.subject(s.theirNick, R.string.subj_wants_named, R.string.subj_wants_anon),
                    fontSize = 15.sp, color = Pal.Muted, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                )
            } else {
                Spacer(Modifier.height(26.dp))
            }
            RaisedButton(
                text = if (s.iWantRematch) {
                    stringResource(
                        R.string.common_waiting_rematch,
                        s.theirNick.ifBlank { stringResource(R.string.common_opponent) },
                    )
                } else {
                    stringResource(R.string.chess_play_again)
                },
                onClick = onRematch,
                enabled = !s.iWantRematch,
                tone = Tone.Primary,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Spacer(Modifier.height(54.dp))
        }
        TextButton(onClick = onLeave) {
            Text(stringResource(R.string.common_leave), color = Pal.Muted, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** The chooser draws the piece you are about to become, in your colour, over its name. */
@Composable
private fun PromotionButton(labelRes: Int, type: Int, s: ChessUi, onPromote: (Int) -> Unit) {
    val piece = ChessPiece.of(s.myChessSide, type)
    val fill = Pal.paint(s.mySide)
    TextButton(
        onClick = { onPromote(type) },
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(34.dp)) {
                drawChessPiece(piece, fill, Offset(size.width / 2f, size.height / 2f), size.width * 0.80f)
            }
            Text(stringResource(labelRes), fontSize = 13.sp, color = Pal.Ink, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun DrawScope.drawBoard(
    s: ChessUi,
    drag: BoardDrag?,
    glideOn: Boolean,
    glideMove: ChessMove?,
    glidePiece: ChessPiece,
    glideRook: ChessMove?,
    glideRookPiece: ChessPiece,
    glideT: Float,
) {
    val sq = size.width / 8f
    val selectedCell = drag?.cell ?: s.selected
    val targets = if (selectedCell >= 0) {
        s.board.movesFrom(selectedCell).map { it.to }.toSet()
    } else {
        emptySet()
    }
    val glideTo = if (glideOn) glideMove?.to else null
    val rookTo = if (glideOn) glideRook?.to else null
    val flip = s.flipped
    drawCheckeredBoard(
        tintFrom = s.last?.from ?: -1,
        tintTo = s.last?.to ?: -1,
        selected = selectedCell,
        flipped = flip,
    )

    s.board.cells.forEachIndexed { i, piece ->
        if (piece == ChessPiece.EMPTY) return@forEachIndexed
        if (i == drag?.cell) return@forEachIndexed // rides under the finger instead
        if (i == glideTo || i == rookTo) return@forEachIndexed // the glide draws it instead
        drawChessPiece(piece, colorFor(piece, s), boardCenter(i, flip), sq * 0.84f)
    }

    // Legal landings: teal dots on the squares.
    drawTargetDots(targets, flip)

    // The latest move, gliding from origin to landing.
    if (glideOn && glideMove != null && glidePiece != ChessPiece.EMPTY) {
        val a = boardCenter(glideMove.from, flip)
        val b = boardCenter(glideMove.to, flip)
        val at = Offset(a.x + (b.x - a.x) * glideT, a.y + (b.y - a.y) * glideT)
        drawChessPiece(glidePiece, colorFor(glidePiece, s), at, sq * 0.84f)
        if (glideRook != null && glideRookPiece != ChessPiece.EMPTY) {
            val ra = boardCenter(glideRook.from, flip)
            val rb = boardCenter(glideRook.to, flip)
            val rat = Offset(ra.x + (rb.x - ra.x) * glideT, ra.y + (rb.y - ra.y) * glideT)
            drawChessPiece(glideRookPiece, colorFor(glideRookPiece, s), rat, sq * 0.84f)
        }
    }

    // The dragged piece, lifted and following the finger.
    if (drag != null) {
        val piece = s.board.cells[drag.cell]
        if (piece != ChessPiece.EMPTY) {
            drawCircle(Color.Black.copy(alpha = 0.25f), sq * 0.40f, drag.pos + Offset(0f, sq * 0.06f))
            drawChessPiece(piece, colorFor(piece, s), drag.pos, sq * 0.97f)
        }
    }
}
