package us.crafties.btgames.games.paddle

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import us.crafties.btgames.R
import us.crafties.btgames.net.Side
import us.crafties.btgames.ui.Haptics
import us.crafties.btgames.ui.Pal
import us.crafties.btgames.ui.RaisedButton
import us.crafties.btgames.ui.Tone
import us.crafties.btgames.ui.Wording

/**
 * The court is landscape: paddles sit at the LEFT and RIGHT edges and the phone is held
 * sideways. Menus are portrait; the caller wraps this screen in [OrientationEffect] to
 * switch to sensor-landscape only while a game is on.
 */
@Composable
fun PaddleCourtScreen(
    frame: Frame,
    myNick: String,
    theirNick: String,
    isHost: Boolean,
    mySide: Side,
    onPaddle: (Float) -> Unit,
    onRematch: () -> Unit,
    onLeave: () -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val paddleCallback by rememberUpdatedState(onPaddle)
    val mine = Pal.paint(mySide)
    val theirs = Pal.paint(mySide.other())

    // A goal is felt by both players: a thump when a point is scored, and the win / lose pattern
    // when the match ends. A new match resets the scores to 0, which is fewer points, so it is quiet.
    val total = frame.myScore + frame.theirScore
    var lastTotal by remember { mutableIntStateOf(total) }
    LaunchedEffect(total) {
        if (total > lastTotal && frame.phase != Phase.GAME_OVER) Haptics.thud()
        lastTotal = total
    }
    LaunchedEffect(frame.phase) {
        if (frame.phase == Phase.GAME_OVER) {
            if (frame.myScore > frame.theirScore) Haptics.win() else Haptics.lose()
        }
    }

    Box(Modifier.fillMaxSize().background(Pal.Court)) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { o -> paddleCallback((o.y / size.height).coerceIn(0f, 1f)) },
                    onDrag = { change, _ ->
                        change.consume()
                        paddleCallback((change.position.y / size.height).coerceIn(0f, 1f))
                    },
                )
            }
        ) {
            drawCourt(frame, measurer, mine, theirs)
        }

        // Names sit above their own half so it is obvious whose paddle is whose.
        Row(
            Modifier.fillMaxWidth().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            NameTag(myNick, mine)
            NameTag(theirNick, theirs)
        }

        if (frame.phase == Phase.SERVING && frame.myScore == 0 && frame.theirScore == 0) {
            Column(
                Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (isHost) stringResource(R.string.paddle_serve_first)
                    else stringResource(
                        R.string.paddle_serve_theirs,
                        theirNick.ifBlank { stringResource(R.string.common_opponent) },
                    ),
                    color = Pal.Chalk.copy(alpha = 0.7f), fontSize = 14.sp,
                )
            }
        }

        if (frame.phase == Phase.GAME_OVER) {
            val won = frame.myScore > frame.theirScore
            Column(
                Modifier.fillMaxSize().background(Pal.Court.copy(alpha = 0.86f)).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (won) stringResource(R.string.common_you_win)
                    else Wording.subject(theirNick, R.string.subj_wins_named, R.string.subj_wins_anon),
                    color = if (won) mine else theirs,
                    fontSize = 44.sp, fontWeight = FontWeight.Black,
                )
                Text(stringResource(R.string.paddle_score, frame.myScore, frame.theirScore), color = Pal.Chalk, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(24.dp))
                if (isHost) {
                    RaisedButton(stringResource(R.string.common_play_again), onClick = onRematch, tone = Tone.Primary)
                } else {
                    Text(
                        stringResource(
                            R.string.paddle_wait_rematch,
                            theirNick.ifBlank { stringResource(R.string.common_opponent) },
                        ),
                        color = Pal.Chalk.copy(alpha = 0.65f), fontSize = 14.sp,
                    )
                }
                TextButton(onClick = onLeave) { Text(stringResource(R.string.common_leave), color = Pal.Chalk) }
            }
        } else {
            TextButton(onClick = onLeave, modifier = Modifier.align(Alignment.BottomEnd).safeDrawingPadding()) {
                Text(stringResource(R.string.common_leave_short), color = Pal.Chalk.copy(alpha = 0.45f), fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun NameTag(name: String, color: Color) {
    Text(
        name.ifBlank { stringResource(R.string.common_player) },
        color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1,
    )
}

private fun DrawScope.drawCourt(f: Frame, measurer: TextMeasurer, mine: Color, theirs: Color) {
    val w = size.width
    val h = size.height

    // Scores: huge, faint, centred in each half, drawn BEFORE the play so the ball passes over them.
    val scoreStyle = { c: Color -> TextStyle(color = c.copy(alpha = 0.22f), fontSize = (h * 0.55f).toSp(), fontWeight = FontWeight.Black) }
    val myText = measurer.measure(f.myScore.toString(), scoreStyle(mine))
    val theirText = measurer.measure(f.theirScore.toString(), scoreStyle(theirs))
    drawText(myText, topLeft = Offset(w * 0.25f - myText.size.width / 2f, h / 2f - myText.size.height / 2f))
    drawText(theirText, topLeft = Offset(w * 0.75f - theirText.size.width / 2f, h / 2f - theirText.size.height / 2f))

    // Centre line
    drawLine(
        color = Pal.Chalk.copy(alpha = 0.28f),
        start = Offset(w / 2f, 0f), end = Offset(w / 2f, h),
        strokeWidth = 3.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(14.dp.toPx(), 14.dp.toPx())),
    )

    // Paddles. Positions are fractions of the field; the faces sit PADDLE_INSET from each edge.
    val padW = 10.dp.toPx()
    val padH = h * PaddleEngine.PADDLE_H
    val radius = CornerRadius(padW / 2f)
    drawRoundRect(
        mine,
        topLeft = Offset(w * PaddleEngine.PADDLE_INSET - padW, f.myPaddleY * h - padH / 2f),
        size = Size(padW, padH), cornerRadius = radius,
    )
    drawRoundRect(
        theirs,
        topLeft = Offset(w * (1f - PaddleEngine.PADDLE_INSET), f.theirPaddleY * h - padH / 2f),
        size = Size(padW, padH), cornerRadius = radius,
    )

    // Ball. Round in pixels regardless of screen aspect ratio (logical field is stretched).
    if (f.phase != Phase.GAME_OVER) {
        val r = 8.dp.toPx()
        drawCircle(Pal.Chalk, radius = r, center = Offset(f.ballX * w, f.ballY * h))
    }

    // Court outline
    drawRect(Pal.Chalk.copy(alpha = 0.12f), style = Stroke(width = 2.dp.toPx()))
}
