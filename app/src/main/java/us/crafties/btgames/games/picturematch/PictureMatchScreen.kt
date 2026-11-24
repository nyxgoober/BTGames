package us.crafties.btgames.games.picturematch

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import us.crafties.btgames.R
import us.crafties.btgames.ui.AppScreen
import us.crafties.btgames.ui.Haptics
import us.crafties.btgames.ui.Pal
import us.crafties.btgames.ui.RaisedButton
import us.crafties.btgames.ui.RaisedCard
import us.crafties.btgames.ui.Tone
import kotlin.math.PI
import kotlin.math.absoluteValue
import kotlin.math.cos

/** The board, the scoreboard and the host's setup panel. All colours come from Pal. */
@Composable
fun PictureMatchScreen(
    state: PictureMatchUi,
    onGrid: (Int) -> Unit,
    onDeal: () -> Unit,
    onFlip: (Int) -> Unit,
    onAgain: () -> Unit,
    onLeave: () -> Unit,
) {
    val myPaint = Pal.paint(state.mySide)
    val turnName = state.nicks.getOrNull(state.turn).orEmpty()
    val totalScore = state.scores.sum()

    var flash by remember { mutableStateOf(false) }
    var lastTotal by remember { mutableIntStateOf(-1) }
    LaunchedEffect(totalScore) {
        if (lastTotal >= 0 && totalScore > lastTotal && !state.over) {
            flash = true
            Haptics.tick()
            delay(900)
            flash = false
        }
        lastTotal = totalScore
    }
    LaunchedEffect(state.over) {
        if (state.over) {
            when (state.winner) {
                state.mySeat -> Haptics.win()
                -1 -> Haptics.click()
                else -> Haptics.lose()
            }
        }
    }

    AppScreen(title = stringResource(R.string.g_pm_title), onBack = onLeave) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!state.ready && state.states.isEmpty()) {
                if (state.isHost) SetupPanel(state, onGrid, onDeal)
                else {
                    Spacer(Modifier.height(48.dp))
                    Text(
                        stringResource(R.string.pm_waiting),
                        fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Pal.Muted,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(20.dp))
                RaisedButton(stringResource(R.string.common_leave), onClick = onLeave)
                return@Column
            }

            RaisedCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.nicks.forEachIndexed { seat, nick ->
                        val paint = if (seat == state.mySeat) myPaint else Pal.Teal
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(12.dp).clip(CircleShape).background(paint))
                                Spacer(Modifier.size(8.dp))
                                Text(
                                    if (seat == state.mySeat) stringResource(R.string.pm_you) else nick,
                                    fontSize = 16.sp, fontWeight = if (seat == state.mySeat) FontWeight.Bold else FontWeight.Normal,
                                    color = Pal.Ink,
                                )
                                if (!state.over && seat == state.turn) Text("  •", color = paint, fontSize = 18.sp)
                            }
                            Text(
                                "${state.scores.getOrNull(seat) ?: 0}",
                                fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Pal.Ink,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            val status = when {
                state.over -> when (state.winner) {
                    state.mySeat -> stringResource(R.string.pm_won)
                    -1 -> stringResource(R.string.pm_draw)
                    else -> stringResource(R.string.pm_lost, state.nicks.getOrNull(state.winner).orEmpty())
                }
                flash -> stringResource(R.string.pm_matched)
                state.myTurn -> stringResource(R.string.pm_your_turn)
                else -> stringResource(R.string.pm_their_turn, turnName)
            }
            Crossfade(targetState = status, animationSpec = tween(220), label = "status") { t ->
                Text(
                    t, fontSize = 20.sp, fontWeight = FontWeight.Black,
                    color = if (state.over && state.winner == state.mySeat) myPaint else Pal.Ink,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(14.dp))

            if (state.states.isNotEmpty()) Board(state, onFlip)

            Spacer(Modifier.height(16.dp))
            if (state.over) {
                RaisedButton(
                    stringResource(R.string.common_play_again),
                    onClick = onAgain,
                    enabled = !state.iWantAgain,
                    tone = Tone.Primary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            RaisedButton(stringResource(R.string.common_leave), onClick = onLeave, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SetupPanel(state: PictureMatchUi, onGrid: (Int) -> Unit, onDeal: () -> Unit) {
    RaisedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.pm_grid), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Pal.Ink)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (g in PictureIcons.VALID_GRIDS) RaisedButton(
                    text = "${g}×${g}",
                    onClick = { onGrid(g) },
                    tone = if (state.grid == g) Tone.Primary else Tone.Light,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(20.dp))
            RaisedButton(
                stringResource(R.string.pm_deal), onClick = onDeal,
                tone = Tone.Primary, modifier = Modifier.fillMaxWidth().height(56.dp),
            )
        }
    }
}

@Composable
private fun Board(state: PictureMatchUi, onFlip: (Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 6.dp
        val tile: Dp = (maxWidth - gap * (state.grid - 1)) / state.grid
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            for (row in 0 until state.grid) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    for (col in 0 until state.grid) {
                        val index = row * state.grid + col
                        key(index) {
                            PictureTile(
                                icon = state.icons.getOrNull(index),
                                tileState = state.states[index],
                                size = tile,
                                enabled = state.myTurn && state.states[index] == TileState.HIDDEN,
                                onClick = { Haptics.tick(); onFlip(index) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PictureTile(
    icon: String?,
    tileState: TileState,
    size: Dp,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val faceUp = tileState != TileState.HIDDEN
    val angle by animateFloatAsState(if (faceUp) 180f else 0f, tween(300), label = "pmFlip")
    val ring = if (tileState == TileState.MATCHED) Pal.Teal else Pal.Line
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .graphicsLayer { scaleX = cos((angle * PI / 180.0).toFloat()).absoluteValue },
        contentAlignment = Alignment.Center,
    ) {
        if (angle < 90f) {
            Box(
                Modifier.fillMaxSize().clip(CircleShape).background(Pal.Court)
                    .border(2.dp, Pal.Teal, CircleShape),
            )
            Text("?", fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Black, color = Pal.Teal)
        } else {
            Box(
                Modifier.fillMaxSize().clip(CircleShape).background(Pal.Face)
                    .border(3.dp, ring, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(icon ?: "", fontSize = (size.value * 0.52f).sp, textAlign = TextAlign.Center)
            }
        }
    }
}
