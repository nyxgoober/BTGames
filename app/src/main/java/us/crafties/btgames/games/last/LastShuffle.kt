package us.crafties.btgames.games.last

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import us.crafties.btgames.R
import us.crafties.btgames.ui.Pal
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val SHUFFLE_MS = 3000
private const val FADE_MS = 220
private const val CARD_COUNT = 24
/** Where the wash ends and the riffle begins, as a fraction of the whole intro. */
private const val WASH_END = 0.36f
/** Where the riffle ends and the deal begins. */
private const val RIFFLE_END = 0.62f
private val CARD_WIDTH = 40.dp
private val HALF_SEP = 46.dp
private const val TWO_PI = (2.0 * PI).toFloat()

/**
 * The start of every deal: an invisible pair of hands washes the deck across the table, riffles it
 * back into one pile, then deals card backs round-robin to every seat. It is local-only; the game
 * itself is already dealt underneath, and this covers the table until the cards are out.
 */
@Composable
internal fun ShuffleIntro(
    match: Int,
    deck: Offset,
    hand: Offset,
    seats: Map<Int, Offset>,
    origin: Offset,
    mySeat: Int,
    playerCount: Int,
    rootSize: IntSize,
    onDone: () -> Unit,
) {
    val done by rememberUpdatedState(onDone)
    val progress = remember(match) { Animatable(0f) }
    val fade = remember(match) { Animatable(1f) }
    LaunchedEffect(match) {
        progress.animateTo(1f, tween(SHUFFLE_MS, easing = LinearEasing))
        fade.animateTo(0f, tween(FADE_MS))
        done()
    }

    val density = LocalDensity.current
    val cardW = with(density) { CARD_WIDTH.toPx() }
    val cardH = cardW * 1.45f
    val halfSep = with(density) { HALF_SEP.toPx() }
    val width = rootSize.width.toFloat()
    val height = rootSize.height.toFloat()
    val center = if (deck != Offset.Zero) deck - origin else Offset(width / 2f, height * 0.42f)
    val maxR = minOf(width, height) * 0.34f
    val players = playerCount.coerceAtLeast(1)

    val cards = remember(match) {
        val rnd = Random(match.toLong())
        List(CARD_COUNT) { i ->
            ShuffleCard(
                half = i % 2,
                angle = rnd.nextFloat() * TWO_PI,
                turns = 0.35f + rnd.nextFloat() * 0.8f,
                radius = 0.35f + rnd.nextFloat() * 0.65f,
                spin = (rnd.nextFloat() - 0.5f) * 70f,
                pileRot = (rnd.nextFloat() - 0.5f) * 12f,
                jx = (rnd.nextFloat() - 0.5f) * 7f,
                jy = (rnd.nextFloat() - 0.5f) * 7f,
            )
        }
    }

    // Where each seat's cards land: the real hand/chip when it has been laid out, otherwise a
    // sensible row across the top of the table.
    val targets = remember(hand, seats, origin, mySeat, players, width, height) {
        List(players) { seat ->
            val raw = when {
                seat == mySeat && hand != Offset.Zero -> hand - origin
                seats[seat]?.takeIf { it != Offset.Zero } != null -> seats[seat]!! - origin
                else -> Offset(width * (seat + 1f) / (players + 1f), height * 0.16f)
            }
            if (width > 48f && height > 48f) {
                Offset(raw.x.coerceIn(24f, width - 24f), raw.y.coerceIn(24f, height - 24f))
            } else raw
        }
    }

    val p = progress.value
    val fadeAlpha = fade.value
    val caption = if (p < RIFFLE_END) stringResource(R.string.last_shuffling)
                  else stringResource(R.string.last_dealing)

    Box(
        Modifier
            .fillMaxSize()
            .alpha(fadeAlpha)
            .background(Pal.Paper)
            .pointerInput(Unit) {
                // The intro is a cutscene: swallow every touch until it finishes.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                        if (event.changes.none { it.pressed }) break
                    }
                }
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 26.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Pal.PaperDim)
                .border(1.dp, Pal.Line, RoundedCornerShape(28.dp)),
        )
        for ((i, card) in cards.withIndex()) {
            val pose = cardPose(card, i, p, center, maxR, halfSep, targets, players)
            if (pose.alpha <= 0.01f) continue
            Box(
                Modifier
                    .offset {
                        IntOffset(
                            (pose.at.x - cardW / 2f).roundToInt(),
                            (pose.at.y - cardH / 2f).roundToInt(),
                        )
                    }
                    .graphicsLayer {
                        rotationZ = pose.rot
                        alpha = pose.alpha
                    },
            ) {
                LastCardBack(width = CARD_WIDTH)
            }
        }
        Text(
            caption,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 44.dp).zIndex(1f),
            fontSize = 20.sp,
            fontWeight = FontWeight.Black,
            color = Pal.Ink,
        )
    }
}

