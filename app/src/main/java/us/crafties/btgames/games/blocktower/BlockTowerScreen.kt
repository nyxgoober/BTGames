package us.crafties.btgames.games.blocktower

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutQuad
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.SystemClock
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import androidx.compose.ui.res.stringResource
import us.crafties.btgames.R
import us.crafties.btgames.games.blocktower.BlockTowerPhysics.TowerState
import us.crafties.btgames.ui.Haptics
import us.crafties.btgames.ui.Pal
import us.crafties.btgames.ui.RaisedButton
import us.crafties.btgames.ui.RaisedCard
import us.crafties.btgames.ui.Tone
import us.crafties.btgames.ui.Wording

// ---- 3D camera ------------------------------------------------------------------------------
//
// The tower is real geometry in X (right), Z (depth, toward viewer), Y (up) tower-units, matching
// BlockTowerPhysics exactly: block centres at slotCenter(i) along the layer's axis, BLOCK_H tall,
// stacked k*BLOCK_H up. A 3/4 camera (yaw + pitch) turns that into screen pixels, so X-layers and
// Z-layers genuinely foreshorten differently — the alternating orientation the physics already
// models finally reads as alternating orientation on screen, and a lean bends toward the camera or
// away from it in true depth instead of a flat horizontal shear. The player can drag to orbit: yaw
// and pitch are state, not constants, and every projection below takes them as parameters.

/** Starting orbit: how far around the tower the camera sits, and how far above it looks down. */
const val DEFAULT_YAW = 0.62f // radians
const val DEFAULT_PITCH = 0.52f // radians

/** How far the camera is allowed to tip: never quite flat-on, never quite overhead. */
private const val MIN_PITCH = 0.12f
private const val MAX_PITCH = 1.5f

/** Drag-to-orbit feel: radians of camera turn per pixel dragged. */
private const val ORBIT_SENSITIVITY = 0.010f

/** One 3D point in tower units. */
private data class V3(val x: Float, val y: Float, val z: Float)

/** A projected point: screen offset plus a depth used only for painter's-order sorting.
 * Depth grows toward the camera, so larger depth is nearer. */
private data class P2(val x: Float, val y: Float, val depth: Float)

/** Screen projection of one tower unit, in px, plus the camera basis. Kept in one place for draw and hit-test. */
private data class TowerLayout(val scale: Float, val cx: Float, val cy: Float, val yaw: Float, val pitch: Float) {
    private val cosY = cos(yaw)
    private val sinY = sin(yaw)
    private val cosP = cos(pitch)
    private val sinP = sin(pitch)

    /** Project a world point (tower units) to screen px. */
    fun project(p: V3): P2 {
        // Yaw around the vertical axis, then pitch the camera down toward the tower. Depth is
        // the point's distance along the toward-camera axis, so larger depth means nearer.
        val rx = p.x * cosY - p.z * sinY
        val rz = p.x * sinY + p.z * cosY
        val ry = p.y * cosP - rz * sinP
        val depth = p.y * sinP + rz * cosP
        return P2(cx + rx * scale, cy - ry * scale, depth)
    }
}

private fun layoutTower(size: Size, rows: Int, yaw: Float, pitch: Float): TowerLayout {
    // Fit the full 3/4 silhouette: width and height generous enough to hold the tower at any
    // orbit angle in [MIN_PITCH, MAX_PITCH], not just the default 3/4 view.
    val worldW = 6.4f
    val worldH = rows * 0.6f * 1.6f + 2.8f
    val scale = min(size.width / worldW, size.height / worldH)
    return TowerLayout(scale, size.width / 2f, size.height * 0.62f, yaw, pitch)
}

/** A pulled block mid-flight: where it came from, and 0→1 along its way out. */
private data class Flying(val layer: Int, val slot: Int, val p: Animatable<Float, *>)

private const val FLY_MS = 450

