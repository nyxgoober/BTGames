package us.crafties.btgames.games.last

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import us.crafties.btgames.R
import us.crafties.btgames.ui.AppScreen
import us.crafties.btgames.ui.Pal
import us.crafties.btgames.ui.RaisedButton
import us.crafties.btgames.ui.Tone
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The table screen: a tablet or TV standing in the middle of the table. It is the Gamemaster and
 * the referee but holds no seat, so it never sees a hand as anything but a number: everybody's
 * cards are drawn face down around a felt table, with the draw pile and the played pile in the
 * middle. Players play on their own phones; nothing here needs a tap except the rules before a
 * deal and "Play again" afterwards.
 */
@Composable
fun LastTableScreen(session: LastSession, onLeave: () -> Unit) {
    val ui by session.ui.collectAsState()
    KeepScreenOn()

    AppScreen(title = stringResource(R.string.g_last_title), onBack = onLeave) {
        Box(Modifier.fillMaxSize()) {
            when {
                // Rules are picked on the screen before every deal (and after "Play again").
                ui.rulesOpen -> Column(
                    Modifier.fillMaxSize().background(Pal.Paper).verticalScroll(rememberScrollState()).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.last_rules_title),
                        fontSize = 26.sp, fontWeight = FontWeight.Black, color = Pal.Ink,
                    )
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.widthIn(max = 520.dp)) {
                        RulesPanel(ui.rules, onChange = { session.setRules(it) }, onDeal = { session.startDeal() })
                    }
                    Spacer(Modifier.height(12.dp))
                    RaisedButton(stringResource(R.string.last_leave), onClick = onLeave, modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth())
                }

                !ui.ready -> Box(Modifier.fillMaxSize().background(Pal.FeltDark), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.last_waiting),
                        fontSize = 28.sp, fontWeight = FontWeight.Black, color = Pal.Chalk,
                    )
                }

                else -> Table(ui, session, onLeave)
            }
        }
    }
}

@Composable
private fun Table(ui: LastUi, session: LastSession, onLeave: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Pal.FeltDark)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // Whose turn it is, big enough to read from across the room.
            Text(
                if (ui.over) stringResource(R.string.last_winner, nameOf(ui, ui.winner))
                else stringResource(R.string.last_turn_of, nameOf(ui, ui.turn)),
                fontSize = 26.sp, fontWeight = FontWeight.Black, color = Pal.Chalk, maxLines = 1,
            )

            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                // Everything scales with the screen: the layout is tuned for about 900 x 480 dp.
                val s = minOf(maxWidth.value / 900f, maxHeight.value / 440f).coerceIn(0.5f, 1.6f)
                val rx = maxWidth * 0.40f
                val ry = maxHeight * 0.36f
                val n = ui.nicks.size.coerceAtLeast(1)

                // The felt: an oval with a wooden rim.
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width * 0.86f
                    val h = size.height * 0.80f
                    val tl = Offset((size.width - w) / 2f, (size.height - h) / 2f)
                    drawOval(Pal.FeltRim, tl, Size(w, h))
                    val inset = 10.dp.toPx()
                    drawOval(
                        Brush.radialGradient(listOf(Pal.Felt, Pal.FeltDark), center = center, radius = w / 2f),
                        Offset(tl.x + inset, tl.y + inset), Size(w - 2 * inset, h - 2 * inset),
                    )
                    drawOval(
                        Pal.FeltDark, Offset(tl.x + inset, tl.y + inset), Size(w - 2 * inset, h - 2 * inset),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }

                // The middle of the table: draw pile, played pile, colour in play and direction.
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(horizontalArrangement = Arrangement.spacedBy((24 * s).dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            LastDrawPile(ui.drawLeft, cardWidth = (56 * s).dp, canDraw = false, onDraw = null, onPositioned = {})
                            Text(stringResource(R.string.last_draw_n, ui.drawLeft), fontSize = (14 * s).sp, color = Pal.Chalk)
                        }
                        LastPlayPile(ui, cardWidth = (96 * s).dp, onTopPositioned = {})
                    }
                    Spacer(Modifier.height((6 * s).dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size((24 * s).dp).clip(CircleShape).background(lastPaint(ui.colour))
                                .border(2.dp, Pal.Chalk, CircleShape),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(if (ui.direction > 0) "↻" else "↺", fontSize = (26 * s).sp, color = Pal.Chalk)
                    }
                }

                // Everybody around the table, seat 0 at the bottom, then clockwise: the same way a
                // card travels when play goes up (the ↻ arrow).
                ui.nicks.forEachIndexed { seat, nick ->
                    val a = PI / 2.0 + seat * 2.0 * PI / n
                    val seatW = (170 * s).dp
                    val seatH = (116 * s).dp
                    TableSeat(
                        nick = nick,
                        count = ui.counts.getOrNull(seat) ?: 0,
                        active = !ui.over && ui.turn == seat,
                        catchable = !ui.over && seat in ui.catchable,
                        winner = ui.over && ui.winner == seat,
                        s = s,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset(x = rx * cos(a).toFloat(), y = ry * sin(a).toFloat())
                            .size(seatW, seatH),
                    )
                }

                if (ui.slapOpen && !ui.over) SlapBanner(s, Modifier.align(Alignment.TopCenter))
            }

            val line = eventLine(ui)
            Text(
                line.ifEmpty { " " }, fontSize = 20.sp, color = Pal.Chalk, textAlign = TextAlign.Center, maxLines = 1,
            )
        }

        if (ui.over) {
            Box(Modifier.fillMaxSize().background(Pal.Court.copy(alpha = 0.78f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 420.dp).padding(24.dp)) {
                    Text(
                        stringResource(R.string.last_winner, nameOf(ui, ui.winner)),
                        fontSize = 40.sp, fontWeight = FontWeight.Black, color = Pal.Chalk, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(20.dp))
                    RaisedButton(
                        stringResource(R.string.last_again), onClick = { session.playAgain() },
                        tone = Tone.Primary, modifier = Modifier.fillMaxWidth().height(60.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    RaisedButton(stringResource(R.string.last_leave), onClick = onLeave, modifier = Modifier.fillMaxWidth().height(60.dp))
                }
            }
        }
    }
}

