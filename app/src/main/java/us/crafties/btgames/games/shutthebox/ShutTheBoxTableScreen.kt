package us.crafties.btgames.games.shutthebox

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import us.crafties.btgames.R
import us.crafties.btgames.ui.AppScreen
import us.crafties.btgames.ui.Pal
import us.crafties.btgames.ui.RaisedButton
import us.crafties.btgames.ui.Tone

/**
 * The table screen for Shut The Box: a tablet or TV in the middle of the table. It referees but
 * holds no seat. The whole table is always in view, one wooden box on each side of it, every box
 * turned to face its player, with dice, tiles and totals all visible. The pickers for the mode and
 * the tile count are here too, before the first roll; the scores and the result sit beside it.
 */
@Composable
fun ShutTheBoxTableScreen(session: ShutTheBoxSession, onLeave: () -> Unit) {
    val ui by session.ui.collectAsState()
    KeepScreenOn()
    // The camera never moves on the table screen: seat 0 at the bottom, the whole table in view.
    val camRot = remember { Animatable(0f) }
    val zoom = remember { Animatable(0f) }

    AppScreen(title = stringResource(R.string.g_shut_the_box_title), onBack = onLeave) {
        Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(
                Modifier.weight(0.8f).fillMaxHeight().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val turnName = ui.nicks.getOrNull(ui.currentTurn).orEmpty()
                Text(
                    when {
                        ui.finalWinners != null -> stringResource(R.string.stb_game_over)
                        turnName.isNotBlank() -> stringResource(R.string.stb_turn_of, turnName)
                        else -> stringResource(R.string.stb_waiting)
                    },
                    fontSize = 26.sp, fontWeight = FontWeight.Black, color = Pal.Ink, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))

                if (ui.canPickMode) {
                    Text(stringResource(R.string.stb_mode), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Pal.Ink)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        RaisedButton(
                            stringResource(R.string.stb_mode_until), onClick = { session.pickMode(StbMode.UNTIL_DONE) },
                            tone = if (ui.mode == StbMode.UNTIL_DONE) Tone.Primary else Tone.Light,
                            modifier = Modifier.weight(1f),
                        )
                        RaisedButton(
                            stringResource(R.string.stb_mode_one), onClick = { session.pickMode(StbMode.ONE_FLIP) },
                            tone = if (ui.mode == StbMode.ONE_FLIP) Tone.Primary else Tone.Light,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.stb_tiles), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Pal.Ink)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        RaisedButton(
                            stringResource(R.string.stb_tiles_9), onClick = { session.pickTiles(StbTiles.NINE) },
                            tone = if (ui.tiles == StbTiles.NINE) Tone.Primary else Tone.Light,
                            modifier = Modifier.weight(1f),
                        )
                        RaisedButton(
                            stringResource(R.string.stb_tiles_12), onClick = { session.pickTiles(StbTiles.TWELVE) },
                            tone = if (ui.tiles == StbTiles.TWELVE) Tone.Primary else Tone.Light,
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else if (ui.finalWinners == null) {
                    Text(
                        stringResource(if (ui.mode == StbMode.ONE_FLIP) R.string.stb_mode_one else R.string.stb_mode_until) +
                            "  ·  " + stringResource(if (ui.tiles == StbTiles.TWELVE) R.string.stb_tiles_12 else R.string.stb_tiles_9),
                        fontSize = 15.sp, color = Pal.Muted,
                    )
                }
                Spacer(Modifier.height(14.dp))

                Text(stringResource(R.string.stb_scores), fontSize = 18.sp, fontWeight = FontWeight.Black, color = Pal.Ink)
                Spacer(Modifier.height(6.dp))
                ui.nicks.forEachIndexed { seat, nick ->
                    val view = ui.boards.getOrNull(seat)
                    val isWinner = ui.finalWinners?.contains(seat) == true
                    val marker = when {
                        isWinner -> "★ "
                        seat == ui.currentTurn && ui.finalWinners == null -> "▶ "
                        else -> ""
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            marker + nick + if (view?.out == true) "  (" + stringResource(R.string.stb_out) + ")" else "",
                            fontSize = 18.sp, color = Pal.Ink,
                            fontWeight = if (isWinner || seat == ui.currentTurn) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                        )
                        Text(view?.score?.toString() ?: "–", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Pal.Ink)
                    }
                }

                ui.finalWinners?.let { w ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.stb_winner, w.joinToString(", ") { ui.nicks.getOrNull(it).orEmpty() }),
                        fontSize = 24.sp, fontWeight = FontWeight.Black, color = Pal.Teal, textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(20.dp))
                RaisedButton(stringResource(R.string.stb_leave), onClick = onLeave, modifier = Modifier.fillMaxWidth())
            }

            // The table itself: a square, as tall as the screen allows, with a box on every side.
            Box(Modifier.weight(1.2f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                if (ui.boards.isNotEmpty()) {
                    BoxWithConstraints {
                        val side = minOf(maxWidth, maxHeight)
                        Box(Modifier.size(side)) {
                            StbTable(
                                ui = ui, camRot = camRot, zoom = zoom, youLabel = "",
                                selected = emptySet(), canPick = false, onTap = {},
                            )
                        }
                    }
                }
            }
        }
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
