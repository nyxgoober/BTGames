package us.crafties.btgames.games.tetriminoes

import androidx.compose.animation.core.tween
import androidx.compose.animation.Crossfade
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import us.crafties.btgames.R
import us.crafties.btgames.ui.FitText
import us.crafties.btgames.ui.Haptics
import us.crafties.btgames.ui.Pal
import us.crafties.btgames.ui.RaisedButton
import us.crafties.btgames.ui.RaisedCard
import us.crafties.btgames.ui.RaisedSurface
import us.crafties.btgames.ui.Tone
import us.crafties.btgames.ui.Wording

/** Fixed tetromino paints: readable on the dark board in both themes. */
private val PiecePaint = mapOf(
    1 to Color(0xFF22D3EE), // I cyan
    2 to Color(0xFFFACC15), // O yellow
    3 to Color(0xFFA855F7), // T purple
    4 to Color(0xFF22C55E), // S green
    5 to Color(0xFFEF4444), // Z red
    6 to Color(0xFF3B82F6), // J blue
    7 to Color(0xFFF97316), // L orange
)

@Composable
fun TetriminoesScreen(
    state: TetriminoesUi,
    onChoose: (Int) -> Unit,
    onLeft: () -> Boolean,
    onRight: () -> Boolean,
    onRotate: () -> Unit,
    onSoftDrop: () -> Boolean,
    onHardDrop: () -> Boolean,
    onRematch: () -> Unit,
    onLeave: () -> Unit,
) {
    // Feel the game: a tick when a piece lands, a thump for cleared lines, a pattern at the end.
    var lastScore by remember { mutableIntStateOf(0) }
    var lastLines by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.round) { lastScore = 0; lastLines = 0 }
    LaunchedEffect(state.myScore, state.myLines) {
        if (state.myScore == 0 && state.myLines == 0) {
            lastScore = 0; lastLines = 0
        } else if (state.myLines > lastLines) {
            Haptics.thud(); lastLines = state.myLines; lastScore = state.myScore
        } else if (state.myScore > lastScore) {
            Haptics.tick(); lastScore = state.myScore
        }
    }
    LaunchedEffect(state.outcome) {
        when (state.outcome) {
            TetriminoesResult.WIN -> Haptics.win()
            TetriminoesResult.LOSS -> Haptics.lose()
            TetriminoesResult.DRAW -> Haptics.click()
            null -> Unit
        }
    }

    // Drags steer the piece from anywhere on the screen, not just the board: sideways drags
    // walk it a cell per board column crossed, pulling down soft-drops, a fast swipe down slams.
    // One swipe owns exactly one piece: the swipe binds to the falling piece when it starts, and
    // the moment that piece locks the rest of the swipe goes dead. The next piece waits for a
    // fresh finger — a swipe can never drag two pieces, cooldown or not.
    val left by rememberUpdatedState(onLeft)
    val right by rememberUpdatedState(onRight)
    val soft by rememberUpdatedState(onSoftDrop)
    val hard by rememberUpdatedState(onHardDrop)
    val piecesNow by rememberUpdatedState(state.pieces)
    // Board size in px, reported by the board canvas once laid out: the drag math below is in
    // board cells even though the touch can start anywhere.
    var boardW by remember { mutableFloatStateOf(0f) }
    var boardH by remember { mutableFloatStateOf(0f) }
    val steering = state.phase == TetriminoesPhase.PLAYING && !state.myDone
    val scroll = rememberScrollState()

    Column(
        Modifier.fillMaxSize().background(Pal.Paper).safeDrawingPadding()
            // While steering, drags belong to the piece, so the page must not scroll under them.
            .then(if (steering) Modifier else Modifier.verticalScroll(scroll))
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .pointerInput(steering, boardW, boardH) {
                if (!steering || boardW <= 0f || boardH <= 0f) return@pointerInput
                val cellW = boardW / 10f
                val cellH = boardH / 20f
                var totalDx = 0f
                var totalDy = 0f
                var stepX = 0f
                var stepY = 0f
                var t0 = 0L
                // The piece this swipe is allowed to touch. -1 until the drag starts.
                var ownedPiece = -1
                // Set the instant this swipe locks its piece, so not even the next input event
                // in the same frame can touch the fresh piece before the UI catches up.
                var gestureSpent = false
                detectDragGestures(
                    onDragStart = {
                        totalDx = 0f; totalDy = 0f; stepX = 0f; stepY = 0f
                        t0 = SystemClock.uptimeMillis()
                        ownedPiece = piecesNow
                        gestureSpent = false
                    },
                    onDragEnd = {
                        // A spent swipe slams nothing: only the bound piece may be slammed,
                        // and only by the swipe that started on it.
                        if (gestureSpent || piecesNow != ownedPiece) return@detectDragGestures
                        val dt = SystemClock.uptimeMillis() - t0
                        if (totalDy > cellH * 3 && totalDy > 2 * abs(totalDx) && dt < 600) {
                            Haptics.thud()
                            hard() // fast swipe down
                        }
                    },
                    onDragCancel = {},
                    onDrag = { change, dragAmount ->
                        change.consume()
                        if (gestureSpent || piecesNow != ownedPiece) return@detectDragGestures
                        totalDx += dragAmount.x
                        totalDy += dragAmount.y
                        stepX += dragAmount.x
                        while (abs(stepX) >= cellW) {
                            // Tick only when the piece really walks: pushing into a wall
                            // stays quiet.
                            if (stepX > 0) {
                                if (right()) Haptics.tick()
                                stepX -= cellW
                            } else {
                                if (left()) Haptics.tick()
                                stepX += cellW
                            }
                        }
                        // Pulling down walks the piece down for a point a row. The step that
                        // locks it spends the swipe on the spot: the rest of this finger's
                        // travel belongs to nobody.
                        if (totalDy > 0) {
                            stepY += dragAmount.y
                            while (stepY >= cellH) {
                                if (soft()) {
                                    gestureSpent = true
                                    return@detectDragGestures
                                }
                                stepY -= cellH
                            }
                        }
                    },
                )
            },
    ) {
        Scoreboard(state)
        Spacer(Modifier.height(8.dp))
        StatusLine(state)
        Spacer(Modifier.height(10.dp))

        when (state.phase) {
            TetriminoesPhase.SETUP -> SetupBody(state, onChoose)
            TetriminoesPhase.COUNTDOWN -> CountdownBody(state)
            TetriminoesPhase.PLAYING, TetriminoesPhase.OVER -> GameBody(
                state, onLeft, onRight, onRotate, onSoftDrop, onHardDrop,
                onBoardSize = { w, h -> boardW = w; boardH = h },
            )
        }

        Spacer(Modifier.height(10.dp))
        Footer(state, onRematch, onLeave)
    }
}

