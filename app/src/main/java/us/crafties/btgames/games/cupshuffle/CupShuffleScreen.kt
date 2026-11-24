package us.crafties.btgames.games.cupshuffle

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import us.crafties.btgames.R
import us.crafties.btgames.ui.Haptics
import us.crafties.btgames.ui.Pal
import us.crafties.btgames.ui.RaisedButton
import us.crafties.btgames.ui.RaisedCard
import us.crafties.btgames.ui.Tone
import us.crafties.btgames.ui.Wording
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Cups are shaded tumblers on the page; the ball rides in the shuffler's colour. X-ray vision is
 * just an outline: while shuffling, the cup hiding the ball glows in the shuffler's colour
 * (ESP style) on their phone only. The guesser sees identical cups until the reveal, when the
 * ball's cup lifts to show it.
 */
@Composable
fun CupShuffleScreen(
    state: CupUi,
    onSlot: (Int) -> Unit,
    onCups: (Int) -> Unit,
    onRematch: () -> Unit,
    onLeave: () -> Unit,
) {
    val tap by rememberUpdatedState(onSlot)

    // Every cup glides to its slot instead of snapping: a swap reads as motion the eye can
    // follow. Positions reset (snap) each round; the 220 ms glide fits inside the 250 ms
    // tap spacing, so swaps never pile up mid-flight.
    val slotAnims = remember(state.round, state.cups) {
        mutableMapOf<Int, Animatable<Float, androidx.compose.animation.core.AnimationVector1D>>()
    }
    LaunchedEffect(state.order) {
        state.order.forEachIndexed { idx, id ->
            val a = slotAnims.getOrPut(id) { Animatable(idx.toFloat()) }
            if (a.value != idx.toFloat()) launch { a.animateTo(idx.toFloat(), tween(SWAP_ANIM_MS)) }
        }
    }
    // Before the ball is hidden there is no order yet: the shuffler picks from cupsChoice
    // identical cups. (Without this the shuffler would stare at "No cups yet" forever with
    // nothing to tap — the setup dead-end.)
    val shown = when {
        state.cups > 0 -> state.cups
        state.phase == CupPhase.SETUP && state.iShuffle -> state.cupsChoice
        else -> 0
    }
    val displayOrder = if (state.order.isNotEmpty()) state.order else List(shown) { it }

    // Reading a position subscribes: each animation frame redraws the cups.
    // (Falls back to the slot itself, which is what the pre-hide setup cups use.)
    val positions: Map<Int, Float> = displayOrder.associateWith { id ->
        slotAnims[id]?.value ?: displayOrder.indexOf(id).toFloat()
    }

    LaunchedEffect(state.swaps) {
        if (state.phase == CupPhase.SHUFFLING && state.iShuffle && state.swaps > 0) Haptics.tick()
    }
    LaunchedEffect(state.myGuess) {
        if (state.myGuess >= 0) Haptics.thud()
    }
    LaunchedEffect(state.guessCorrect) {
        if (state.phase != CupPhase.REVEAL) return@LaunchedEffect
        val iWon = if (state.iShuffle) state.guessCorrect == false else state.guessCorrect == true
        if (iWon) Haptics.win() else Haptics.lose()
    }

    Column(
        Modifier.fillMaxSize().background(Pal.Paper).safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Scoreboard(state)
        Spacer(Modifier.height(16.dp))
        StatusLine(state)
        Spacer(Modifier.weight(1f))

        RaisedCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
            Column {
                if (shown > 0) {
                    Canvas(
                        Modifier.fillMaxWidth().height((64 * shown + 96).dp.coerceAtMost(420.dp))
                            .pointerInput(shown, state.phase) {
                                detectTapGestures { pos ->
                                    val slot = (pos.x / (size.width / shown)).toInt()
                                        .coerceIn(0, shown - 1)
                                    tap(slot)
                                }
                            },
                    ) { drawCups(state, displayOrder, positions, shown) }
                } else {
                    Text(
                        stringResource(R.string.cup_empty),
                        fontSize = 15.sp, color = Pal.Muted,
                        modifier = Modifier.padding(vertical = 32.dp).fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
                if (state.phase == CupPhase.SHUFFLING && state.iShuffle) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { state.windowLeftMs / CupShuffleSession.SHUFFLE_WINDOW_MS.toFloat() },
                        modifier = Modifier.fillMaxWidth().height(10.dp),
                        color = Pal.Teal,
                        trackColor = Pal.EdgeShade,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.cup_progress, state.swaps, (state.windowLeftMs + 999) / 1000),
                        fontSize = 14.sp, color = Pal.Muted,
                    )
                }
                if (state.phase == CupPhase.SHUFFLING && !state.iShuffle) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.cup_watch, state.swaps),
                        fontSize = 14.sp, color = Pal.Muted,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Controls(state, onCups, tap)

        Spacer(Modifier.weight(1f))
        Footer(state, onRematch, onLeave)
    }
}

