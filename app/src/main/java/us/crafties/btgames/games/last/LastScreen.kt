package us.crafties.btgames.games.last

import androidx.compose.animation.core.Animatable
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Shadow
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import us.crafties.btgames.R
import us.crafties.btgames.ui.AppScreen
import us.crafties.btgames.ui.Haptics
import us.crafties.btgames.ui.Pal
import us.crafties.btgames.ui.RaisedButton
import us.crafties.btgames.ui.RaisedCard
import us.crafties.btgames.ui.Tone
import kotlin.math.abs
import kotlin.math.roundToInt

/** Last!: opponents on top, the table in the middle, my hand along the bottom. */
@Composable
fun LastScreen(
    session: LastSession,
    onLeave: () -> Unit,
) {
    val ui by session.ui.collectAsState()
    /** The wild I tapped and still owe a colour for. */
    var wildIndex by remember { mutableStateOf<Int?>(null) }
    /** The 7 I tapped and still owe a swap target for. */
    var swapIndex by remember { mutableStateOf<Int?>(null) }
    /** The hand card I lifted: tap again (or swipe it up) to play it. */
    var picked by remember { mutableStateOf<Int?>(null) }

    // Where things are on screen, in the root box's own coordinates. The slide animations use
    // these to fly a card from a hand to the pile (or the pile back into a hand).
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    var drawPileCenter by remember { mutableStateOf(Offset.Zero) }
    var pileCenter by remember { mutableStateOf(Offset.Zero) }
    var handCenter by remember { mutableStateOf(Offset.Zero) }
    var handWidth by remember { mutableFloatStateOf(0f) }
    var lastPlayFrom by remember { mutableStateOf(Offset.Zero) }
    val cardCenters = remember { mutableStateMapOf<Int, Offset>() }
    val seatCenters = remember { mutableStateMapOf<Int, Offset>() }
    var flight by remember { mutableStateOf<Flight?>(null) }
    /** Card held at its release point until the queued flight takes over — no one-frame gap. */
    var handoff by remember { mutableStateOf<Pair<LastCard, Offset>?>(null) }
    /** During a multi-card draw the pile thins out card by card instead of all at once. */
    var animDrawLeft by remember(ui.match) { mutableIntStateOf(-1) }
    val flightQueue = remember { Channel<FlightSpec>(Channel.UNLIMITED) }
    var showShuffle by remember { mutableStateOf(false) }
    var rulesFade by remember { mutableFloatStateOf(1f) }
    /** Match whose rules intro has finished; the overlay shows for every newer match. */
    var introDoneFor by remember { mutableStateOf(-1) }
    /** Match whose intro timer is already running, so the effect cannot restart it. */
    var introRunningFor by remember { mutableStateOf(-1) }
    val density = LocalDensity.current
    // Drawn cards stay out of the fan until their own flight lands, so the animation is not
    // spoiled by the finished hand showing them already.
    var revealEnd by remember(ui.moveNo) {
        val ev = ui.event
        val hidden = if (ui.moveNo > 0 && ev?.type == LastEvent.Type.DREW && ev.seat == ui.mySeat && ev.amount > 0) {
            (ui.myHand.size - ev.amount).coerceIn(0, ui.myHand.size)
        } else null
        mutableStateOf<Int?>(hidden)
    }
    val revealing = revealEnd != null
    // Visible from the very first composition of a new deal, before the effect flips the flag.
    val rulesIntroVisible = ui.match > 0 && introDoneFor != ui.match
    // Hide the new top pile card from the very first frame of a PLAYED/DEFENDED move; the flight
    // effect would otherwise set `flight` one frame later and let the card flash in.
    var pileHide by remember(ui.moveNo) {
        val ev0 = ui.event
        mutableStateOf(ui.moveNo > 0 && (ev0?.type == LastEvent.Type.PLAYED || ev0?.type == LastEvent.Type.DEFENDED))
    }

    LaunchedEffect(ui.moveNo, ui.turn) { wildIndex = null; swapIndex = null }
    // A lifted card stays lifted when it stops being playable (it just greys out). It is only
    // let go when the card at that slot is no longer the one I picked, i.e. my hand changed.
    var pickedCard by remember { mutableStateOf<LastCard?>(null) }
    LaunchedEffect(ui.myHand, picked) {
        val i = picked
        if (i != null && ui.myHand.getOrNull(i) != pickedCard) picked = null
    }
    // Haptics: a light tick for every move, a thump when I am hit (Ban, Plus2, caught, slapped),
    // a click when it becomes my turn, when someone calls Last! and when a slap round opens.
    LaunchedEffect(ui.moveNo) {
        if (ui.moveNo <= 0) return@LaunchedEffect
        val ev = ui.event
        val me = ui.mySeat
        when {
            ui.slapOpen -> Unit   // the slap buzz below covers this move
            ev == null -> Haptics.tick()
            ev.type == LastEvent.Type.CAUGHT && ev.target == me -> Haptics.thud()
            ev.type == LastEvent.Type.CAUGHT && ev.seat == me -> Haptics.click()
            ev.type == LastEvent.Type.SLAPPED && me >= 0 && (ev.mask shr me) and 1 != 0 -> Haptics.thud()
            ev.type == LastEvent.Type.PLAYED && ev.target == me && me >= 0 -> Haptics.thud()
            ev.type == LastEvent.Type.CALLED && ev.seat != me -> Haptics.click()
            else -> Haptics.tick()
        }
    }
    LaunchedEffect(ui.myTurn) { if (ui.myTurn && !ui.over) Haptics.click() }
    // Doubling: the bar under the hand drains over the 2 seconds I have to follow up.
    val doubleLeft = remember { Animatable(0f) }
    LaunchedEffect(ui.moveNo, ui.doubleNumber) {
        if (ui.doubleNumber >= 0) {
            doubleLeft.snapTo(1f)
            doubleLeft.animateTo(0f, tween(LastRules.DOUBLE_WINDOW_MS.toInt(), easing = LinearEasing))
        }
    }
    // A 6 with Slap on 6: the longest, strongest buzz in the app. Declared last so it is the one felt.
    LaunchedEffect(ui.slapOpen) { if (ui.slapOpen) Haptics.slap() }
    LaunchedEffect(ui.winner) {
        if (ui.winner >= 0) { if (ui.winner == ui.mySeat) Haptics.win() else Haptics.lose() }
    }

    // Every fresh deal opens with the shuffle-and-deal intro. Wait one frame so the table and
    // hands have been laid out: the intro deals its cards to their real positions.
    LaunchedEffect(ui.match, ui.ready) {
        if (ui.ready && ui.match > 0 && introRunningFor != ui.match) {
            introRunningFor = ui.match
            delay(2_500)
            // Bring the dealing intro up underneath before fading the rules out, so no frame
            // shows the bare table between the two cutscenes.
            showShuffle = true
            val fade = Animatable(1f)
            fade.animateTo(0f, tween(500)) { rulesFade = value }
            introDoneFor = ui.match
        }
    }

    // Flights are queued and played one at a time by a single collector, so a new move can never
    // cancel an animation and leave a card stuck on top.
    LaunchedEffect(handoff) {
        if (handoff == null) return@LaunchedEffect
        delay(HOLD_MS)
        handoff = null          // never let a card stay stuck if the flight never started
    }
    LaunchedEffect(Unit) {
        flightQueue.consumeAsFlow().collect { spec ->
            val progress = Animatable(0f)
            flight = Flight(spec.from, spec.to, spec.card, spec.faceUp, progress, spec.toRotation, spec.toWidth)
            handoff = null
            progress.animateTo(1f, tween(FLY_MS, easing = FastOutSlowInEasing))
            flight = null
            spec.onLanded?.invoke()
        }
    }

    // Build flight specs from the move that just landed.
    LaunchedEffect(ui.moveNo) {
        val ev = ui.event ?: return@LaunchedEffect
        if (ui.moveNo <= 0) return@LaunchedEffect
        if (ev.type != LastEvent.Type.PLAYED && ev.type != LastEvent.Type.DEFENDED) handoff = null
        when (ev.type) {
            LastEvent.Type.PLAYED -> {
                val from = if (ev.seat == ui.mySeat) lastPlayFrom.takeIf { it != Offset.Zero } ?: handCenter
                           else seatCenters[ev.seat] ?: handCenter
                if (from == Offset.Zero || pileCenter == Offset.Zero) { pileHide = false; return@LaunchedEffect }
                if (from != Offset.Zero && pileCenter != Offset.Zero) {
                    val id = ui.playPileIds.lastOrNull() ?: 0
                    val h = id * -1640531535
                    val k = PLAY_PILE_CARD_W / 76f
                    val dx = ((((h ushr 4) % 13) - 6) * k).dp
                    val dy = ((((h ushr 11) % 11) - 5) * k).dp
                    val turn = (((h ushr 17) % 21) - 10).toFloat()
                    val to = pileCenter + Offset(with(density) { dx.toPx() }, with(density) { dy.toPx() })
                    flightQueue.trySend(FlightSpec(from, to, ev.card, true, turn, PLAY_PILE_CARD_W.dp) { pileHide = false })
                }
            }
            LastEvent.Type.DREW -> {
                val to = if (ev.seat == ui.mySeat) handCenter else seatCenters[ev.seat] ?: handCenter
                if (drawPileCenter == Offset.Zero || to == Offset.Zero) return@LaunchedEffect
                val mine = ev.seat == ui.mySeat
                val start = ui.myHand.size - ev.amount
                val count = if (mine) ev.amount else 1
                // The engine has already removed all the cards; show the pile at its pre-draw
                // thickness, then shrink it as each card lands.
                animDrawLeft = ui.drawLeft + ev.amount
                val stepPx = with(density) { (54.dp / 38f).toPx() }
                for (k in 0 until count) {
                    val index = start + k
                    val before = animDrawLeft - k
                    val back = (drawLayers(before) - 1).coerceAtLeast(0)
                    val from = drawPileCenter + Offset(stepPx * back, stepPx * back)
                    val landing = if (mine && handWidth > 0f && handCenter != Offset.Zero) {
                        fanSlot(index, ui.myHand, handWidth, handCenter, density.density)
                    } else to
                    val move = ui.moveNo
                    flightQueue.trySend(
                        // Only my own draws are shown face up; an opponent's card flies back-side up.
                        FlightSpec(from, landing, if (mine) ui.myHand.getOrNull(index) else null, true, 0f, 62.dp) {
                            if (animDrawLeft > ui.drawLeft) animDrawLeft -= 1
                            if (revealEnd != null && ui.moveNo == move) revealEnd = index + 1
                        },
                    )
                    if (k < count - 1) delay(DRAW_GAP_MS)
                }
                flightQueue.trySend(FlightSpec(Offset.Zero, Offset.Zero, null, true, 0f, 62.dp) {
                    if (revealEnd != null) revealEnd = null
                    animDrawLeft = -1
                })
            }
            LastEvent.Type.DEFENDED -> {
                val card = ev.card ?: return@LaunchedEffect
                val from = if (ev.seat == ui.mySeat) lastPlayFrom.takeIf { it != Offset.Zero } ?: handCenter
                           else seatCenters[ev.seat] ?: handCenter
                if (from == Offset.Zero || pileCenter == Offset.Zero) { pileHide = false; return@LaunchedEffect }
                val stack = card.kind == LastKind.PLUS2 || card.kind == LastKind.COLOR_PLUS2_SQ
                flightQueue.trySend(FlightSpec(from, pileCenter, card, true, 0f, PLAY_PILE_CARD_W.dp) { pileHide = false })
            }
            else -> Unit
        }
    }

    /** A +2/+4 is aimed at me: the cards that can answer it are played like any other card. */
    val defending = ui.defenseOpen && !ui.over && ui.defenseTarget == ui.mySeat

    /** Lift a card on the first tap; the second tap (or a swipe up) plays it. */
    fun playCard(index: Int): Boolean {
        if (revealing) return false
        val card = ui.myHand.getOrNull(index) ?: return false
        if (defending) {
            // Ban / Spin / a stackable attack card: no colour or swap prompt, just play it.
            if (index !in ui.defenseCards) return false
            lastPlayFrom = cardCenters[index] ?: handCenter
            picked = null
            handoff = card to lastPlayFrom
            session.defend(index)
            return true
        }
        if (!ui.myTurn || ui.playable.getOrNull(index) != true) return false
        // Start from where the card actually is: its dragged position if it was dropped on the
        // pile, otherwise its slot in the fan.
        lastPlayFrom = cardCenters[index] ?: handCenter
        if (card.kind.isWild) {
            wildIndex = index
            picked = null
            return false   // stays in hand until a colour is chosen
        } else if (card.kind == LastKind.NUMBER && card.number == 7 && ui.rules.zeroSeven) {
            // The special 7: it plays as one move, but first I must name whose hand to take.
            swapIndex = index
            picked = null
            return false
        } else {
            picked = null
            handoff = card to lastPlayFrom
            session.play(index, null)
            return true
        }
    }


    AppScreen(title = stringResource(R.string.g_last_title), onBack = onLeave) {
      Box(
          Modifier
              .fillMaxSize()
              .onGloballyPositioned {
                  rootOrigin = it.positionInRoot()
                  rootSize = it.size
              },
      ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (ui.rulesOpen) {
                RulesPanel(ui.rules, onChange = { session.setRules(it) }, onDeal = { session.startDeal() })
                Spacer(Modifier.height(12.dp))
                RaisedButton(stringResource(R.string.last_leave), onClick = onLeave, modifier = Modifier.fillMaxWidth())
                return@Column
            }

            // ---- the other players -----------------------------------------------------------
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                ui.nicks.forEachIndexed { seat, nick ->
                    if (seat != ui.mySeat) {
                        Box(
                            Modifier.onGloballyPositioned {
                                seatCenters[seat] = it.boundsInRoot().center
                            },
                        ) {
                            OpponentChip(
                                nick = nick,
                                count = ui.counts.getOrNull(seat) ?: 0,
                                active = ui.ready && !ui.over && ui.turn == seat,
                                catchable = ui.ready && !ui.over && seat in ui.catchable,
                                onCatch = { Haptics.tick(); session.catchPlayer(seat) },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            // ---- the table: a big played pile, the draw pile set aside beside it --------------
            val canDraw = ui.myTurn && !ui.drewPlayable && ui.doubleNumber < 0 && !ui.slapOpen && !revealing
            Box(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LastPlayPile(
                        ui,
                        cardWidth = PLAY_PILE_CARD_W.dp,
                        hideTop = pileHide,
                        onTopPositioned = { pileCenter = it },
                        canSlap = ui.canSlap,
                        onSlap = { Haptics.tick(); session.slap() },
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(22.dp).clip(CircleShape).background(lastPaint(ui.colour))
                                .border(2.dp, Pal.Chalk, CircleShape),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (ui.direction > 0) "↻" else "↺", fontSize = 20.sp, color = Pal.Muted)
                    }
                }
                Column(
                    modifier = Modifier.align(Alignment.CenterStart),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LastDrawPile(
                        if (animDrawLeft >= 0) animDrawLeft else ui.drawLeft,
                        cardWidth = 54.dp, canDraw = canDraw,
                        onDraw = { session.draw() }, onPositioned = { drawPileCenter = it },
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.last_draw_n, ui.drawLeft), fontSize = 12.sp, color = Pal.Muted)
                }
            }
            // No status line on the phone: it only cluttered the table. Big hits (Ban, +2, +4)
            // flash on screen instead, see BigCallout; the table screen still shows the line.
            if (ui.slapOpen && ui.slapped) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.last_slapped), fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    color = Pal.Teal, textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(16.dp))

            // ---- my controls -----------------------------------------------------------------
            if (ui.ready && !ui.over && ui.myTurn && !ui.slapOpen && !ui.defenseOpen && !revealing) {
                val swap = swapIndex
                if (swap != null) {
                    Text(stringResource(R.string.last_hint_swap), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Pal.Ink)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    ) {
                        ui.nicks.forEachIndexed { seat, nick ->
                            if (seat != ui.mySeat) RaisedButton(
                                text = "$nick · ${ui.counts.getOrNull(seat) ?: 0}",
                                onClick = { session.exchange(swap, seat); swapIndex = null; picked = null },
                                tone = Tone.Light,
                            )
                        }
                    }
                } else {
                val pick = wildIndex
                if (pick != null) {
                    Text(stringResource(R.string.last_pick_colour), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Pal.Ink)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (c in LastColor.entries) {
                            Box(
                                Modifier
                                    .size(54.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(lastPaint(c))
                                    .border(3.dp, Pal.Chalk, RoundedCornerShape(14.dp))
                                    .clickable { session.play(pick, c); wildIndex = null; picked = null },
                            )
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        if (ui.drewPlayable && !ui.rules.forcePlay) {
                            RaisedButton(
                                stringResource(R.string.last_pass), onClick = { session.pass() },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    if (ui.doubleNumber >= 0) {
                        // Doubling: the same number follows on, or the turn ends when this bar runs out.
                        Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Pal.Line)) {
                            Box(Modifier.fillMaxWidth(doubleLeft.value).height(5.dp).background(Pal.Teal))
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
                }
            }

            // ---- my hand: fanned out the way a hand holds cards ------------------------------
            if (ui.ready) {
                val hand = revealEnd?.let { end ->
                    if (end < ui.myHand.size) ui.myHand.take(end) else ui.myHand
                } ?: ui.myHand
                val order = hand.indices.sortedWith(
                    compareBy({ hand[it].color?.code ?: 4 }, { hand[it].kind.code }, { hand[it].number }),
                )
                // Keep the lift for as long as the same card is still in my hand, playable or not.
                val sel = picked?.takeIf { it in hand.indices && hand[it] == pickedCard }
                val selPos = sel?.let { order.indexOf(it) } ?: -1
                BoxWithConstraints(
                    Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                ) {
                    val n = order.size
                    val cardW = when {
                        n <= 4 -> 66.dp
                        n <= 7 -> 60.dp
                        n <= 10 -> 54.dp
                        else -> 48.dp
                    }
                    val cardH = cardW * 1.45f
                    val gapExtra = 16.dp
                    val usable = (maxWidth - cardW).coerceAtLeast(0.dp)
                    val extraGaps = if (selPos >= 0) 2 else 0
                    val rawStep = if (n > 1) minOf(cardW * 0.62f, usable / (n - 1)) else 0.dp
                    val step = if (n > 1) {
                        minOf(rawStep, (usable - gapExtra * extraGaps).coerceAtLeast(0.dp) / (n - 1))
                    } else 0.dp
                    val xs = FloatArray(n)
                    var cursor = 0f
                    for (p in 0 until n) {
                        if (p > 0) {
                            cursor += step.value
                            if (selPos >= 0 && (p - 1 == selPos || p == selPos)) cursor += gapExtra.value
                        }
                        xs[p] = cursor
                    }
                    val totalW = (xs.lastOrNull() ?: 0f) + cardW.value
                    val start = (maxWidth.value - totalW) / 2f
                    val half = if (n > 1) (n - 1) / 2f else 0f
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(cardH + 42.dp)
                            .onGloballyPositioned {
                                handCenter = it.boundsInRoot().center
                                handWidth = it.size.width.toFloat()
                            },
                    ) {
                        // Layering is done with zIndex inside HandCard, so a card keeps its place in
                        // the composition while it lifts and settles (no reorder mid-animation).
                        for (p in 0 until n) {
                            val isSel = p == selPos
                            val i = order[p]
                            val card = hand[i]
                            val ok = if (defending) !revealing && i in ui.defenseCards
                                     else ui.myTurn && !revealing && ui.playable.getOrNull(i) == true
                            val centered = p - half
                            val t = if (n > 1) centered / half else 0f
                            val yArc = t * t * 12f
                            val y = yArc - (if (isSel) 22f else if (ok) 4f else 0f)
                            val handLive = wildIndex == null && swapIndex == null
                            // Key by the card's hand index, not its fan position: when a card
                            // leaves, the survivors keep their identity and animate into the
                            // closing gap instead of being reused as a different card.
                            // Identity key: card + which copy of it this is. Keying by slot let the
                            // next card inherit the played card's drag offset and hover at the pile.
                            val copy = hand.take(i).count { it == card }
                            key(card, copy) {
                                HandCard(
                                    card = card,
                                    width = cardW,
                                    x = (start + xs[p]).dp,
                                    y = y.dp,
                                    rotation = if (isSel) 0f else (centered * 3.4f).coerceIn(-15f, 15f),
                                    scale = if (isSel) 1.10f else 1f,
                                    selected = isSel,
                                    enabled = ok && handLive,
                                    dimmed = (ui.myTurn || defending) && !revealing && (!ok || !handLive),
                                    onTap = {
                                        if (picked == i) playCard(i) else { picked = i; pickedCard = hand[i]; Haptics.tick() }
                                    },
                                    onSwipeUp = { if (picked == i) playCard(i) },
                                    dropTarget = pileCenter,
                                    onDropOnPile = {
                                        picked = i
                                        pickedCard = hand[i]
                                        playCard(i)   // false for a wild / 7 (stays in hand) or a rejected play
                                    },
                                    onPosition = { cardCenters[i] = it },
                                    epoch = ui.moveNo,
                                )
                            }
                        }
                        // LAST! rides on the corner of the last card instead of the screen corner.
                        val lastFade by animateFloatAsState(
                            if (ui.showLast) 1f else 0f, tween(160), label = "lastFade",
                        )
                        if (lastFade > 0.01f) {
                            LastCircle(
                                armed = ui.lastArmed,
                                size = 64.dp,
                                onClick = { Haptics.tick(); session.callLast() },
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .offset(
                                        x = (start + (xs.lastOrNull() ?: 0f) + cardW.value - 20f).dp,
                                        y = 0.dp,
                                    )
                                    .zIndex(2f)
                                    .alpha(lastFade),
                            )
                        }
                    }
                }
            }

            if (ui.over && !ui.askedAgain) {
                Spacer(Modifier.height(8.dp))
                RaisedButton(
                    stringResource(if (ui.isHost) R.string.last_again_host else R.string.last_again),
                    onClick = { session.playAgain() },
                    tone = Tone.Primary, modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(12.dp))
            RaisedButton(stringResource(R.string.last_leave), onClick = onLeave, modifier = Modifier.fillMaxWidth())
        }
        handoff?.let { (card, at) ->
            Box(
                Modifier
                    .offset {
                        val hx = with(density) { 62.dp.toPx() }
                        IntOffset((at.x - rootOrigin.x - hx / 2f).roundToInt(),
                            (at.y - rootOrigin.y - hx * 0.725f).roundToInt())
                    }
                    .zIndex(10f),
            ) { LastCardView(card, width = 62.dp) }
        }
        flight?.let { FlyingCard(it, rootOrigin) }
        BigCallout(ui)
        if (showShuffle) {
            ShuffleIntro(
                match = ui.match,
                // The play pile is the table's centre; the draw pile sits off to the side, so
                // averaging the two put the riffle visibly left of centre.
                deck = if (pileCenter != Offset.Zero) {
                    pileCenter
                } else if (drawPileCenter != Offset.Zero) {
                    drawPileCenter
                } else {
                    Offset(rootSize.width / 2f, rootSize.height * 0.42f) + rootOrigin
                },
                hand = handCenter,
                seats = seatCenters,
                origin = rootOrigin,
                mySeat = ui.mySeat,
                playerCount = ui.nicks.size.coerceAtLeast(2),
                rootSize = rootSize,
                onDone = { showShuffle = false },
            )
        }
        if (rulesIntroVisible) RulesIntro(ui.rules, rulesFade)
      }
    }
}

/** The root-space centre of [index] in the hand fan, using the same maths as the fan itself. */
private fun fanSlot(index: Int, hand: List<LastCard>, widthPx: Float, center: Offset, density: Float): Offset {
    val shown = hand.take(index + 1)
    val n = shown.size
    val order = shown.indices.sortedWith(compareBy({ shown[it].color?.code ?: 4 }, { shown[it].kind.code }, { shown[it].number }))
    val p = order.indexOf(index).coerceAtLeast(0)
    val cardW = when {
        n <= 4 -> 66f
        n <= 7 -> 60f
        n <= 10 -> 54f
        else -> 48f
    } * density
    val gapExtra = 16f * density
    val usable = (widthPx - cardW).coerceAtLeast(0f)
    val step = if (n > 1) minOf(cardW * 0.62f, (usable - gapExtra * 2f).coerceAtLeast(0f) / (n - 1)) else 0f
    var cursor = 0f
    for (i in 0 until n) {
        if (i > 0) cursor += step
        if (i == p) break
    }
    val totalW = step * (n - 1).coerceAtLeast(0) + cardW
    val start = (widthPx - totalW) / 2f
    val half = if (n > 1) (n - 1) / 2f else 0f
    val centred = p - half
    val t = if (n > 1) centred / half else 0f
    val yArc = t * t * 12f * density
    return Offset(
        center.x - widthPx / 2f + start + cursor + cardW / 2f,
        center.y - 21f * density + yArc,
    )
}

/** A card mid-slide: a played card flying to the pile, or a drawn card flying back to a hand. */
private data class FlightSpec(
    val from: Offset,
    val to: Offset,
    val card: LastCard? = null,
    val faceUp: Boolean = true,
    val toRotation: Float = 0f,
    val toWidth: Dp = 62.dp,
    val onLanded: (() -> Unit)? = null,
)

private data class Flight(
    val from: Offset,
    val to: Offset,
    val card: LastCard?,
    val faceUp: Boolean,
    val progress: Animatable<Float, *>,
    val toRotation: Float = 0f,
    val toWidth: Dp = 62.dp,
)

private const val FLY_MS = 340

/**
 * How long a card dropped on the pile is held there while the host confirms the move. A client
 * only sees its card leave the hand once the host's answer has come back over Bluetooth, which
 * takes longer than a flight; a shorter hold sent the card back too early.
 */
private const val HOLD_MS = 2500L

/** Play-pile card width in dp; the jitter is tuned around a 76dp card. */
private const val PLAY_PILE_CARD_W = 116f

/** Pause between the cards of a multi-card draw, so they land one after another. */
private const val DRAW_GAP_MS = 110L

private fun drawLayers(count: Int) = when {
    count <= 0 -> 0
    count <= 5 -> 1
    count <= 20 -> 2
    else -> 3
}

@Composable
private fun FlyingCard(f: Flight, origin: Offset) {
    val p = f.progress.value
    // Positions are measured against the composition root; the flight itself is drawn inside the
    // screen's root box, so take that box's own offset back off.
    val center = lerp(f.from - origin, f.to - origin, p)
    val density = LocalDensity.current
    val width = lerp(62f, f.toWidth.value, p).dp
    val w = with(density) { width.toPx() }
    val h = w * 1.45f
    Box(
        Modifier
            .offset { IntOffset((center.x - w / 2f).roundToInt(), (center.y - h / 2f).roundToInt()) }
            .zIndex(10f)
            .graphicsLayer { rotationZ = lerp(-12f, f.toRotation, p) },
    ) {
        if (f.card != null) LastCardView(f.card, width = width) else LastCardBack(width = width)
    }
}

/**
 * One card in the fan. The first tap lifts it; a second tap, or a swipe up towards the pile,
 * plays it. Offsets are animated so the whole hand opens up around the lifted card.
 */
@Composable
private fun HandCard(
    card: LastCard,
    width: Dp,
    x: Dp,
    y: Dp,
    rotation: Float,
    scale: Float,
    selected: Boolean,
    enabled: Boolean,
    dimmed: Boolean,
    onTap: () -> Unit,
    onSwipeUp: () -> Unit,
    dropTarget: Offset,
    onDropOnPile: () -> Boolean,
    onPosition: (Offset) -> Unit,
    epoch: Int = 0,
) {
    val tap by rememberUpdatedState(onTap)
    val swipe by rememberUpdatedState(onSwipeUp)
    val dropOnPile by rememberUpdatedState(onDropOnPile)
    val position by rememberUpdatedState(onPosition)
    val enabledNow by rememberUpdatedState(enabled)
    val selectedNow by rememberUpdatedState(selected)
    // pointerInput(Unit) never restarts, so it must read the pile position through State;
    // a plain parameter would stay frozen at whatever it was when this card first composed.
    val dropTargetNow by rememberUpdatedState(dropTarget)
    var currentCenter by remember { mutableStateOf(Offset.Zero) }

    // Springs instead of fixed-length tweens: when a target changes mid-flight (tap, tap away,
    // hand reshuffles) the card keeps its velocity and bends toward the new target instead of
    // restarting a 180ms curve from wherever it happened to be.
    val moveSpec = spring<Dp>(dampingRatio = 0.78f, stiffness = 520f)
    val ax by animateDpAsState(x, moveSpec, label = "cardX")
    val ay by animateDpAsState(y, moveSpec, label = "cardY")
    val rot by animateFloatAsState(rotation, spring(dampingRatio = 0.8f, stiffness = 520f), label = "cardRot")
    val sc by animateFloatAsState(scale, spring(dampingRatio = 0.7f, stiffness = 650f), label = "cardScale")
    val lift by animateDpAsState(if (selected) 12.dp else 0.dp, tween(140), label = "cardLift")
    // Unplayable cards grey out, lifted or not.
    val fade by animateFloatAsState(if (dimmed) 0.45f else 1f, tween(160), label = "cardFade")

    val density = LocalDensity.current
    var dragX by remember { mutableStateOf(0.dp) }
    var dragY by remember { mutableStateOf(0.dp) }
    var isDragging by remember { mutableStateOf(false) }
    /** Dropped on the pile and waiting for the hand to drop it; falls back if that never comes. */
    var parked by remember { mutableStateOf(false) }
    /** Next return to the fan is instant (a stale parked offset must never glide back). */
    var snapBack by remember { mutableStateOf(false) }
    fun settle() { isDragging = false; parked = false; dragX = 0.dp; dragY = 0.dp }
    // Any new move: a card still parked at the pile belongs to a play that already happened.
    LaunchedEffect(epoch) {
        if (parked) { snapBack = true; settle() }
    }
    LaunchedEffect(snapBack) { if (snapBack) { delay(80); snapBack = false } }
    DisposableEffect(Unit) { onDispose { settle() } }
    // Safety net: a card parked at the drop point must not hang in the air if the play never
    // lands (rejected move, dropped message). It glides back into the fan instead.
    LaunchedEffect(parked) {
        if (parked) {
            delay(HOLD_MS)
            settle()
        }
    }
    val dragSpec = if (isDragging || snapBack) snap<Dp>() else spring(dampingRatio = 0.8f, stiffness = 600f)
    val shownDragX by animateDpAsState(dragX, dragSpec, label = "dragX")
    val shownDragY by animateDpAsState(dragY, dragSpec, label = "dragY")
    val shape = RoundedCornerShape(width * 0.14f)
    // Stay on top for as long as the card is visibly raised or displaced, not just while the
    // flag is set, so it does not slip under its neighbours halfway through settling.
    val raised = selected || isDragging || parked || lift > 0.5.dp ||
        shownDragX.value != 0f || shownDragY.value != 0f
    Box(
        Modifier
            .offset(x = ax + shownDragX, y = ay + shownDragY)
            .zIndex(if (raised) 1f else 0f)
            .graphicsLayer {
                rotationZ = rot
                scaleX = sc
                scaleY = sc
                // A parked card is being played: the handoff card is drawing it at the pile.
                alpha = if (parked) 0f else fade
                shadowElevation = lift.toPx()
                this.shape = shape
                clip = false
                transformOrigin = TransformOrigin(0.5f, 1f)
            }
            .onGloballyPositioned {
                currentCenter = it.boundsInRoot().center
                position(currentCenter)
            }
            .pointerInput(Unit) {
                val swipeUp = 48.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!enabledNow || parked) return@awaitEachGesture
                    var totalX = 0f
                    var totalY = 0f
                    var dragging = false
                    var finished = false
                    val slop = viewConfiguration.touchSlop
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val delta = change.positionChange()
                            totalX += delta.x
                            totalY += delta.y
                            if (abs(totalX) > slop || abs(totalY) > slop) {
                                if (!dragging) isDragging = true
                                dragging = true
                                dragX += with(density) { delta.x.toDp() }
                                dragY += with(density) { delta.y.toDp() }
                            }
                            if (dragging) change.consume()
                        }
                        val releasedAt = currentCenter
                        val target = dropTargetNow
                        val overPile = dragging && target != Offset.Zero && releasedAt != Offset.Zero &&
                            (releasedAt - target).getDistance() < 110.dp.toPx()
                        // Greyed out mid-drag: it stays under the finger, but cannot be played.
                        if (overPile && enabledNow && dropOnPile()) {
                            // Really being played: hold at the drop point so the flight continues
                            // from there; the safety net above covers a play that never lands.
                            isDragging = false
                            parked = true
                            finished = true
                            return@awaitEachGesture
                        }
                        settle()
                        finished = true
                        when {
                            !dragging -> if (enabledNow) tap()
                            totalY < -swipeUp && abs(totalY) > abs(totalX) && selectedNow -> swipe()
                        }
                    } finally {
                        // Cancelled (scroll stole it, card left composition, window lost focus):
                        // never leave the card displaced and flagged as dragging.
                        if (!finished) settle()
                    }
                }
            },
    ) {
        LastCardView(card, width = width)
    }
}