// ---- header -------------------------------------------------------------------------

@Composable
private fun Scoreboard(s: TetriminoesUi) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Tally(
            name = s.myNick, score = s.myScore, sub = stringResource(R.string.tet_lines, s.myLines),
            color = Pal.paint(s.mySide), align = Alignment.Start,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (s.phase == TetriminoesPhase.PLAYING || s.phase == TetriminoesPhase.OVER) {
                    formatTime(s.remainingSec)
                } else stringResource(R.string.tet_clock_blank),
                fontSize = 26.sp, fontWeight = FontWeight.Black, color = Pal.Ink,
            )
            Text(stringResource(R.string.tet_time_left), fontSize = 12.sp, color = Pal.Muted)
        }
        Tally(
            name = s.theirNick, score = s.theirScore, sub = stringResource(R.string.tet_lines, s.theirLines),
            color = Pal.paint(s.mySide.other()), align = Alignment.End,
        )
    }
}

@Composable
private fun Tally(name: String, score: Int, sub: String, color: Color, align: Alignment.Horizontal) {
    Column(horizontalAlignment = align, modifier = Modifier.padding(horizontal = 2.dp)) {
        Text(
            name.ifBlank { stringResource(R.string.common_player) }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            color = color, maxLines = 1,
        )
        Text("$score", fontSize = 34.sp, fontWeight = FontWeight.Black, color = Pal.Ink)
        Text(sub, fontSize = 12.sp, color = Pal.Muted)
    }
}

@Composable
private fun StatusLine(s: TetriminoesUi) {
    val themLow = stringResource(R.string.common_them_low)
    val opponent = stringResource(R.string.common_opponent)
    val (text, color) = when (s.phase) {
        TetriminoesPhase.SETUP -> if (s.iChoose) stringResource(R.string.tet_pick_time_you) to Pal.Ink
        else stringResource(R.string.tet_pick_time_wait, s.theirNick.ifBlank { opponent }) to Pal.Ink
        TetriminoesPhase.COUNTDOWN -> stringResource(R.string.tet_ready) to Pal.Ink
        TetriminoesPhase.PLAYING -> when {
            s.myDone && !s.theyDone -> stringResource(R.string.tet_locked, s.theirNick.ifBlank { themLow }) to Pal.Muted
            s.myDone -> stringResource(R.string.tet_time) to Pal.Ink
            else -> stringResource(R.string.tet_level_status, s.level) to Pal.Ink
        }
        TetriminoesPhase.OVER -> when (s.outcome) {
            TetriminoesResult.WIN -> stringResource(R.string.common_you_win) to Pal.paint(s.mySide)
            TetriminoesResult.LOSS -> Wording.subject(s.theirNick, R.string.subj_wins_named, R.string.subj_wins_anon) to Pal.paint(s.mySide.other())
            TetriminoesResult.DRAW -> stringResource(R.string.common_draw) to Pal.Ink
            null -> "" to Pal.Ink
        }
    }
    Crossfade(targetState = text to color, animationSpec = tween(220), label = "status") { (t, c) ->
        Text(t, fontSize = 22.sp, fontWeight = FontWeight.Black, color = c)
    }
}

