package us.crafties.btgames.games.rps

import androidx.compose.animation.core.tween
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import us.crafties.btgames.R
import us.crafties.btgames.ui.Haptics
import us.crafties.btgames.ui.Pal
import us.crafties.btgames.ui.RaisedButton
import us.crafties.btgames.ui.RaisedCard
import us.crafties.btgames.ui.Tone
import us.crafties.btgames.ui.Wording

@Composable
fun RpsScreen(
    state: RpsUi,
    onThrow: (Throw) -> Unit,
    onRematch: () -> Unit,
    onLeave: () -> Unit,
) {
    // Feel it: a tick on the throw, a click on a drawn round, the pattern on the match.
    LaunchedEffect(state.myThrow, state.round, state.match) {
        if (state.myThrow != null) Haptics.tick()
    }
    LaunchedEffect(state.roundResult) {
        when (state.roundResult) {
            RpsResult.WIN -> Haptics.thud()
            RpsResult.DRAW -> Haptics.click()
            else -> Unit
        }
    }
    LaunchedEffect(state.outcome) {
        when (state.outcome) {
            RpsResult.WIN -> Haptics.win()
            RpsResult.LOSS -> Haptics.lose()
            else -> Unit
        }
    }

    Column(
        Modifier.fillMaxSize().background(Pal.Paper).safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Scoreboard(state)
        Spacer(Modifier.height(8.dp))
        StatusLine(state)
        Spacer(Modifier.height(10.dp))

        RevealCard(state)
        Spacer(Modifier.height(16.dp))

        // The three throws. Once yours is in, they lock until the reveal is over.
        val locked = state.phase != RpsPhase.PLAYING || state.myThrow != null
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Throw.entries.forEach { t ->
                val picked = state.myThrow == t
                RaisedButton(
                    text = throwName(t),
                    onClick = { onThrow(t) },
                    enabled = !locked,
                    tone = if (picked) Tone.Primary else Tone.Light,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Footer(state, onRematch, onLeave)
    }
}

// ---- header -------------------------------------------------------------------------

@Composable
private fun Scoreboard(s: RpsUi) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Tally(
            name = s.myNick, score = s.myScore, color = Pal.paint(s.mySide),
            align = Alignment.Start,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.rps_round), fontSize = 12.sp, color = Pal.Muted)
            Text("${s.round + 1}", fontSize = 26.sp, fontWeight = FontWeight.Black, color = Pal.Ink)
            Text(stringResource(R.string.rps_first_to, RpsRules.MATCH_POINT), fontSize = 12.sp, color = Pal.Muted)
        }
        Tally(
            name = s.theirNick, score = s.theirScore, color = Pal.paint(s.mySide.other()),
            align = Alignment.End,
        )
    }
}

@Composable
private fun Tally(name: String, score: Int, color: Color, align: Alignment.Horizontal) {
    Column(horizontalAlignment = align, modifier = Modifier.padding(horizontal = 2.dp)) {
        Text(
            name.ifBlank { stringResource(R.string.common_player) }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            color = color, maxLines = 1,
        )
        Text("$score", fontSize = 40.sp, fontWeight = FontWeight.Black, color = Pal.Ink)
    }
}

@Composable
private fun StatusLine(s: RpsUi) {
    val opponent = stringResource(R.string.common_opponent)
    val (text, color) = when (s.phase) {
        RpsPhase.PLAYING -> when {
            s.roundResult != null -> when (s.roundResult) {
                RpsResult.WIN -> stringResource(R.string.rps_you_take) to Pal.paint(s.mySide)
                RpsResult.LOSS -> Wording.subject(
                    s.theirNick, R.string.rps_they_take_named, R.string.rps_they_take_anon,
                ) to Pal.paint(s.mySide.other())
                RpsResult.DRAW -> stringResource(R.string.rps_round_drawn) to Pal.Ink
            }
            s.myThrow != null -> stringResource(
                R.string.rps_wait_thrown, s.theirNick.ifBlank { opponent },
            ) to Pal.Ink
            else -> stringResource(R.string.rps_throw) to Pal.Ink
        }
        RpsPhase.OVER -> when (s.outcome) {
            RpsResult.WIN -> stringResource(R.string.rps_you_match) to Pal.paint(s.mySide)
            RpsResult.LOSS -> Wording.subject(
                s.theirNick, R.string.rps_they_match_named, R.string.rps_they_match_anon,
            ) to Pal.paint(s.mySide.other())
            else -> stringResource(R.string.rps_drawn_match) to Pal.Ink
        }
    }
    Crossfade(targetState = text to color, animationSpec = tween(220), label = "status") { (t, c) ->
        Text(t, fontSize = 26.sp, fontWeight = FontWeight.Black, color = c)
    }
}