/** One player's place at the table: their cards face down, their name, and how many are left. */
@Composable
private fun TableSeat(
    nick: String,
    count: Int,
    active: Boolean,
    catchable: Boolean,
    winner: Boolean,
    s: Float,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape((16 * s).dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .clip(shape)
            .background(Pal.Court.copy(alpha = 0.55f))
            .border(
                if (active || catchable || winner) 4.dp else 1.dp,
                if (catchable) Pal.Red else if (active || winner) Pal.Teal else Pal.Chalk.copy(alpha = 0.25f),
                shape,
            )
            .padding(horizontal = (8 * s).dp, vertical = (6 * s).dp),
    ) {
        if (count > 0) TableHand(count, cardWidth = (34 * s).dp)
        Spacer(Modifier.height((4 * s).dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                nick, fontSize = (18 * s).sp, fontWeight = if (active) FontWeight.Black else FontWeight.Bold,
                color = Pal.Chalk, maxLines = 1,
            )
            // One card left and no Last! said: the others can catch them on their phones.
            if (catchable) {
                Box(Modifier.size((24 * s).dp).clip(CircleShape).background(Pal.Red), contentAlignment = Alignment.Center) {
                    Text("!", fontSize = (16 * s).sp, fontWeight = FontWeight.Black, color = Pal.Chalk)
                }
            }
        }
        if (count == 1) {
            Text(stringResource(R.string.last_card_one), fontSize = (15 * s).sp, fontWeight = FontWeight.Black, color = Pal.Red)
        } else {
            Text(stringResource(R.string.last_cards_n, count), fontSize = (15 * s).sp, color = Pal.Chalk.copy(alpha = 0.8f))
        }
    }
}

/** A hand seen from across the table: a fan of up to eight backs; the number under it says the rest. */
@Composable
private fun TableHand(count: Int, cardWidth: Dp) {
    val shown = count.coerceAtMost(MAX_SHOWN)
    val step = cardWidth * 0.30f
    Box(
        Modifier
            .width(cardWidth + step * (shown - 1).toFloat())
            .height(cardWidth * 1.45f + 6.dp),
    ) {
        for (k in 0 until shown) {
            LastCardBack(
                width = cardWidth,
                modifier = Modifier
                    .offset(x = step * k.toFloat(), y = (k * 0.5f).dp)
                    .graphicsLayer { rotationZ = (k - (shown - 1) / 2f) * 3f },
            )
        }
    }
}

/** "SLAP!" while a slap round is open: the table's cue to hit the button on your phone. */
@Composable
private fun SlapBanner(s: Float, modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "slap").animateFloat(
        initialValue = 0.55f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(350), RepeatMode.Reverse), label = "slapPulse",
    )
    Box(
        modifier
            .alpha(pulse)
            .clip(RoundedCornerShape(20.dp))
            .background(Pal.Red)
            .padding(horizontal = (28 * s).dp, vertical = (8 * s).dp),
    ) {
        Text(stringResource(R.string.last_table_slap), fontSize = (34 * s).sp, fontWeight = FontWeight.Black, color = Pal.Chalk)
    }
}

/** The table stays lit for as long as it is on screen. */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

private const val MAX_SHOWN = 8