/** World-space (x, z) offset of row [k]: the bend is shared along the height, so the whole tower
 * leans as a rigid rod rather than shearing layer to layer — same rule [BlockTowerPhysics.leanOffset]
 * uses for the physics, now driving true 3D position instead of a flat screen shear. */
private fun rowOffset(t: TowerState, k: Int): Pair<Float, Float> {
    val top = BlockTowerPhysics.topLayer(t).coerceAtLeast(1).toFloat()
    val f = (k / top).coerceIn(0f, 1f)
    return t.leanX * f to t.leanZ * f
}

@Composable
fun BlockTowerScreen(
    state: BlockTowerUi,
    seed: Long,
    onTapBlock: (Int, Int) -> Unit,
    onPull: () -> Unit,
    onRematch: () -> Unit,
    onLeave: () -> Unit,
) {
    // Feel it: a tick on select, a thump on pull, the result pattern on the fall.
    LaunchedEffect(state.selLayer, state.selSlot) {
        if (state.selLayer >= 0) Haptics.tick()
    }
    val moves = state.tower?.moveCount ?: 0
    LaunchedEffect(moves) {
        if (moves > 0 && state.phase == BlockTowerPhase.PLAYING) Haptics.thud()
    }
    LaunchedEffect(state.outcome) {
        when (state.outcome) {
            BlockTowerResult.WIN -> Haptics.win()
            BlockTowerResult.LOSS -> Haptics.lose()
            null -> Unit
        }
    }

    // Collapse clock: redraws every frame while the tower falls (visual only, ~2.6 s), so the
    // collapse runs at the display's full refresh rate instead of a fixed ~30 fps tick.
    var nowMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.phase) {
        if (state.phase == BlockTowerPhase.OVER) {
            while (true) {
                withFrameNanos { }
                nowMs = SystemClock.uptimeMillis()
            }
        }
    }

    // Never scrollable: the tower card takes whatever room is left, so orbit drags always
    // reach the canvas and the Pull button can never scroll off-screen on small phones.
    Column(
        Modifier.fillMaxSize().background(Pal.Paper).safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Scoreboard(state)
        Spacer(Modifier.height(8.dp))
        StatusLine(state)
        Spacer(Modifier.height(10.dp))

        when (state.phase) {
            BlockTowerPhase.SYNC -> SyncBody(state)
            BlockTowerPhase.PLAYING, BlockTowerPhase.OVER -> TowerBody(
                state = state,
                seed = seed,
                nowMs = nowMs,
                onTapBlock = onTapBlock,
                onPull = onPull,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(10.dp))
        Footer(state, onRematch, onLeave)
    }
}

// ---- header -------------------------------------------------------------------------

@Composable
private fun Scoreboard(s: BlockTowerUi) {
    val t = s.tower
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Tally(
            name = s.myNick, big = "${s.myPulls}",
            sub = stringResource(R.string.tower_pulled), color = Pal.paint(s.mySide), align = Alignment.Start,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val wobble = ((t?.leanMag ?: 0f) / BlockTowerPhysics.COLLAPSE_LEAN).coerceIn(0f, 1f)
            Text(stringResource(R.string.tower_wobble), fontSize = 12.sp, color = Pal.Muted)
            LinearProgressIndicator(
                progress = { wobble },
                modifier = Modifier.fillMaxWidth(0.3f).height(8.dp),
                color = if (wobble > 0.75f) Pal.Red else Pal.Teal,
                trackColor = Pal.EdgeShade,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.tower_layers, t?.let { BlockTowerPhysics.topLayer(it) + 1 } ?: 0),
                fontSize = 11.sp, color = Pal.Muted,
            )
        }
        Tally(
            name = s.theirNick, big = "${s.theirPulls}",
            sub = stringResource(R.string.tower_pulled), color = Pal.paint(s.mySide.other()), align = Alignment.End,
        )
    }
}