// ---- setup + countdown ----------------------------------------------------------------

@Composable
private fun SetupBody(s: TetriminoesUi, onChoose: (Int) -> Unit) {
    if (s.iChoose) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.tet_how_long), fontSize = 15.sp, color = Pal.Muted)
            TetriminoesCodec.DURATIONS.forEach { sec ->
                RaisedButton(
                    text = if (sec < 60) stringResource(R.string.tet_secs, sec)
                    else stringResource(R.string.tet_mins, sec / 60),
                    onClick = { onChoose(sec) },
                    tone = Tone.Primary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    } else {
        RaisedCard(Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.tet_choosing, s.theirNick.ifBlank { stringResource(R.string.common_opponent_cap) }),
                fontSize = 16.sp, color = Pal.Muted,
            )
        }
    }
}

@Composable
private fun CountdownBody(s: TetriminoesUi) {
    RaisedCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(28.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (s.countdownLeft <= 0) stringResource(R.string.tet_go) else "${s.countdownLeft}",
                fontSize = 72.sp, fontWeight = FontWeight.Black, color = Pal.Teal,
            )
            Text(
                stringResource(R.string.tet_first_to, s.durationSec / 60),
                fontSize = 14.sp, color = Pal.Muted,
            )
        }
    }
}

// ---- the match ------------------------------------------------------------------------

@Composable
private fun GameBody(
    s: TetriminoesUi,
    onLeft: () -> Boolean,
    onRight: () -> Boolean,
    onRotate: () -> Unit,
    onSoftDrop: () -> Boolean,
    onHardDrop: () -> Boolean,
    onBoardSize: (Float, Float) -> Unit,
) {
    val rotate by rememberUpdatedState(onRotate)
    val hard by rememberUpdatedState(onHardDrop)
    val sizeReport by rememberUpdatedState(onBoardSize)
    // A slam is felt, not just seen.
    fun slam() {
        Haptics.thud()
        hard()
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // Tapping the board spins. Steering drags are handled one level up so they work from
        // anywhere on the screen; keeping the tap here means taps on the buttons below never
        // spin by accident.
        RaisedCard(Modifier.weight(1.5f), contentPadding = PaddingValues(8.dp)) {
            Canvas(
                Modifier.fillMaxWidth().aspectRatio(10f / 20f)
                    .onSizeChanged { sizeReport(it.width.toFloat(), it.height.toFloat()) }
                    .pointerInput(s.phase, s.myDone) {
                        if (s.phase != TetriminoesPhase.PLAYING || s.myDone) return@pointerInput
                        detectTapGestures(onTap = { rotate() })
                    },
            ) {
                drawBoard(s)
            }
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RaisedCard(contentPadding = PaddingValues(10.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.tet_next), fontSize = 12.sp, color = Pal.Muted)
                    Canvas(Modifier.fillMaxWidth().aspectRatio(1.4f)) {
                        drawNext(s.next)
                    }
                }
            }
            RaisedCard(contentPadding = PaddingValues(10.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.tet_level), fontSize = 12.sp, color = Pal.Muted)
                    Text("${s.level}", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Pal.Ink)
                }
            }
            if (s.myDone && s.phase == TetriminoesPhase.PLAYING) {
                Text(stringResource(R.string.tet_locked_badge), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Pal.Muted)
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    // Buttons as well, so the moves are findable without the hint. Compact on purpose: four
    // full-size buttons do not fit a narrow phone without their labels overflowing.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PadButton(stringResource(R.string.tet_left), onClick = { onLeft() }, modifier = Modifier.weight(1f))
        PadButton(stringResource(R.string.tet_spin), onClick = onRotate, modifier = Modifier.weight(1f))
        PadButton(stringResource(R.string.tet_right), onClick = { onRight() }, modifier = Modifier.weight(1f))
        PadButton(stringResource(R.string.tet_slam), onClick = ::slam, modifier = Modifier.weight(1f), tone = Tone.Primary)
    }
}

/**
 * A small control pad button: tighter padding and type that starts smaller than [RaisedButton],
 * so four of them fit side by side on a narrow phone with room to spare.
 */