/** The round LAST! button: press it before playing down to one card. */
@Composable
private fun LastCircle(
    armed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 76.dp,
) {
    val pulse by rememberInfiniteTransition(label = "lastCircle").animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "lastCirclePulse",
    )
    Box(
        modifier
            .size(size)
            .shadow(8.dp, CircleShape)
            .clip(CircleShape)
            .background(if (armed) Pal.Teal else Pal.Red)
            .border((2 + 2 * pulse).dp, Pal.Chalk.copy(alpha = 0.35f + 0.65f * pulse), CircleShape)
            .clickable(enabled = !armed, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(R.string.last_btn), fontSize = (size.value * 0.25f).sp,
            fontWeight = FontWeight.Black, color = Color.White, textAlign = TextAlign.Center,
        )
    }
}

/** The host's rule picks: icon tiles only, no explanation text. */
@Composable
internal fun RulesPanel(rules: LastRules, onChange: (LastRules) -> Unit, onDeal: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.last_rules_title), fontSize = 20.sp, fontWeight = FontWeight.Black, color = Pal.Ink)
        Spacer(Modifier.height(12.dp))
        Text(
            "${stringResource(R.string.last_rule_cards)}: ${rules.handSize}",
            fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Pal.Ink,
        )
        androidx.compose.material3.Slider(
            value = rules.handSize.toFloat(),
            onValueChange = { onChange(rules.copy(handSize = it.roundToInt())) },
            valueRange = LastRules.MIN_HAND.toFloat()..LastRules.MAX_HAND.toFloat(),
            steps = LastRules.MAX_HAND - LastRules.MIN_HAND - 1,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )
        Spacer(Modifier.height(12.dp))
        RulesTiles(rules, onChange)
        Spacer(Modifier.height(20.dp))
        RaisedButton(
            stringResource(R.string.last_rule_deal), onClick = onDeal,
            tone = Tone.Primary, modifier = Modifier.fillMaxWidth().height(60.dp),
        )
    }
}