@Composable
private fun Tally(
    name: String, big: String, sub: String, color: Color, align: Alignment.Horizontal,
) {
    Column(horizontalAlignment = align, modifier = Modifier.padding(horizontal = 2.dp)) {
        Text(
            name.ifBlank { stringResource(R.string.common_player) }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            color = color, maxLines = 1,
        )
        Text(big, fontSize = 34.sp, fontWeight = FontWeight.Black, color = Pal.Ink)
        Text(sub, fontSize = 12.sp, color = Pal.Muted)
    }
}

@Composable
private fun StatusLine(s: BlockTowerUi) {
    val opponent = stringResource(R.string.common_opponent)
    val (text, color) = when (s.phase) {
        BlockTowerPhase.SYNC -> if (s.waitingForPeer) {
            stringResource(R.string.tower_sync_wait, s.theirNick.ifBlank { opponent }) to Pal.Ink
        } else stringResource(R.string.tower_sync) to Pal.Ink
        BlockTowerPhase.PLAYING -> if (s.myTurn) {
            if (s.selLayer >= 0) stringResource(R.string.tower_pull) to Pal.Ink
            else stringResource(R.string.tower_your_move) to Pal.Ink
        } else stringResource(R.string.tower_wait, s.theirNick.ifBlank { opponent }) to Pal.Ink
        BlockTowerPhase.OVER -> when {
            s.felledByMe == true -> stringResource(R.string.tower_toppled_you) to Pal.paint(s.mySide.other())
            s.felledByMe == false -> Wording.subject(
                s.theirNick, R.string.tower_toppled_them, R.string.tower_toppled_them_anon,
            ) to Pal.paint(s.mySide)
            else -> "" to Pal.Ink
        }
    }
    Crossfade(targetState = text to color, animationSpec = tween(220), label = "status") { (t, c) ->
        Text(t, fontSize = 22.sp, fontWeight = FontWeight.Black, color = c)
    }
}

// ---- sync -----------------------------------------------------------------------------------

@Composable
private fun SyncBody(s: BlockTowerUi) {
    RaisedCard(Modifier.fillMaxWidth()) {
        Text(
            if (s.waitingForPeer) {
                stringResource(
                    R.string.tower_ready,
                    s.theirNick.ifBlank { stringResource(R.string.common_opponent) },
                )
            } else stringResource(R.string.tower_building),
            fontSize = 16.sp, color = Pal.Muted,
        )
    }
}

// ---- the tower ----------------------------------------------------------------------------------