private data class ShuffleCard(
    val half: Int,
    val angle: Float,
    val turns: Float,
    val radius: Float,
    val spin: Float,
    val pileRot: Float,
    val jx: Float,
    val jy: Float,
)

private data class Pose(val at: Offset, val rot: Float, val alpha: Float)

private fun cardPose(
    c: ShuffleCard,
    i: Int,
    p: Float,
    center: Offset,
    maxR: Float,
    halfSep: Float,
    targets: List<Offset>,
    players: Int,
): Pose {
    val base = center + Offset(c.jx, c.jy)
    val pile = center + Offset((if (c.half == 0) -halfSep else halfSep) + c.jx, c.jy)
    val stack = center + Offset(c.jx * 0.25f, c.jy * 0.25f + (i - CARD_COUNT / 2f) * 0.35f)
    return when {
        p < WASH_END -> {
            // Wash: fan the deck out in a swirl and gather it into two halves.
            val w = (p / WASH_END).coerceIn(0f, 1f)
            val spread = fsin(w * PI.toFloat())
            val angle = c.angle + w * c.turns * TWO_PI
            val r = maxR * c.radius * spread
            val swirl = Offset(fcos(angle) * r, fsin(angle) * r)
            val at = base + swirl + (pile - base) * w
            Pose(at, c.spin * spread + c.pileRot * w, 1f)
        }
        p < RIFFLE_END -> {
            // Riffle: alternating halves cascade back into one stack, each card arcing across.
            val r = ((p - WASH_END) / (RIFFLE_END - WASH_END)).coerceIn(0f, 1f)
            val start = i.toFloat() / CARD_COUNT * 0.55f
            val t = ((r - start) / 0.45f).coerceIn(0f, 1f)
            val e = smooth(t)
            val lift = fsin(t * PI.toFloat()) * (if (c.half == 0) 20f else 12f)
            val rot = c.pileRot * (1f - e) + c.spin * 0.12f * fsin(t * PI.toFloat())
            Pose(lerp(pile, stack, e) + Offset(0f, -lift), rot, 1f)
        }
        else -> {
            // Deal: round-robin to every seat, then fade the card away where it lands.
            val d = ((p - RIFFLE_END) / (1f - RIFFLE_END)).coerceIn(0f, 1f)
            val seat = i % players
            val round = i / players
            val target = (targets.getOrNull(seat) ?: center) +
                Offset(c.jx * 0.35f, c.jy * 0.35f - round * 0.5f)
            val start = i.toFloat() / CARD_COUNT * 0.70f
            val t = ((d - start) / 0.30f).coerceIn(0f, 1f)
            val e = easeOut(t)
            val lift = fsin(t * PI.toFloat()) * 28f
            val alpha = if (t >= 1f) {
                (1f - (d - (start + 0.30f)) * 10f).coerceIn(0f, 1f)
            } else 1f
            val rot = c.spin * 0.10f * fsin(t * PI.toFloat())
            Pose(lerp(stack, target, e) + Offset(0f, -lift), rot, alpha)
        }
    }
}

private fun smooth(t: Float): Float = t * t * (3f - 2f * t)

private fun easeOut(t: Float): Float = 1f - (1f - t) * (1f - t)

private fun fsin(x: Float): Float = sin(x.toDouble()).toFloat()

private fun fcos(x: Float): Float = cos(x.toDouble()).toFloat()