/** The same tiles, shown to everyone for three seconds when a round starts. */
@Composable
internal fun RulesIntro(rules: LastRules, alpha: Float) {
    Box(Modifier.fillMaxSize().alpha(alpha).background(Pal.Paper), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.last_rules_title), fontSize = 24.sp, fontWeight = FontWeight.Black, color = Pal.Ink)
            Spacer(Modifier.height(16.dp))
            RulesTiles(rules, null)
        }
    }
}

@Composable
private fun RulesTiles(rules: LastRules, onChange: ((LastRules) -> Unit)?) {
    val tiles = listOf(
        RuleSpec(LastRuleIcon.SLAP_SIX, stringResource(R.string.last_rule_slap)) { rules.copy(slapSix = !rules.slapSix) } to rules.slapSix,
        RuleSpec(LastRuleIcon.DRAW_ONE, stringResource(R.string.last_rule_draw_one)) { rules.copy(draw = DrawRule.ONE) } to (rules.draw == DrawRule.ONE),
        RuleSpec(LastRuleIcon.DRAW_MATCH, stringResource(R.string.last_rule_draw_match)) { rules.copy(draw = DrawRule.UNTIL_MATCH) } to (rules.draw == DrawRule.UNTIL_MATCH),
        RuleSpec(LastRuleIcon.DRAW_TWO, stringResource(R.string.last_rule_draw_two)) { rules.copy(draw = DrawRule.TWO) } to (rules.draw == DrawRule.TWO),
        RuleSpec(LastRuleIcon.FORCE, stringResource(R.string.last_rule_force)) { rules.copy(forcePlay = !rules.forcePlay) } to rules.forcePlay,
        RuleSpec(LastRuleIcon.DOUBLE, stringResource(R.string.last_rule_double)) { rules.copy(doubling = !rules.doubling) } to rules.doubling,
        RuleSpec(LastRuleIcon.ZERO_SEVEN, stringResource(R.string.last_rule_special)) { rules.copy(zeroSeven = !rules.zeroSeven) } to rules.zeroSeven,
        RuleSpec(LastRuleIcon.DEFENSE, stringResource(R.string.last_rule_defense)) { rules.copy(defense = !rules.defense) } to rules.defense,
        RuleSpec(LastRuleIcon.STACK_LESSER, stringResource(R.string.last_rule_stack)) { rules.copy(stackLesser = !rules.stackLesser) } to rules.stackLesser,
    )
    for (row in tiles.chunked(3)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            for ((spec, enabled) in row) RuleTile(
                icon = spec.icon,
                label = spec.label,
                enabled = enabled,
                onClick = if (onChange != null) {{ onChange(spec.next()) }} else null,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

private data class RuleSpec(
    val icon: LastRuleIcon,
    val label: String,
    val next: () -> LastRules,
)

@Composable
private fun OpponentChip(nick: String, count: Int, active: Boolean, catchable: Boolean, onCatch: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(nick, fontSize = 16.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, color = Pal.Ink, maxLines = 1)
            // One card left and no Last! yet: tap the "!" before the three seconds are up.
            if (catchable) {
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Pal.Red)
                        .clickable(onClick = onCatch),
                    contentAlignment = Alignment.Center,
                ) { Text("!", fontSize = 19.sp, fontWeight = FontWeight.Black, color = Color.White) }
            }
        }
        if (count > 0) {
            Spacer(Modifier.height(6.dp))
            OpponentHand(count)
        }
        if (count == 1) {
            Text(stringResource(R.string.last_card_one), fontSize = 14.sp, fontWeight = FontWeight.Black, color = Pal.Red)
        } else {
            Text(stringResource(R.string.last_cards_n, count), fontSize = 14.sp, color = Pal.Muted)
        }
    }
}