@Composable
private fun TowerBody(
    state: BlockTowerUi,
    seed: Long,
    nowMs: Long,
    onTapBlock: (Int, Int) -> Unit,
    onPull: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = state.tower
    if (t == null) {
        RaisedCard(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.tower_building), fontSize = 16.sp, color = Pal.Muted)
        }
        return
    }
    val tap by rememberUpdatedState(onTapBlock)
    val interactive = state.phase == BlockTowerPhase.PLAYING && state.myTurn
    val rows = t.layers.size

    // Drag to orbit: yaw wraps all the way around, pitch stays clamped so the camera never goes
    // flat-on or flips overhead. Survives recomposition (new tower, new turn) but not navigating
    // away — a fresh visit starts back at the default 3/4 angle.
    var yaw by remember { mutableFloatStateOf(DEFAULT_YAW) }
    var pitch by remember { mutableFloatStateOf(DEFAULT_PITCH) }

    // The latest pull flies out instead of vanishing, created synchronously in composition so
    // the hole and the flight start on the same frame: it rides its real 3D box out along the
    // direction it was actually pulled and fades as it clears the tower.
    val flying = remember(state.pullSeq) {
        if (state.pullSeq == 0 || state.pullLayer < 0) null
        else Flying(state.pullLayer, state.pullSlot, Animatable(0f))
    }
    LaunchedEffect(state.pullSeq) {
        flying?.p?.animateTo(1f, animationSpec = tween(FLY_MS, easing = EaseOutQuad))
    }

    // Hover clock: while a block is selected, redraw every frame so the hover bob glides at
    // the display's full refresh rate. Without this the lift below is sampled from the wall
    // clock on whatever random recomposition happens to redraw the canvas, and the selected
    // block visibly snaps.
    var hoverMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.selLayer, state.selSlot) {
        if (state.selLayer < 0) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            hoverMs = SystemClock.uptimeMillis()
        }
    }

    RaisedCard(modifier.fillMaxWidth(), contentPadding = PaddingValues(8.dp)) {
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    // Orbit always works, win or lose, playing or watching — it's just the camera.
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            yaw -= dragAmount.x * ORBIT_SENSITIVITY
                            pitch = (pitch + dragAmount.y * ORBIT_SENSITIVITY)
                                .coerceIn(MIN_PITCH, MAX_PITCH)
                        },
                    )
                }
                .pointerInput(interactive, rows) {
                    if (!interactive) return@pointerInput
                    detectTapGestures { pos ->
                        val hit = pickBlock(
                            t, Size(size.width.toFloat(), size.height.toFloat()), pos, yaw, pitch,
                        )
                        if (hit != null) tap(hit.first, hit.second)
                    }
                },
        ) {
            if (state.phase == BlockTowerPhase.OVER) {
                drawCollapse(t, seed, state.fellAt, nowMs, yaw, pitch)
            } else {
                drawTower(t, state.selLayer, state.selSlot, yaw, pitch, hoverMs)
            }
            // Also over the collapse: the fatal pull was a real yank, not a vanishing.
            drawFlying(state, flying, yaw, pitch)
        }
    }

    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val selLegal = state.selLayer >= 0 &&
            BlockTowerPhysics.isLegalPull(t, state.selLayer, state.selSlot)
        RaisedButton(
            text = stringResource(R.string.tower_pull_btn),
            onClick = onPull,
            enabled = interactive && selLegal,
            tone = Tone.Primary,
            modifier = Modifier.weight(1f),
        )
    }
}

/** World-space box for block [i] of layer [k], centred on its jittered position, lifted by [lift]
 * tower-units (used to hover the selected block and to fly a pulled one). Half-extents follow
 * [BlockTowerPhysics.BLOCK_LEN]/[BlockTowerPhysics.BLOCK_H]: long along the layer's axis, unit
 * across it — the alternating-orientation geometry the physics already models. */
private fun blockBox(t: TowerState, k: Int, i: Int, lift: Float = 0f): List<V3> {
    val blk = t.layers[k][i]
    val (ox, oz) = rowOffset(t, k)
    val alongX = BlockTowerPhysics.alongX(k)
    val across = BlockTowerPhysics.slotCenter(i)
    val worldX = (if (alongX) 0f else across) + ox + blk.jx
    val worldZ = (if (alongX) across else 0f) + oz + blk.jz
    val y0 = k * BlockTowerPhysics.BLOCK_H + lift
    val y1 = y0 + BlockTowerPhysics.BLOCK_H
    val hl = BlockTowerPhysics.BLOCK_LEN / 2f // along the beam's long axis
    val hw = 0.46f // across the beam (slightly under 1 unit so neighbours don't touch)
    val (hx, hz) = if (alongX) hl to hw else hw to hl
    return listOf(
        V3(worldX - hx, y0, worldZ - hz), V3(worldX + hx, y0, worldZ - hz),
        V3(worldX + hx, y0, worldZ + hz), V3(worldX - hx, y0, worldZ + hz),
        V3(worldX - hx, y1, worldZ - hz), V3(worldX + hx, y1, worldZ - hz),
        V3(worldX + hx, y1, worldZ + hz), V3(worldX - hx, y1, worldZ + hz),
    )
}

/** Which block sits under [pos], nearest to the camera first, or null for a tap on air. Tests
 * the projected top face of every present block (a real 3D hit-test, not a row/column grid). */