// ---- reveal ---------------------------------------------------------------------------

@Composable
private fun RevealCard(s: RpsUi) {
    RaisedCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RevealSide(
                label = s.myNick.ifBlank { stringResource(R.string.common_you) }, throw_ = s.myThrow,
                color = Pal.paint(s.mySide),
            )
            Text(stringResource(R.string.rps_vs), fontSize = 16.sp, color = Pal.Muted, fontWeight = FontWeight.Bold)
            RevealSide(
                label = s.theirNick.ifBlank { stringResource(R.string.common_them) }, throw_ = s.theirThrow,
                color = Pal.paint(s.mySide.other()),
            )
        }
        if (s.roundResult != null && s.roundResult != RpsResult.DRAW) {
            Spacer(Modifier.height(8.dp))
            val winner = if (s.roundResult == RpsResult.WIN) s.myThrow else s.theirThrow
            Text(
                stringResource(RpsRules.verb(winner ?: Throw.ROCK)),
                fontSize = 14.sp, color = Pal.Muted,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun RevealSide(label: String, throw_: Throw?, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        Canvas(Modifier.size(64.dp)) {
            if (throw_ == null) drawHidden() else drawThrowGlyph(throw_, color)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (throw_ == null) "" else throwNameLow(throw_), fontSize = 13.sp, color = Pal.Muted,
        )
    }
}

/** Localized throw names for the buttons (capitalized) and the reveal captions. */
@Composable
private fun throwName(t: Throw): String = stringResource(
    when (t) {
        Throw.ROCK -> R.string.rps_throw_rock
        Throw.PAPER -> R.string.rps_throw_paper
        Throw.SCISSORS -> R.string.rps_throw_scissors
    }
)

@Composable
private fun throwNameLow(t: Throw): String = stringResource(
    when (t) {
        Throw.ROCK -> R.string.rps_throw_rock_low
        Throw.PAPER -> R.string.rps_throw_paper_low
        Throw.SCISSORS -> R.string.rps_throw_scissors_low
    }
)

private fun DrawScope.drawHidden() {
    drawCircle(
        Pal.Muted.copy(alpha = 0.35f), radius = size.width * 0.3f,
        center = center, style = Stroke(width = size.width * 0.07f),
    )
}

/**
 * Shared by the reveal and the menu tile. Each throw is a small dimensional icon — faceted
 * stone, folded sheet, open scissors — built from the one accent [color] plus lighter/darker
 * shades of it, the same trick the block tower uses to sell volume from a single hue.
 */
fun DrawScope.drawThrowGlyph(t: Throw, color: Color) {
    val s = size.width
    val cx = s / 2
    val cy = s / 2
    val dark = color.copy(
        red = color.red * 0.66f, green = color.green * 0.66f, blue = color.blue * 0.66f,
    )
    val light = Color(
        red = (color.red + (1f - color.red) * 0.45f).coerceIn(0f, 1f),
        green = (color.green + (1f - color.green) * 0.45f).coerceIn(0f, 1f),
        blue = (color.blue + (1f - color.blue) * 0.45f).coerceIn(0f, 1f),
        alpha = color.alpha,
    )
    when (t) {
        Throw.ROCK -> {
            // A faceted stone: three overlapping planes (top-lit, left-lit, shadowed) instead of
            // one flat lump, so it reads as a solid rather than a silhouette.
            val w = s * 0.38f
            val h = s * 0.32f
            val top = Path().apply {
                moveTo(cx - w * 0.75f, cy - h * 0.1f)
                lineTo(cx - w * 0.2f, cy - h)
                lineTo(cx + w * 0.55f, cy - h * 0.72f)
                lineTo(cx + w * 0.1f, cy - h * 0.05f)
                close()
            }
            val leftFace = Path().apply {
                moveTo(cx - w * 0.75f, cy - h * 0.1f)
                lineTo(cx + w * 0.1f, cy - h * 0.05f)
                lineTo(cx - w * 0.15f, cy + h)
                lineTo(cx - w * 0.85f, cy + h * 0.55f)
                close()
            }
            val rightFace = Path().apply {
                moveTo(cx + w * 0.1f, cy - h * 0.05f)
                lineTo(cx + w * 0.55f, cy - h * 0.72f)
                lineTo(cx + w, cy + h * 0.35f)
                lineTo(cx - w * 0.15f, cy + h)
                close()
            }
            drawPath(rightFace, dark)
            drawPath(leftFace, color)
            drawPath(top, light)
        }
        Throw.PAPER -> {
            // A folded sheet: the page plus a lifted corner, so it reads as a physical object
            // instead of a rounded outline.
            val w = s * 0.30f
            val h = s * 0.38f
            val fold = w * 0.42f
            val sheet = Path().apply {
                moveTo(cx - w, cy - h)
                lineTo(cx + w - fold, cy - h)
                lineTo(cx + w, cy - h + fold)
                lineTo(cx + w, cy + h)
                lineTo(cx - w, cy + h)
                close()
            }
            drawPath(sheet, light)
            drawPath(sheet, color, style = Stroke(width = s * 0.045f))
            val cornerFold = Path().apply {
                moveTo(cx + w - fold, cy - h)
                lineTo(cx + w, cy - h + fold)
                lineTo(cx + w - fold, cy - h + fold)
                close()
            }
            drawPath(cornerFold, dark)
            val lineStroke = s * 0.028f
            for (f in listOf(0.15f, 0.45f, 0.72f)) {
                drawLine(
                    color.copy(alpha = color.alpha * 0.55f),
                    Offset(cx - w * 0.62f, cy - h * 0.55f + h * 1.35f * f),
                    Offset(cx + w * 0.62f, cy - h * 0.55f + h * 1.35f * f),
                    lineStroke, StrokeCap.Round,
                )
            }
        }
        Throw.SCISSORS -> {
            // Open scissors, blades up: two tapered blades crossing at a pivot screw, with a
            // finger loop and a (smaller) thumb loop below — a real scissors silhouette that
            // stays legible down at menu-tile size, instead of a plain X.
            val pivot = Offset(cx, cy + s * 0.02f)
            val tipA = Offset(cx - s * 0.26f, cy - s * 0.30f)
            val tipB = Offset(cx + s * 0.26f, cy - s * 0.30f)
            val loopA = Offset(cx - s * 0.14f, cy + s * 0.27f)
            val loopB = Offset(cx + s * 0.14f, cy + s * 0.27f)
            fun blade(tip: Offset, fill: Color) {
                val d = tip - pivot
                val len = d.getDistance().coerceAtLeast(1f)
                // Perpendicular, half a blade-width at the base, tapering to a point at the tip.
                val px = -d.y / len * s * 0.045f
                val py = d.x / len * s * 0.045f
                val path = Path().apply {
                    moveTo(pivot.x + px, pivot.y + py)
                    lineTo(tip.x, tip.y)
                    lineTo(pivot.x - px, pivot.y - py)
                    close()
                }
                drawPath(path, fill)
                drawLine(dark, pivot, tip, s * 0.022f, StrokeCap.Round)
            }
            // Shafts first, then blades over them, then loops, then the pivot screw on top.
            drawLine(dark, pivot, loopA, s * 0.05f, StrokeCap.Round)
            drawLine(dark, pivot, loopB, s * 0.05f, StrokeCap.Round)
            blade(tipA, light)
            blade(tipB, color)
            drawCircle(color, radius = s * 0.095f, center = loopA, style = Stroke(width = s * 0.05f))
            drawCircle(color, radius = s * 0.078f, center = loopB, style = Stroke(width = s * 0.05f))
            drawCircle(dark, radius = s * 0.045f, center = pivot)
            drawCircle(light, radius = s * 0.045f, center = pivot - Offset(s * 0.008f, s * 0.008f))
        }
    }
}

// ---- footer -------------------------------------------------------------------------------

@Composable
private fun Footer(s: RpsUi, onRematch: () -> Unit, onLeave: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        if (s.phase == RpsPhase.OVER) {
            Text(
                stringResource(
                    R.string.rps_matches,
                    s.myMatches,
                    s.theirNick.ifBlank { stringResource(R.string.common_them_low) },
                    s.theirMatches, s.draws,
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
        } else {
            Text(
                stringResource(R.string.rps_draws_match, s.draws),
                fontSize = 14.sp, color = Pal.Muted,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        TextButton(onClick = onLeave) {
            Text(stringResource(R.string.common_leave), color = Pal.Muted, fontWeight = FontWeight.SemiBold)
        }
    }
}