@Composable
private fun PadButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Light,
) {
    RaisedSurface(
        onClick = onClick,
        modifier = modifier,
        tone = tone,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 12.dp),
    ) {
        FitText(
            text, maxSize = 15.sp, minSize = 11.sp, maxLines = 1,
            weight = FontWeight.Bold, color = LocalContentColor.current,
        )
    }
}

private fun formatTime(totalSec: Int): String {
    val m = (totalSec / 60).coerceAtLeast(0)
    val s = (totalSec % 60).coerceAtLeast(0)
    return "$m:%02d".format(s)
}

// ---- footer ---------------------------------------------------------------------------

@Composable
private fun Footer(s: TetriminoesUi, onRematch: () -> Unit, onLeave: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        if (s.phase == TetriminoesPhase.OVER) {
            Text(
                stringResource(
                    R.string.tet_match,
                    s.myWins + s.theirWins + s.draws, s.myWins,
                    s.theirNick.ifBlank { stringResource(R.string.common_them_low) },
                    s.theirWins, s.draws,
                ),
                fontSize = 14.sp, color = Pal.Muted,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
            )
            if (s.theyWantRematch && !s.iWantRematch) {
                Text(
                    Wording.subject(s.theirNick, R.string.subj_wants_named, R.string.subj_wants_anon),
                    fontSize = 15.sp, color = Pal.Muted,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                )
            }
            RaisedButton(
                text = if (s.iWantRematch) {
                    stringResource(
                        R.string.common_waiting_rematch,
                        s.theirNick.ifBlank { stringResource(R.string.common_opponent) },
                    )
                } else stringResource(R.string.common_play_again),
                onClick = onRematch,
                enabled = !s.iWantRematch,
                tone = Tone.Primary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        TextButton(onClick = onLeave) {
            Text(stringResource(R.string.common_leave), color = Pal.Muted, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ---- drawing ----------------------------------------------------------------------------

private fun DrawScope.drawBoard(s: TetriminoesUi) {
    val w = size.width
    val h = size.height
    val cw = w / 10f
    val ch = h / 20f

    drawRect(Pal.Court, size = size)
    // Faint grid.
    val gridLine = Color.White.copy(alpha = 0.06f)
    for (i in 1..9) drawLine(gridLine, Offset(cw * i, 0f), Offset(cw * i, h), 1f)
    for (j in 1..19) drawLine(gridLine, Offset(0f, ch * j), Offset(w, ch * j), 1f)

    // Ghost: where the piece would land.
    for ((x, y) in s.ghost) {
        if (y < 0) continue
        drawCell(x, y, cw, ch, PiecePaint[s.fallingKind.code] ?: Color.White, ghost = true)
    }
    // Locked cells.
    s.grid.forEachIndexed { y, row ->
        row.forEachIndexed { x, code ->
            if (code != 0) drawCell(x, y, cw, ch, PiecePaint[code] ?: Color.White, ghost = false)
        }
    }
    // The falling piece on top.
    for ((x, y) in s.falling) {
        if (y < 0 || x !in 0..9 || y !in 0..19) continue
        drawCell(x, y, cw, ch, PiecePaint[s.fallingKind.code] ?: Color.White, ghost = false)
    }
}

private fun DrawScope.drawCell(x: Int, y: Int, cw: Float, ch: Float, color: Color, ghost: Boolean) {
    val pad = minOf(cw, ch) * 0.08f
    val topLeft = Offset(x * cw + pad, y * ch + pad)
    val cellSize = Size(cw - pad * 2, ch - pad * 2)
    val radius = CornerRadius(pad * 1.6f, pad * 1.6f)
    if (ghost) {
        drawRoundRect(color.copy(alpha = 0.25f), topLeft, cellSize, radius)
    } else {
        drawRoundRect(color, topLeft, cellSize, radius)
        // A lighter top edge so the stack reads as blocks, not soup.
        drawRoundRect(
            Color.White.copy(alpha = 0.28f), topLeft,
            Size(cellSize.width, cellSize.height * 0.32f), radius,
        )
    }
}

private fun DrawScope.drawNext(kind: Kind) {
    val cells = kind.spawn
    val maxX = cells.maxOf { it.first }
    val maxY = cells.maxOf { it.second }
    val cw = size.width / (maxX + 1.6f)
    val ch = size.height / (maxY + 1.6f)
    val cell = minOf(cw, ch)
    val ox = (size.width - cell * (maxX + 1)) / 2f
    val oy = (size.height - cell * (maxY + 1)) / 2f
    val color = PiecePaint[kind.code] ?: Color.White
    for ((x, y) in cells) {
        drawRoundRect(
            color, Offset(ox + x * cell + 1f, oy + y * cell + 1f),
            Size(cell - 2f, cell - 2f), CornerRadius(3f, 3f),
        )
    }
}