private fun pickBlock(t: TowerState, size: Size, pos: Offset, yaw: Float, pitch: Float): Pair<Int, Int>? {
    val lay = layoutTower(size, t.layers.size, yaw, pitch)
    data class Cand(val k: Int, val i: Int, val depth: Float)
    val cands = mutableListOf<Cand>()
    t.layers.forEachIndexed { k, layer ->
        layer.forEachIndexed { i, blk ->
            if (!blk.present) return@forEachIndexed
            val box = blockBox(t, k, i)
            // Only test the faces actually visible from this camera — top, plus whichever of the
            // near/far pair on each axis faces the viewer — so a tap can't land on a block's
            // hidden side and pick a slot that isn't the one the player is looking at.
            val top = box.subList(4, 8).map(lay::project)
            // Faces share the blockBox corner order (0..3 bottom, 4..7 top): each quad below
            // lists bottom, bottom, top, top so the grain streaks can run bottom-to-top, and
            // every quad winds counter-clockwise seen from outside the box — the precondition
            // [faceTowardCamera] relies on.
            val front = listOf(box[3], box[2], box[6], box[7]).map(lay::project)
            val right = listOf(box[2], box[1], box[5], box[6]).map(lay::project)
            val back = listOf(box[1], box[0], box[4], box[5]).map(lay::project)
            val left = listOf(box[0], box[3], box[7], box[4]).map(lay::project)
            val visFront = if (faceTowardCamera(front)) front else back
            val visRight = if (faceTowardCamera(right)) right else left
            val depth = (top[0].depth + top[1].depth + top[2].depth + top[3].depth) / 4f
            if (pointInQuad(pos, top) || pointInQuad(pos, visFront) || pointInQuad(pos, visRight)) {
                cands.add(Cand(k, i, depth))
            }
        }
    }
    // Nearest to the camera (largest projected depth) wins when faces overlap on screen.
    return cands.maxByOrNull { it.depth }?.let { it.k to it.i }
}