/** The other players' cards, face down: a small fan of the LAST! backs. */
@Composable
internal fun OpponentHand(count: Int) {
    val shown = count.coerceAtMost(5)
    val w = 36.dp
    val step = 10.dp
    Box(
        Modifier
            .width(w + step * (shown - 1).toFloat())
            .height(w * 1.45f + 6.dp),
    ) {
        for (k in 0 until shown) {
            LastCardBack(
                width = w,
                modifier = Modifier
                    .offset(x = step * k.toFloat(), y = (k * 0.7f).dp)
                    .graphicsLayer { rotationZ = (k - (shown - 1) / 2f) * 4f },
            )
        }
    }
}

@Composable
internal fun nameOf(ui: LastUi, seat: Int): String =
    if (seat == ui.mySeat) stringResource(R.string.last_you) else ui.nicks.getOrNull(seat).orEmpty()

@Composable
internal fun cardName(card: LastCard): String {
    val base = when (card.kind) {
        LastKind.NUMBER -> card.number.toString()
        LastKind.BAN -> stringResource(R.string.last_card_ban)
        LastKind.SPIN -> stringResource(R.string.last_card_spin)
        LastKind.PLUS2 -> stringResource(R.string.last_card_plus2)
        LastKind.COLOR_CHANGE -> return stringResource(R.string.last_card_cc)
        LastKind.COLOR_PLUS2_SQ -> return stringResource(R.string.last_card_cc_sq)
    }
    val colour = when (card.color) {
        LastColor.RED -> stringResource(R.string.last_colour_red)
        LastColor.YELLOW -> stringResource(R.string.last_colour_yellow)
        LastColor.GREEN -> stringResource(R.string.last_colour_green)
        LastColor.BLUE -> stringResource(R.string.last_colour_blue)
        null -> ""
    }
    return stringResource(R.string.last_card_fmt, colour, base)
}