@Composable
private fun Controls(state: CupUi, onCups: (Int) -> Unit, onTap: (Int) -> Unit) {
    when {
        state.phase == CupPhase.SETUP && state.iShuffle -> {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RaisedButton(text = "−", onClick = { onCups(state.cupsChoice - 1) }, modifier = Modifier.width(72.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.cup_count, state.cupsChoice), fontSize = 22.sp, fontWeight = FontWeight.Black, color = Pal.Ink,
                    modifier = Modifier.width(110.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.width(12.dp))
                RaisedButton(text = "+", onClick = { onCups(state.cupsChoice + 1) }, modifier = Modifier.width(72.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.cup_tap_hide),
                fontSize = 14.sp, color = Pal.Muted, modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        state.phase == CupPhase.SHUFFLING && state.iShuffle -> {
            RaisedButton(
                text = stringResource(R.string.cup_tap_shuffle),
                onClick = { onTap(0) },
                tone = Tone.Primary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        else -> Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Scoreboard(s: CupUi) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Tally(
            name = s.myNick, wins = s.myWins,
            sub = stringResource(
                if (s.iShuffle) R.string.cup_shuffling else R.string.cup_guessing, s.round + 1,
            ),
            color = Pal.paint(s.mySide), align = Alignment.Start,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.cup_round), fontSize = 12.sp, color = Pal.Muted)
            Text("${s.round + 1}", fontSize = 26.sp, fontWeight = FontWeight.Black, color = Pal.Ink)
        }
        Tally(
            name = s.theirNick, wins = s.theirWins,
            sub = stringResource(
                if (!s.iShuffle) R.string.cup_shuffling else R.string.cup_guessing, s.round + 1,
            ),
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
private fun StatusLine(s: CupUi) {
    val (text, color) = when (s.phase) {
        CupPhase.SETUP -> if (s.iShuffle) {
            stringResource(R.string.cup_hide) to Pal.paint(s.mySide)
        } else {
            Wording.subject(
                s.theirNick, R.string.cup_hiding_named, R.string.cup_hiding_anon,
            ) to Pal.Ink
        }
        CupPhase.SHOW -> if (s.iShuffle) {
            stringResource(R.string.cup_showing) to Pal.paint(s.mySide)
        } else {
            stringResource(R.string.cup_watch_ball) to Pal.paint(s.mySide.other())
        }
        CupPhase.SHUFFLING -> if (s.iShuffle) {
            stringResource(R.string.cup_shuffle_fast) to Pal.paint(s.mySide)
        } else {
            stringResource(R.string.cup_watch_cups) to Pal.Ink
        }
        CupPhase.GUESSING -> if (s.iShuffle) {
            stringResource(
                R.string.cup_wait_guess,
                s.theirNick.ifBlank { stringResource(R.string.common_opponent) },
            ) to Pal.Ink
        } else {
            if (s.myGuess >= 0) stringResource(R.string.cup_locked) to Pal.Ink
            else stringResource(R.string.cup_which) to Pal.Ink
        }
        CupPhase.REVEAL -> when {
            s.guessCorrect == true && !s.iShuffle -> stringResource(R.string.cup_found) to Pal.paint(s.mySide)
            s.guessCorrect == true -> Wording.subject(
                s.theirNick, R.string.cup_finds_named, R.string.cup_finds_anon,
            ) to Pal.paint(s.mySide.other())
            s.iShuffle -> stringResource(
                R.string.cup_fooled_you,
                s.theirNick.ifBlank { stringResource(R.string.common_them) },
            ) to Pal.paint(s.mySide)
            else -> Wording.subject(
                s.theirNick, R.string.cup_fools_named, R.string.cup_fools_anon,
            ) to Pal.paint(s.mySide.other())
        }
    }
    Crossfade(targetState = text to color, animationSpec = tween(220), label = "status") { (t, c) ->
        Text(t, fontSize = 30.sp, fontWeight = FontWeight.Black, color = c)
    }
}

@Composable
private fun Footer(s: CupUi, onRematch: () -> Unit, onLeave: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 124.dp), horizontalAlignment = Alignment.Start) {
        if (s.phase == CupPhase.REVEAL) {
            if (s.theyWantRematch && !s.iWantRematch) {
                Text(
                    Wording.subject(s.theirNick, R.string.cup_wants_named, R.string.cup_wants_anon),
                    fontSize = 15.sp, color = Pal.Muted, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                )
            } else {
                Spacer(Modifier.height(26.dp))
            }
            RaisedButton(
                text = if (s.iWantRematch) stringResource(
                    R.string.common_waiting_rematch,
                    s.theirNick.ifBlank { stringResource(R.string.common_opponent) },
                ) else stringResource(R.string.cup_next),
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

private fun DrawScope.drawCups(s: CupUi, order: List<Int>, positions: Map<Int, Float>, n: Int) {
    if (n <= 0 || order.isEmpty()) return
    val slot = size.width / n
    val cupH = (slot * 1.05f).coerceAtMost(size.height * 0.72f)
    val cy = size.height / 2f
    val shufflerColor = Pal.paint(if (s.iShuffle) s.mySide else s.mySide.other())
    // The ball rides with its cup id, whatever slot glides it carries.
    val ballId = if (s.ballSlot in 0 until n) s.order.getOrElse(s.ballSlot) { -1 } else -1
    val guessId = if (!s.iShuffle && s.phase == CupPhase.GUESSING && s.myGuess in 0 until n) {
        order.getOrElse(s.myGuess) { -1 }
    } else -1
    val showBall = s.phase == CupPhase.SHOW || s.phase == CupPhase.REVEAL
    val targets = order.withIndex().associate { it.value to it.index }
    // Cups mid-glide draw last (on top) and hop slightly, so crossings read as passing over.
    val moving = positions.filter { (id, p) ->
        abs(p - (targets[id]?.toFloat() ?: p)) > 0.001f
    }.keys
    val drawOrder = order.filter { it !in moving } + order.filter { it in moving }
    for (id in drawOrder) {
        val p = positions[id] ?: continue
        val target = targets[id] ?: continue
        val cx = (p + 0.5f) * slot
        val hop = sin((1f - abs(p - target).coerceIn(0f, 1f)) * PI).toFloat() * slot * 0.30f
        // At the show and the reveal the ball's cup lifts to show the ball underneath.
        val lifted = showBall && id == ballId
        val lift = (if (lifted) cupH * 0.28f else 0f) + hop
        if (lifted) {
            drawCircle(shufflerColor, slot * 0.17f, Offset(cx, cy + cupH * 0.32f))
            drawCircle(
                Color.White.copy(alpha = 0.35f), slot * 0.07f,
                Offset(cx - slot * 0.05f, cy + cupH * 0.32f - slot * 0.05f),
            )
        }
        drawCup(Offset(cx, cy - lift), slot, cupH)
        // X-ray vision: only the shuffler sees which cup glows, and only while it matters.
        if (s.iShuffle && id == ballId && (s.phase == CupPhase.SHUFFLING || s.phase == CupPhase.GUESSING)) {
            drawCupOutline(Offset(cx, cy - lift), slot, cupH, shufflerColor, glow = true)
        } else if (id == guessId) {
            drawCupOutline(Offset(cx, cy - lift), slot, cupH, Pal.Teal, glow = false)
        }
    }
}

/** How long one cup glide takes. Fits inside the 250 ms tap spacing by design. */
private const val SWAP_ANIM_MS = 220

/** An upside-down cup: a tapered tumbler, narrow at the closed top, wide at the mouth. */
private fun DrawScope.cupPath(center: Offset, slot: Float, h: Float): Path {
    val topW = slot * 0.22f
    val botW = slot * 0.38f
    val top = center.y - h / 2f
    val bot = center.y + h / 2f
    return Path().apply {
        moveTo(center.x - topW, top)
        lineTo(center.x + topW, top)
        lineTo(center.x + botW, bot)
        lineTo(center.x - botW, bot)
        close()
    }
}

private fun DrawScope.drawCup(center: Offset, slot: Float, h: Float) {
    val topW = slot * 0.22f
    val botW = slot * 0.38f
    val top = center.y - h / 2f
    val bot = center.y + h / 2f
    val outline = (slot * 0.045f).coerceAtLeast(3f)

    // Soft table shadow so the cup sits on something.
    drawOval(
        Pal.Shadow.copy(alpha = 0.20f),
        topLeft = Offset(center.x - botW * 1.05f, bot + h * 0.015f),
        size = Size(botW * 2.1f, slot * 0.09f),
    )

    // Body with cylindrical shading: darker pressed edges, bright face in the middle.
    val body = cupPath(center, slot, h)
    drawPath(
        body,
        Brush.horizontalGradient(
            0.0f to Pal.FacePressed,
            0.22f to Pal.Face,
            0.5f to Pal.Face,
            0.78f to Pal.Face,
            1.0f to Pal.FacePressed,
            startX = center.x - botW,
            endX = center.x + botW,
        ),
    )
    // Centre sheen: a narrow lighter strip down the cup.
    val sheenW = (topW * 0.45f).coerceAtLeast(2f)
    drawRoundRect(
        Pal.Chalk.copy(alpha = 0.45f),
        topLeft = Offset(center.x - sheenW - topW * 0.25f, top + h * 0.12f),
        size = Size(sheenW * 2f, h * 0.62f),
    )
    drawPath(body, Pal.Line, style = Stroke(width = outline))

    // Closed top (the base of the upturned cup): a small dome cap.
    val capH = (slot * 0.13f).coerceAtLeast(6f)
    drawOval(
        Pal.Face,
        topLeft = Offset(center.x - topW, top - capH / 2f),
        size = Size(topW * 2f, capH),
    )
    drawOval(
        Pal.Line,
        topLeft = Offset(center.x - topW, top - capH / 2f),
        size = Size(topW * 2f, capH),
        style = Stroke(width = (outline * 0.8f).coerceAtLeast(2f)),
    )
    // Glint on the cap.
    drawOval(
        Pal.Chalk.copy(alpha = 0.7f),
        topLeft = Offset(center.x - topW * 0.55f, top - capH * 0.32f),
        size = Size(topW * 0.7f, capH * 0.38f),
    )

    // Open mouth: a thick rim seen edge-on at the bottom.
    val rimH = (slot * 0.15f).coerceAtLeast(7f)
    drawOval(
        Pal.FacePressed,
        topLeft = Offset(center.x - botW, bot - rimH / 2f),
        size = Size(botW * 2f, rimH),
    )
    drawOval(
        Pal.Shadow.copy(alpha = 0.35f),
        topLeft = Offset(center.x - botW * 0.78f, bot - rimH * 0.32f),
        size = Size(botW * 1.56f, rimH * 0.64f),
    )
    drawOval(
        Pal.Line,
        topLeft = Offset(center.x - botW, bot - rimH / 2f),
        size = Size(botW * 2f, rimH),
        style = Stroke(width = (outline * 0.8f).coerceAtLeast(2f)),
    )
}

private fun DrawScope.drawCupOutline(center: Offset, slot: Float, h: Float, color: Color, glow: Boolean) {
    val p = cupPath(center, slot, h)
    if (glow) {
        drawPath(p, color.copy(alpha = 0.25f), style = Stroke(width = slot * 0.10f))
    }
    drawPath(p, color, style = Stroke(width = slot * 0.05f))
}
