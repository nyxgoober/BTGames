package us.crafties.btgames.games.shutthebox

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import us.crafties.btgames.R
import us.crafties.btgames.ui.AppScreen
import us.crafties.btgames.ui.Haptics
import us.crafties.btgames.ui.Pal
import us.crafties.btgames.ui.RaisedButton
import us.crafties.btgames.ui.Tone
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Table geometry, as fractions of the table's width.
private const val BOARD_W = 0.27f
private const val SEAT_RADIUS = 0.31f
private const val ZOOM_IN = 0.92f / BOARD_W

/** Shut The Box: every player's wooden box sits round a shared table, and the camera follows the turn. */
@Composable
fun ShutTheBoxScreen(
    session: ShutTheBoxSession,
    onLeave: () -> Unit,
) {
    val ui by session.ui.collectAsState()
    var selected by remember { mutableStateOf<Set<Int>>(emptySet()) }

    // Camera: after a turn the view zooms out to the whole table, swings round to the next
    // player's seat and zooms back in. Every phone does the same, so everyone sees one view.
    val n = ui.boards.size
    val camRot = remember { Animatable(0f) }
    val zoom = remember { Animatable(1f) }
    var cameraPlaced by remember { mutableStateOf(false) }
    LaunchedEffect(ui.currentTurn, n) {
        if (n == 0) return@LaunchedEffect
        val target = -360f * ui.currentTurn / n
        if (!cameraPlaced) {
            camRot.snapTo(target); zoom.snapTo(1f); cameraPlaced = true
        } else {
            zoom.animateTo(0f, tween(550, easing = FastOutSlowInEasing))
            val back = ((camRot.value - target) % 360f + 360f) % 360f   // always swing on to the next seat
            if (back > 0.5f) camRot.animateTo(camRot.value - back, tween(900, easing = FastOutSlowInEasing))
            zoom.animateTo(1f, tween(600, easing = FastOutSlowInEasing))
        }
    }
    LaunchedEffect(ui.finalWinners) { if (ui.finalWinners != null) zoom.animateTo(0f, tween(700)) }
    val camSettled by remember { derivedStateOf { zoom.value > 0.97f } }

    // Dice are thrown for ~0.7 s; nothing can be picked until they land.
    var landed by remember { mutableStateOf(true) }
    val myRoll = ui.myBoard?.rollId ?: 0
    LaunchedEffect(myRoll) { landed = false; delay(750); landed = true }
    LaunchedEffect(ui.rolled, ui.myTurn) { selected = emptySet() }

    val youLabel = stringResource(R.string.stb_you)
    val sum = ui.diceSum
    val canPlay = ui.myTurn && ui.rolled && landed && camSettled && ui.finalWinners == null
    val picked = selected.toList().sorted()
    val pickedSum = picked.sum()
    val legal = sum != null && picked.isNotEmpty() && pickedSum == sum
    val mustFinish = ui.myTurn && landed && camSettled && (ui.boxShut || (ui.rolled && !ui.moveAvailable))

    // Haptics: my turn comes round, the dice are thrown and land, the box is shut or I am stuck,
    // and the result. Everyone else's moves are felt through the camera, not the motor.
    LaunchedEffect(ui.myTurn) { if (ui.myTurn && ui.finalWinners == null) Haptics.click() }
    LaunchedEffect(myRoll) { if (myRoll != 0) { Haptics.tick(); delay(750); Haptics.thud() } }
    LaunchedEffect(mustFinish) { if (mustFinish) { if (ui.boxShut) Haptics.win() else Haptics.lose() } }
    LaunchedEffect(ui.finalWinners) {
        ui.finalWinners?.let { if (ui.mySeat in it) Haptics.win() else Haptics.lose() }
    }

    AppScreen(title = stringResource(R.string.g_shut_the_box_title), onBack = onLeave) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val turnName = ui.nicks.getOrNull(ui.currentTurn).orEmpty()
            val status = when {
                ui.finalWinners != null -> stringResource(R.string.stb_game_over)
                ui.myTurn -> stringResource(R.string.stb_your_turn)
                turnName.isNotBlank() -> stringResource(R.string.stb_turn_of, turnName)
                else -> stringResource(R.string.stb_waiting)
            }
            Crossfade(targetState = status, animationSpec = tween(220), label = "status") { t ->
                Text(t, fontSize = 22.sp, fontWeight = FontWeight.Black, color = Pal.Ink)
            }
            Spacer(Modifier.height(10.dp))

            if (n > 0) {
                StbTable(
                    ui = ui, camRot = camRot, zoom = zoom, youLabel = youLabel,
                    selected = selected, canPick = canPlay && !mustFinish,
                    onTap = { num -> Haptics.tick(); selected = if (num in selected) selected - num else selected + num },
                )
            }

            Spacer(Modifier.height(12.dp))

            if (ui.canPickMode) {
                Text(stringResource(R.string.stb_mode), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Pal.Ink)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    RaisedButton(
                        stringResource(R.string.stb_mode_until), onClick = { Haptics.tick(); session.pickMode(StbMode.UNTIL_DONE) },
                        tone = if (ui.mode == StbMode.UNTIL_DONE) Tone.Primary else Tone.Light,
                        modifier = Modifier.weight(1f),
                    )
                    RaisedButton(
                        stringResource(R.string.stb_mode_one), onClick = { Haptics.tick(); session.pickMode(StbMode.ONE_FLIP) },
                        tone = if (ui.mode == StbMode.ONE_FLIP) Tone.Primary else Tone.Light,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.stb_tiles), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Pal.Ink)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    RaisedButton(
                        stringResource(R.string.stb_tiles_9), onClick = { Haptics.tick(); session.pickTiles(StbTiles.NINE) },
                        tone = if (ui.tiles == StbTiles.NINE) Tone.Primary else Tone.Light,
                        modifier = Modifier.weight(1f),
                    )
                    RaisedButton(
                        stringResource(R.string.stb_tiles_12), onClick = { Haptics.tick(); session.pickTiles(StbTiles.TWELVE) },
                        tone = if (ui.tiles == StbTiles.TWELVE) Tone.Primary else Tone.Light,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
            } else if (ui.finalWinners == null) {
                Text(
                    stringResource(if (ui.mode == StbMode.ONE_FLIP) R.string.stb_mode_one else R.string.stb_mode_until) +
                        "  ·  " + stringResource(if (ui.tiles == StbTiles.TWELVE) R.string.stb_tiles_12 else R.string.stb_tiles_9),
                    fontSize = 14.sp, color = Pal.Muted,
                )
                Spacer(Modifier.height(8.dp))
            }

            if (ui.finalWinners == null && ui.myTurn) {
                Text(
                    text = if (ui.rolled && landed) stringResource(R.string.stb_total, sum ?: 0) else " ",
                    fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Pal.Ink,
                )
                Spacer(Modifier.height(8.dp))
                when {
                    mustFinish -> {
                        Text(
                            stringResource(if (ui.boxShut) R.string.stb_shut else R.string.stb_no_move),
                            fontSize = 16.sp, color = Pal.Muted, textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(8.dp))
                        RaisedButton(
                            stringResource(R.string.stb_finish), onClick = { Haptics.tick(); session.endTurn() },
                            tone = Tone.Primary, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    !ui.rolled -> RaisedButton(
                        stringResource(R.string.stb_roll), onClick = { session.rollDice() },
                        tone = Tone.Primary, enabled = camSettled, modifier = Modifier.fillMaxWidth(),
                    )
                    else -> RaisedButton(
                        if (picked.isEmpty()) stringResource(R.string.stb_pick, sum ?: 0)
                        else stringResource(R.string.stb_close, picked.joinToString(" + "), pickedSum),
                        onClick = { if (session.closeNumbers(picked)) { Haptics.thud(); selected = emptySet() } },
                        tone = Tone.Primary, enabled = canPlay && legal, modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            Text(stringResource(R.string.stb_scores), fontSize = 18.sp, fontWeight = FontWeight.Black, color = Pal.Ink)
            Spacer(Modifier.height(6.dp))
            ui.nicks.forEachIndexed { seat, nick ->
                val view = ui.boards.getOrNull(seat)
                val name = if (seat == ui.mySeat) youLabel else nick
                val isWinner = ui.finalWinners?.contains(seat) == true
                val marker = when {
                    isWinner -> "★ "
                    seat == ui.currentTurn && ui.finalWinners == null -> "▶ "
                    else -> ""
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        marker + name + if (view?.out == true) "  (" + stringResource(R.string.stb_out) + ")" else "",
                        fontSize = 17.sp, color = Pal.Ink,
                        fontWeight = if (isWinner || seat == ui.currentTurn) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                    )
                    Text(view?.score?.toString() ?: "–", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Pal.Ink)
                }
            }

            ui.finalWinners?.let { w ->
                Spacer(Modifier.height(12.dp))
                val names = w.joinToString(", ") { s -> if (s == ui.mySeat) youLabel else ui.nicks.getOrNull(s).orEmpty() }
                Text(
                    stringResource(R.string.stb_winner, names),
                    fontSize = 20.sp, fontWeight = FontWeight.Black, color = Pal.Teal, textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(20.dp))
            RaisedButton(stringResource(R.string.stb_leave), onClick = onLeave, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * The table: a polygon with one side per player, a box on each side. Seat 0 sits at the bottom of
 * the table; turning the whole table by [camRot] brings any seat to the bottom of the screen.
 * [zoom] 0 = the whole table, 1 = filled by the box at the bottom.
 */
@Composable
internal fun StbTable(
    ui: ShutTheBoxUi,
    camRot: Animatable<Float, *>,
    zoom: Animatable<Float, *>,
    youLabel: String,
    selected: Set<Int>,
    canPick: Boolean,
    onTap: (Int) -> Unit,
) {
    val n = ui.boards.size
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f).clipToBounds()) {
        val tableW = maxWidth
        val density = LocalDensity.current
        val wPx = with(density) { tableW.toPx() }
        val seatR = SEAT_RADIUS * wPx

        // Outer layer: zoom about the focused box (bottom seat), then slide it to the middle.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val z = zoom.value
                    val s = 1f + (ZOOM_IN - 1f) * z
                    transformOrigin = TransformOrigin(0.5f, 0.5f + SEAT_RADIUS)
                    scaleX = s; scaleY = s
                    translationY = -seatR * z
                },
        ) {
            // Inner layer: the whole table turns about its centre.
            Box(Modifier.fillMaxSize().graphicsLayer { rotationZ = camRot.value }) {
                Canvas(Modifier.fillMaxSize()) { drawTable(n) }
                val boardW = tableW * BOARD_W
                val boardH = boardW * BOARD_ASPECT
                for (seat in 0 until n) {
                    val theta = 360f * seat / n
                    val rad = theta * PI.toFloat() / 180f
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .graphicsLayer {
                                translationX = -seatR * sin(rad)
                                translationY = seatR * cos(rad)
                                rotationZ = theta
                            },
                    ) {
                        val name = if (seat == ui.mySeat) youLabel else ui.nicks.getOrNull(seat).orEmpty()
                        StbBoardCanvas(
                            view = ui.boards[seat],
                            label = "$name · ${ui.boards[seat].score}",
                            active = seat == ui.currentTurn && ui.finalWinners == null,
                            mine = seat == ui.mySeat,
                            tiles = ui.tiles,
                            selected = if (seat == ui.mySeat) selected else emptySet(),
                            canPick = canPick && seat == ui.mySeat,
                            onTap = onTap,
                            modifier = Modifier.size(boardW, boardH),
                        )
                    }
                }
            }
        }
    }
}

/** The table top: a regular polygon with one side per player (a square for two). */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTable(players: Int) {
    val sides = if (players < 3) 4 else players
    val w = size.width
    val apothem = (SEAT_RADIUS + BOARD_W * BOARD_ASPECT / 2f + 0.035f) * w
    val circum = apothem / cos(PI.toFloat() / sides)
    val c = Offset(w / 2f, w / 2f)
    // Seats sit at the middle of a side; the first side's middle is straight down.
    val path = Path()
    for (k in 0 until sides) {
        val a = (2f * PI.toFloat() * k / sides) + PI.toFloat() / sides
        val p = Offset(c.x - circum * sin(a), c.y + circum * cos(a))
        if (k == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    path.close()
    drawRect(Color(0xFF2B1B0E), Offset.Zero, size)
    drawPath(path, Brush.radialGradient(listOf(Color(0xFF7A4E26), Color(0xFF5A3517)), c, circum))
    drawPath(path, Color(0xFF2B1B0E), style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.012f))
}

/** One player's box on the table. Tile taps only count on [canPick] boxes. */
@Composable
private fun StbBoardCanvas(
    view: StbBoardView,
    label: String,
    active: Boolean,
    mine: Boolean,
    tiles: Int,
    selected: Set<Int>,
    canPick: Boolean,
    onTap: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val paint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    val folds = (1..tiles).map { num ->
        animateFloatAsState(
            targetValue = if (view.mask and (1 shl (num - 1)) != 0) 1f else 0f,
            animationSpec = tween(420, easing = FastOutSlowInEasing),
            label = "tile$num",
        )
    }
    val tumble = remember { Animatable(1f) }
    LaunchedEffect(view.rollId) {
        if (view.rollId != 0) {
            tumble.snapTo(0f)
            tumble.animateTo(1f, tween(700, easing = LinearOutSlowInEasing))
        }
    }
    Canvas(
        modifier.pointerInput(canPick, view.mask, tiles) {
            if (!canPick) return@pointerInput
            detectTapGestures { p ->
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                val pad = w * 0.05f
                if (p.y > pad + h * 0.40f) return@detectTapGestures
                val num = stbTileAt(p.x, w, tiles)
                if (num != 0 && view.mask and (1 shl (num - 1)) == 0) onTap(num)
            }
        },
    ) {
        drawStbBoard(
            folds = folds, picked = selected, die1 = view.die1, die2 = view.die2, rollId = view.rollId,
            tumble = tumble.value, paint = paint, label = label, active = active, out = view.out, tiles = tiles,
        )
    }
}