/** One line on what just happened, for everyone at the table. */
@Composable
internal fun eventLine(ui: LastUi): String {
    val ev = ui.event ?: return ""
    val who = nameOf(ui, ev.seat)
    return when (ev.type) {
        LastEvent.Type.PLAYED -> {
            val card = ev.card
            val played = stringResource(R.string.last_ev_played, who, if (card != null) cardName(card) else "")
            val extra = when {
                ev.target >= 0 && ev.amount > 0 -> stringResource(R.string.last_ev_drew, nameOf(ui, ev.target), ev.amount)
                ev.target >= 0 -> stringResource(R.string.last_ev_banned, nameOf(ui, ev.target))
                card != null && card.kind == LastKind.SPIN -> stringResource(R.string.last_ev_reversed)
                card != null && card.kind == LastKind.NUMBER && card.number == 0 && ui.rules.zeroSeven ->
                    stringResource(R.string.last_ev_rotated)
                else -> ""
            }
            if (extra.isEmpty()) played else "$played · $extra"
        }
        LastEvent.Type.DREW -> stringResource(R.string.last_ev_drew_one, who)
        LastEvent.Type.PASSED -> stringResource(R.string.last_ev_passed, who)
        LastEvent.Type.DOUBLED -> if (ev.amount > 1) stringResource(R.string.last_ev_double, who, ev.amount) else ""
        LastEvent.Type.CALLED -> stringResource(R.string.last_ev_called, who)
        LastEvent.Type.CAUGHT -> stringResource(R.string.last_ev_caught, who, nameOf(ui, ev.target), ev.amount)
        LastEvent.Type.SLAPPED ->
            if (Integer.bitCount(ev.mask) <= 1) stringResource(R.string.last_ev_slap_one, who, ev.amount)
            else stringResource(R.string.last_ev_slap_many, Integer.bitCount(ev.mask), ev.amount)
        LastEvent.Type.EXCHANGED -> stringResource(R.string.last_ev_exchanged, who, nameOf(ui, ev.target))
        LastEvent.Type.DEFENDED -> ""   // the defence cards speak for themselves
    }
}