private fun pointInQuad(p: Offset, q: List<P2>): Boolean {
    var inside = false
    var j = q.size - 1
    for (idx in q.indices) {
        val a = q[idx]; val b = q[j]
        if ((a.y > p.y) != (b.y > p.y)) {
            val xCross = (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x
            if (p.x < xCross) inside = !inside
        }
        j = idx
    }
    return inside
}

private fun DrawScope.drawTower(
    t: TowerState, selLayer: Int, selSlot: Int, yaw: Float, pitch: Float, hoverMs: Long,
) {
    val lay = layoutTower(size, t.layers.size, yaw, pitch)
    // Painter's algorithm: draw every present block back-to-front by its projected depth, so
    // nearer blocks correctly cover farther ones however the camera or the lean turns them.
    data class Item(val k: Int, val i: Int, val depth: Float)
    val items = mutableListOf<Item>()
    t.layers.forEachIndexed { k, layer ->
        layer.forEachIndexed { i, blk ->
            val box = blockBox(t, k, i)
            val depth = box.map { lay.project(it).depth }.average().toFloat()
            if (blk.present) items.add(Item(k, i, depth))
            else {
                // The empty slot: a flat ghosted quad on the layer's resting plane.
                drawGhostSlot(t, lay, k, i)
            }
        }
    }
    items.sortBy { it.depth } // farthest (smallest depth) first, nearest last so it covers
    for (item in items) {
        val blk = t.layers[item.k][item.i]
        val lift = if (item.k == selLayer && item.i == selSlot) {
            0.16f + 0.05f * sin(hoverMs % 700 / 700f * 2f * Math.PI.toFloat())
        } else 0f
        drawBlock3D(
            box = blockBox(t, item.k, item.i, lift), lay = lay, shade = blk.shade,
            selected = item.k == selLayer && item.i == selSlot,
        )
    }
}

private fun DrawScope.drawGhostSlot(t: TowerState, lay: TowerLayout, k: Int, i: Int) {
    val alongX = BlockTowerPhysics.alongX(k)
    val (ox, oz) = rowOffset(t, k)
    val across = BlockTowerPhysics.slotCenter(i)
    val worldX = if (alongX) 0f else across
    val worldZ = if (alongX) across else 0f
    val y = k * BlockTowerPhysics.BLOCK_H
    val hl = BlockTowerPhysics.BLOCK_LEN / 2f
    val hw = 0.46f
    val (hx, hz) = if (alongX) hl to hw else hw to hl
    val corners = listOf(
        V3(worldX - hx + ox, y, worldZ - hz + oz), V3(worldX + hx + ox, y, worldZ - hz + oz),
        V3(worldX + hx + ox, y, worldZ + hz + oz), V3(worldX - hx + ox, y, worldZ + hz + oz),
    ).map { lay.project(it) }
    val path = Path().apply {
        moveTo(corners[0].x, corners[0].y)
        for (c in corners.drop(1)) lineTo(c.x, c.y)
        close()
    }
    drawPath(path, Pal.Ink.copy(alpha = 0.07f))
}

/**
 * The latest pull, mid-flight: the block rides its real 3D box outward along the slot's own
 * across-axis (the direction it was actually pulled), passing in front of everything as it comes
 * toward the camera, then fades.
 */
private fun DrawScope.drawFlying(s: BlockTowerUi, flying: Flying?, yaw: Float, pitch: Float) {
    val f = flying ?: return
    if (f.p.value >= 1f) return
    val t = s.tower ?: return
    val p = f.p.value
    val lay = layoutTower(size, t.layers.size, yaw, pitch)
    val k = f.layer
    val alongX = BlockTowerPhysics.alongX(k)
    val (dirX, dirZ) = BlockTowerPhysics.pullDir(k, f.slot)
    val travel = 3.2f * p
    val base = blockBox(t, k, f.slot)
    val alpha = (1f - p).coerceIn(0f, 1f)
    val box = base.map { V3(it.x + dirX * travel + s.pullJx, it.y + s.pullJz * 0.4f, it.z + dirZ * travel) }
    drawBlock3D(box = box, lay = lay, shade = s.pullShade, selected = false, alpha = alpha, alongXOverride = alongX)
}

/** Fixed light from upper-front-left: sells volume without per-frame lighting math. */
private fun litFace(base: Color, litness: Float, alpha: Float): Color {
    val f = litness.coerceIn(-1f, 1f)
    return Color(
        red = (base.red + f * 0.16f).coerceIn(0f, 1f),
        green = (base.green + f * 0.15f).coerceIn(0f, 1f),
        blue = (base.blue + f * 0.11f).coerceIn(0f, 1f),
        alpha = alpha,
    )
}

/**
 * A single beam, drawn as three visible faces of its real 3D box (top + the two camera-facing
 * sides), each shaded by a fixed light so the block reads as a solid rather than a painted card.
 * Only faces whose outward normal actually points at the camera are drawn — without this, every
 * block in a layer shows its "far" side too, and a 3-wide layer reads as all three slots sitting
 * open to view instead of the near two overlapping the far one the way a real stack does.
 * The face along the beam's long axis shows wood grain; the end face shows a growth ring — this
 * is what makes an X-layer and a Z-layer look visibly different, the way a real Jenga tower does.
 */
private fun DrawScope.drawBlock3D(
    box: List<V3>, lay: TowerLayout, shade: Float, selected: Boolean, alpha: Float = 1f,
    alongXOverride: Boolean? = null,
) {
    val base = Color(red = 0.88f - shade * 0.18f, green = 0.66f - shade * 0.16f, blue = 0.38f - shade * 0.10f)
    val p = box.map { lay.project(it) }
    // Faces, each as 4 of the 8 box corners (0..3 bottom, 4..7 top, matching blockBox order).
    // Every quad lists bottom, bottom, top, top and winds counter-clockwise seen from outside
    // the box — the precondition [faceTowardCamera] relies on.
    val top = listOf(p[4], p[7], p[6], p[5])
    val front = listOf(p[3], p[2], p[6], p[7]) // +z face, outward normal (0, 0, 1)
    val right = listOf(p[2], p[1], p[5], p[6]) // +x face, outward normal (1, 0, 0)
    val back = listOf(p[1], p[0], p[4], p[5]) // -z face, outward normal (0, 0, -1)
    val left = listOf(p[0], p[3], p[7], p[4]) // -x face, outward normal (-1, 0, 0)
    fun poly(face: List<P2>, color: Color) {
        val path = Path().apply {
            moveTo(face[0].x, face[0].y)
            for (c in face.drop(1)) lineTo(c.x, c.y)
            close()
        }
        drawPath(path, color)
    }
    // Grain: long streaks on the long face, a ring on the end face, drawn as clipped lines/circle.
    fun grain(face: List<P2>, alongLong: Boolean, color: Color) {
        val path = Path().apply {
            moveTo(face[0].x, face[0].y)
            for (c in face.drop(1)) lineTo(c.x, c.y)
            close()
        }
        clipPath(path) {
            if (alongLong) {
                for (f in listOf(0.32f, 0.5f, 0.68f)) {
                    val start = Offset(
                        face[0].x + (face[3].x - face[0].x) * f, face[0].y + (face[3].y - face[0].y) * f,
                    )
                    val end = Offset(
                        face[1].x + (face[2].x - face[1].x) * f, face[1].y + (face[2].y - face[1].y) * f,
                    )
                    drawLine(color, start, end, strokeWidth = size.minDimension * 0.0015f + 1.2f)
                }
            } else {
                val cxr = (face[0].x + face[1].x + face[2].x + face[3].x) / 4f
                val cyr = (face[0].y + face[1].y + face[2].y + face[3].y) / 4f
                val r = kotlin.math.hypot(face[2].x - face[0].x, face[2].y - face[0].y) / 4.2f
                drawCircle(color, radius = r, center = Offset(cxr, cyr), style = Stroke(width = r * 0.28f))
            }
        }
    }
    val alongX = alongXOverride ?: run {
        // Infer from box proportions: the long axis has the bigger extent.
        kotlin.math.abs(box[1].x - box[0].x) + kotlin.math.abs(box[1].z - box[0].z) >
            kotlin.math.abs(box[3].x - box[0].x) + kotlin.math.abs(box[3].z - box[0].z)
    }
    // Back-face cull: at any orbit angle, only one of each opposing face pair can ever point at
    // the camera. [faceTowardCamera] measures that per-frame from the projected winding, so this
    // stays correct as the player drags the camera all the way around — nothing here assumes a
    // fixed viewing octant.
    val showFront = faceTowardCamera(front)
    val showRight = faceTowardCamera(right)
    // Top: brightest, lit from above.
    poly(top, litFace(base, 0.9f, alpha))
    if (showFront) poly(front, litFace(base, -0.05f, alpha)) else poly(back, litFace(base, -0.05f, alpha))
    if (showRight) poly(right, litFace(base, -0.42f, alpha)) else poly(left, litFace(base, -0.42f, alpha))
    val grainColor = litFace(base, -0.6f, alpha * 0.8f)
    // Front face runs along whichever world axis is +z; grain shows as streaks when that face is
    // the beam's long side, a ring when it's the end.
    if (showFront) grain(front, alongLong = !alongX, grainColor) else grain(back, alongLong = !alongX, grainColor)
    if (showRight) grain(right, alongLong = alongX, grainColor) else grain(left, alongLong = alongX, grainColor)
    if (selected) {
        val strokeCol = Pal.Teal
        val visFront = if (showFront) front else back
        val visRight = if (showRight) right else left
        for (face in listOf(top, visFront, visRight)) {
            val path = Path().apply {
                moveTo(face[0].x, face[0].y)
                for (c in face.drop(1)) lineTo(c.x, c.y)
                close()
            }
            drawPath(path, strokeCol, style = Stroke(width = 3.5f))
        }
    }
}

/** True when a screen-space quad winds clockwise, i.e. its 3D face is angled toward the camera
 * rather than away from it (the standard 2D cross-product back-face test, applied post-projection
 * since our projection is a simple rotation with no perspective divide to upset winding — note
 * Compose's y-down screen flips the sign versus the math convention, so toward the camera reads
 * as a negative cross product). Every face quad passed here must wind counter-clockwise seen
 * from outside the box in 3D — see [drawBlock3D]. */
private fun faceTowardCamera(face: List<P2>): Boolean {
    val (a, b, c) = Triple(face[0], face[1], face[2])
    val cross = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
    return cross < 0f
}

/**
 * The deterministic collapse movie. Velocities come from [BlockTowerPhysics.collapseScatter]
 * with the round seed plus the fatal move number, so both phones scatter identically; only the
 * local clock drives the frames.
 */
private fun DrawScope.drawCollapse(t: TowerState, seed: Long, fellAt: Long, nowMs: Long, yaw: Float, pitch: Float) {
    val lay = layoutTower(size, t.layers.size, yaw, pitch)
    val elapsed = ((if (nowMs > 0) nowMs else fellAt) - fellAt) / 1000f
    val time = elapsed.coerceIn(0f, COLLAPSE_SECONDS)
    val scatter = BlockTowerPhysics.collapseScatter(
        t, BlockTowerPhysics.fallSeed(seed, t.moveCount - 1),
    )
    val veil = if (time > COLLAPSE_SECONDS - 0.8f) {
        ((COLLAPSE_SECONDS - time) / 0.8f).coerceIn(0f, 1f)
    } else 1f
    // Painter's order by current depth, same as the standing tower, so tumbling blocks still
    // occlude correctly as they fly past and toward the camera.
    data class Falling(val k: Int, val i: Int, val box: List<V3>, val depth: Float, val spinDeg: Float)
    val out = mutableListOf<Falling>()
    var si = 0
    t.layers.forEachIndexed { k, layer ->
        layer.forEachIndexed { i, blk ->
            if (!blk.present) return@forEachIndexed
            val sc = scatter[si++]
            // True 3D ballistic flight in tower units: outward in x/z, gravity pulls y down.
            val dx = sc.vx * time
            val dz = sc.vz * time
            val dy = sc.vy * time - 0.5f * GRAVITY * time * time
            val box = blockBox(t, k, i).map { V3(it.x + dx, it.y + dy, it.z + dz) }
            val depth = box.map { lay.project(it).depth }.average().toFloat()
            out.add(Falling(k, i, box, depth, sc.spin * time * 57.3f))
        }
    }
    out.sortBy { it.depth } // farthest first, same convention as the standing tower
    for (fl in out) {
        val blk = t.layers[fl.k][fl.i]
        val centerScreen = fl.box.map { lay.project(it) }
            .let { pts -> Offset(pts.map { it.x }.average().toFloat(), pts.map { it.y }.average().toFloat()) }
        withTransform({ rotate(fl.spinDeg, centerScreen) }) {
            drawBlock3D(box = fl.box, lay = lay, shade = blk.shade, selected = false)
        }
    }
    if (veil < 1f) {
        // Settle the dust: a soft veil as the fall ends.
        drawRect(Pal.Paper.copy(alpha = (1f - veil) * 0.35f))
    }
}

// ---- footer -------------------------------------------------------------------------

@Composable
private fun Footer(s: BlockTowerUi, onRematch: () -> Unit, onLeave: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        if (s.phase == BlockTowerPhase.OVER) {
            Text(
                stringResource(
                    R.string.tower_match,
                    s.myWins + s.theirWins, s.myWins,
                    s.theirNick.ifBlank { stringResource(R.string.common_them_low) }, s.theirWins,
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

private const val COLLAPSE_SECONDS = 2.6f
private const val GRAVITY = 7f