/**
 * The draw pile: a few cards' thickness that shrinks as it runs down, and an empty outline when
 * nothing is left. Shared by the phone screen and the table screen. [onDraw] is null when the pile
 * is only for show (the table screen).
 */
@Composable
internal fun LastDrawPile(
    drawLeft: Int,
    cardWidth: Dp,
    canDraw: Boolean,
    onDraw: (() -> Unit)?,
    onPositioned: (Offset) -> Unit,
) {
    val layers = drawLayers(drawLeft)
    val height = cardWidth * 1.45f
    val shape = RoundedCornerShape(cardWidth * 0.14f)
    val step = cardWidth / 38f
    Box(Modifier.size(width = cardWidth + step * 3f, height = height + step * 3f)) {
        if (layers == 0) {
            Box(
                Modifier
                    .size(cardWidth, height)
                    .onGloballyPositioned { onPositioned(it.boundsInRoot().center) }
                    .border(2.dp, Pal.Muted, shape),
            )
        } else {
            for (k in layers - 1 downTo 1) {
                LastCardBack(width = cardWidth, modifier = Modifier.offset(x = step * k.toFloat(), y = step * k.toFloat()))
            }
            LastCardBack(
                width = cardWidth,
                modifier = Modifier
                    .onGloballyPositioned { onPositioned(it.boundsInRoot().center) }
                    .border(
                        if (canDraw) 3.dp else 0.dp,
                        if (canDraw) Pal.Teal else Pal.Court,
                        shape,
                    )
                    .then(if (onDraw != null) Modifier.clickable(enabled = canDraw, onClick = onDraw) else Modifier),
            )
        }
    }
}

/**
 * Played cards thrown on a pile like real cards: each one lands a little off centre and a little
 * turned. The offsets come from the card's running number, so they never change and every phone
 * shows the same pile. Newest on top.
 */
@Composable
internal fun LastPlayPile(
    ui: LastUi,
    cardWidth: Dp,
    onTopPositioned: (Offset) -> Unit,
    hideTop: Boolean = false,
    canSlap: Boolean = false,
    onSlap: (() -> Unit)? = null,
) {
    val pile = ui.playPile
    val k = cardWidth.value / 76f   // the jitter below is tuned for a 76dp card
    Box(
        Modifier
            .size(width = cardWidth + (16 * k).dp, height = cardWidth * 1.45f + (14 * k).dp)
            .onGloballyPositioned { onTopPositioned(it.boundsInRoot().center) },
        contentAlignment = Alignment.Center,
    ) {
        for ((i, card) in pile.withIndex()) {
            if (hideTop && i == pile.lastIndex) continue
            val h = (ui.playPileIds.getOrElse(i) { i }) * -1640531535
            val dx = ((((h ushr 4) % 13) - 6) * k).dp
            val dy = ((((h ushr 11) % 11) - 5) * k).dp
            val turn = (((h ushr 17) % 21) - 10).toFloat()
            Box(
                modifier = Modifier
                    .offset(x = dx, y = dy)
                    .graphicsLayer { rotationZ = turn },
            ) {
                LastCardView(card, width = cardWidth)
            }
        }
        // Slap on the 6 itself: the top card is the target, ringed in red until everyone has slapped.
        if (canSlap && onSlap != null) {
            val pulse by rememberInfiniteTransition(label = "slapPile").animateFloat(
                initialValue = 0.35f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(420), RepeatMode.Reverse), label = "slapPilePulse",
            )
            val shape = RoundedCornerShape(cardWidth * 0.14f)
            Box(
                Modifier
                    .matchParentSize()
                    .clip(shape)
                    .border((3 + 3 * pulse).dp, Pal.Red.copy(alpha = pulse), shape)
                    .clickable(onClick = onSlap),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Pal.Red.copy(alpha = 0.92f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        stringResource(R.string.last_slap), fontSize = 18.sp,
                        fontWeight = FontWeight.Black, color = Color.White,
                    )
                }
            }
        }
    }
}


/**
 * A big word that flashes in the middle of the screen when something hits me: Ban, +2 or +4 from
 * a card, +2 from a catch or a slap. It pops in, holds for a moment and fades away. Only the player
 * who is hit sees it, and it never takes a tap.
 */
@Composable
private fun BigCallout(ui: LastUi) {
    val ban = stringResource(R.string.last_callout_ban)
    var trigger by remember { mutableStateOf<Pair<Int, String>?>(null) }
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(0.5f) }

    LaunchedEffect(ui.moveNo) {
        val ev = ui.event ?: return@LaunchedEffect
        val me = ui.mySeat
        if (ui.moveNo <= 0 || me < 0) return@LaunchedEffect
        val word = when {
            ev.type == LastEvent.Type.PLAYED && ev.target == me -> when (ev.card?.kind) {
                LastKind.BAN, LastKind.SPIN -> ban        // a Spin against one opponent bans them
                LastKind.PLUS2 -> "+2"
                LastKind.COLOR_PLUS2_SQ -> "+4"
                else -> null
            }
            ev.type == LastEvent.Type.CAUGHT && ev.target == me -> "+${ev.amount}"
            ev.type == LastEvent.Type.SLAPPED && (ev.mask shr me) and 1 != 0 -> "+${ev.amount}"
            else -> null
        }
        if (word != null) trigger = ui.moveNo to word
    }
    // Keyed on the trigger alone, so a move that is not a hit never cuts a fade short.
    LaunchedEffect(trigger) {
        if (trigger == null) return@LaunchedEffect
        alpha.snapTo(1f)
        scale.snapTo(0.5f)
        coroutineScope {
            launch { scale.animateTo(1.12f, spring(dampingRatio = 0.4f, stiffness = 450f)) }
            launch { delay(850); alpha.animateTo(0f, tween(450)) }
        }
    }

    val word = trigger?.second
    if (word != null && alpha.value > 0f) {
        Box(Modifier.fillMaxSize().zIndex(20f), contentAlignment = Alignment.Center) {
            Text(
                word,
                fontSize = 120.sp,
                fontWeight = FontWeight.Black,
                color = Pal.Red,
                style = TextStyle(shadow = Shadow(Pal.Court.copy(alpha = 0.55f), Offset(0f, 8f), 18f)),
                modifier = Modifier.graphicsLayer {
                    scaleX = scale.value; scaleY = scale.value; this.alpha = alpha.value
                },
            )
        }
    }
}
