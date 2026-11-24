package us.crafties.btgames.games.blocktower

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Deterministic block-tower physics. No host, no authority: both phones hold the same [TowerState]
 * and apply the same moves in the same order, so every simulation agrees bit-for-bit.
 *
 * Randomness comes only from the seed the players share at the start of the round:
 * tower imperfections at build time, the wobble direction of every pull, and the scatter of a
 * collapse. Same seed + same pulls = same tower; a new seed deals a new tower, so pulling the
 * same blocks twice plays out differently.
 *
 * The model: layers of 3 blocks alternate orientation (even layers run along X, odd along Z).
 * This is the kind of Jenga where pulled blocks stay out: each pull lifts a block away and the
 * tower leans a little toward the gap, plus a seeded wobble — and the lean persists. The tower
 * topples once the top has drifted past [COLLAPSE_LEAN], or immediately if some layer's centre
 * of mass ends up with no support under it (e.g. pulling the last block of a layer). Only the
 * cross axis is checked per layer: along its length a block always rests on the layer below.
 *
 * Float note: both phones run the same bytecode on IEEE-754 hardware (every Android phone),
 * so the same inputs give the same floats on both sides.
 */
object BlockTowerPhysics {
    const val START_LAYERS = 10
    const val SLOTS = 3

    /** Block proportions in width units: a classic 3:1 plank, 0.6 thick. */
    const val BLOCK_LEN = 3f
    const val BLOCK_H = 0.6f

    /** Top drift (width units) that brings the tower down. Base half-width is 1.5. */
    const val COLLAPSE_LEAN = 1.1f

    /** Support-span edge tolerance. Inside it a layer still holds. */
    const val SPAN_TOL = 0.03f

    /** Imperfection of a fresh block: offset range and draw shade come from the seed. */
    const val JITTER = 0.05f

    /** Layer k runs along X when even, along Z when odd. */
    fun alongX(layer: Int): Boolean = layer % 2 == 0

    /** Centre of slot 0/1/2 across the layer. */
    fun slotCenter(slot: Int): Float = (slot - 1).toFloat()

    data class Block(
        val present: Boolean,
        val jx: Float,
        val jz: Float,
        val shade: Float,
    )

    data class TowerState(
        val layers: List<List<Block>>,
        val leanX: Float,
        val leanZ: Float,
        val moveCount: Int,
    ) {
        val leanMag: Float get() = sqrt(leanX * leanX + leanZ * leanZ)
    }

    /** Highest layer holding at least one block. */
    fun topLayer(s: TowerState): Int =
        s.layers.indexOfLast { layer -> layer.any { it.present } }.coerceAtLeast(0)

    fun presentCount(s: TowerState, layer: Int): Int =
        if (layer in s.layers.indices) s.layers[layer].count { it.present } else 0

    /** A pull is legal when the game runs, it is a real block, and it is not on the top layer. */
    fun isLegalPull(s: TowerState, layer: Int, slot: Int): Boolean {
        if (layer < 0 || layer >= topLayer(s)) return false
        if (slot !in 0 until SLOTS) return false
        if (layer !in s.layers.indices) return false
        return s.layers[layer][slot].present
    }

    /** True while anyone still has a block to take: any present block below the top layer. */
    fun hasLegalPull(s: TowerState): Boolean {
        for (layer in 0 until topLayer(s)) {
            if (layer in s.layers.indices && s.layers[layer].any { it.present }) return true
        }
        return false
    }

    /** Build a fresh tower. Every block's imperfection comes from the seed, in fixed order. */
    fun buildTower(seed: Long): TowerState {
        val r = kotlin.random.Random(seed)
        fun jitter() = Block(
            present = true,
            jx = r.nextFloat() * 2 * JITTER - JITTER,
            jz = r.nextFloat() * 2 * JITTER - JITTER,
            shade = r.nextFloat(),
        )
        val layers = List(START_LAYERS) { List(SLOTS) { jitter() } }
        return TowerState(layers, 0f, 0f, 0)
    }

    data class PullResult(val state: TowerState, val collapsed: Boolean)

    /**
     * Pull [layer]/[slot] out of the tower for good, disturb what remains, and check it still
     * stands. Requires [isLegalPull]; callers validate turns, this validates physics inputs.
     */
    fun applyPull(s: TowerState, layer: Int, slot: Int, seed: Long): PullResult {
        require(isLegalPull(s, layer, slot)) { "illegal pull $layer/$slot" }

        // Lift the block out. It stays out: this tower only ever shrinks.
        val layers = s.layers.map { it.toMutableList() }.toMutableList()
        layers[layer][slot] = layers[layer][slot].copy(present = false)

        // Disturb: the tower leans toward the gap, plus a seeded wobble. Both phones draw the
        // same two floats from the same per-move stream, so the lean agrees exactly.
        val n = s.moveCount
        val mr = kotlin.random.Random(mixSeed(seed, n))
        val a = mr.nextFloat() * 2f - 1f
        val b = mr.nextFloat() * 2f - 1f
        val dir = pullDir(layer, slot)
        val (vx, vz) = impulseDir(dir.first, dir.second, a, b)
        val mag = impulseMag(topLayerAfter(layers), layer, remainingAfter(layers, layer), slot)
        val leanX = s.leanX + vx * mag
        val leanZ = s.leanZ + vz * mag

        val next = TowerState(layers, leanX, leanZ, n + 1)
        return PullResult(next, hasCollapsed(next))
    }

    // ---- stability ------------------------------------------------------------------------

    /** True once the tower is past saving: leaned too far, or some layer floats. */
    fun hasCollapsed(s: TowerState): Boolean {
        if (s.leanMag > COLLAPSE_LEAN) return true
        val top = topLayer(s)
        val topF = top.coerceAtLeast(1).toFloat()
        for (k in 1..top) {
            val span = supportSpan(s, k, topF) ?: return true // nothing underneath at all
            val com = layerCom(s, k, topF)
            if (com < span.first - SPAN_TOL || com > span.second + SPAN_TOL) return true
        }
        return false
    }

    /**
     * Centre of mass of layer [k] along its constrained axis (x for X-layers, z for Z-layers),
     * including jitter and the layer's share of the lean.
     */
    fun layerCom(s: TowerState, k: Int, topF: Float): Float {
        val off = leanOffset(s, k, topF)
        val present = s.layers[k].mapIndexedNotNull { i, blk ->
            if (!blk.present) null else slotCenter(i) + if (alongX(k)) blk.jx else blk.jz
        }
        if (present.isEmpty()) return if (alongX(k)) off.first else off.second
        return present.average().toFloat() + if (alongX(k)) off.first else off.second
    }

    /**
     * Support span for layer [k] from the layer below, along the same axis: the convex hull of
     * the below blocks' footprints. A block bridging a gap still stands while its middle sits
     * between two supports; null when the layer below is gone entirely.
     */
    fun supportSpan(s: TowerState, k: Int, topF: Float): Pair<Float, Float>? {
        val below = s.layers[k - 1]
        val off = leanOffset(s, k - 1, topF)
        val intervals = below.mapIndexedNotNull { i, blk ->
            if (!blk.present) null else {
                val c = slotCenter(i) + (if (alongX(k)) blk.jx else blk.jz) +
                    if (alongX(k)) off.first else off.second
                (c - 0.5f) to (c + 0.5f)
            }
        }
        if (intervals.isEmpty()) return null
        return intervals.minOf { it.first } to intervals.maxOf { it.second }
    }

    /** Rigid share of the lean carried by layer [k]: the tower bends, it does not shear. */
    fun leanOffset(s: TowerState, k: Int, topF: Float): Pair<Float, Float> {
        val f = (k / topF).coerceIn(0f, 1f)
        return s.leanX * f to s.leanZ * f
    }

    // ---- impulses ---------------------------------------------------------------------------

    /**
     * Lean direction of a pull: toward the pulled side, since the stack settles into the gap.
     * Slots run across z on X-layers and across x on Z-layers.
     */
    fun pullDir(layer: Int, slot: Int): Pair<Float, Float> {
        val c = slotCenter(slot)
        return if (alongX(layer)) 0f to c else c to 0f
    }

    /** Pull direction blended with the seeded wobble. The sign along [d] never flips. */
    fun impulseDir(dx: Float, dz: Float, a: Float, b: Float): Pair<Float, Float> {
        // Perpendicular of d; a centre pull (d = 0) wobbles around the x-axis instead.
        val (px, pz) = if (dx == 0f && dz == 0f) 1f to 0f else -dz to dx
        var vx = dx + px * 0.6f * a + dx * 0.25f * b
        var vz = dz + pz * 0.6f * a + dz * 0.25f * b
        if (dx == 0f && dz == 0f) {
            vx = a
            vz = b
        }
        val len = sqrt(vx * vx + vz * vz)
        if (len < 1e-6f) return 1f to 0f
        return vx / len to vz / len
    }

    /**
     * How hard a pull shakes the tower. Low pulls move more stack, sparse layers and edge
     * blocks shift the weight further, and tall towers punish everything.
     */
    fun impulseMag(top: Int, layer: Int, remaining: Int, slot: Int): Float {
        val height = 1f + (top - layer) * 0.08f
        val sparse = 1f + (SLOTS - remaining) * 0.35f
        val edge = if (slot == 1) 1f else 1.25f
        val tall = 1f + top * 0.02f
        return 0.10f * height * sparse * edge * tall
    }

    // ---- collapse scatter (visual, but deterministic) -------------------------------------------

    data class Scatter(val vx: Float, val vy: Float, val vz: Float, val spin: Float)

    /**
     * Ballistic scatter for every present block, in tower units per second. Drawn from
     * [fallSeed], in layer/slot order, so both phones animate the identical collapse.
     */
    fun collapseScatter(s: TowerState, fallSeed: Long): List<Scatter> {
        val r = kotlin.random.Random(fallSeed)
        val out = mutableListOf<Scatter>()
        val topF = topLayer(s).coerceAtLeast(1).toFloat()
        s.layers.forEachIndexed { k, layer ->
            layer.forEach { blk ->
                if (!blk.present) return@forEach
                val ang = r.nextFloat() * 2f * Math.PI.toFloat()
                val push = 0.9f
                var dx = s.leanX * 1.5f + kotlin.math.cos(ang) * push
                var dz = s.leanZ * 1.5f + kotlin.math.sin(ang) * push
                val len = sqrt(dx * dx + dz * dz).coerceAtLeast(1e-6f)
                dx /= len; dz /= len
                val heightFrac = k / topF
                val speed = 2.5f + heightFrac * 3f + r.nextFloat() * 2f
                out.add(
                    Scatter(
                        vx = dx * speed,
                        vy = 1.5f + r.nextFloat() * 2f,
                        vz = dz * speed,
                        spin = (r.nextFloat() - 0.5f) * 10f,
                    ),
                )
            }
        }
        return out
    }

    /** Seed stream for move [n]: fixed draw order (two floats) keeps both phones in step. */
    fun mixSeed(seed: Long, n: Int): Long = seed xor (n.toLong() * 0x9E3779B9L)

    /** Collapse movie seed: the seed plus the fatal move number. */
    fun fallSeed(seed: Long, fatalMove: Int): Long = seed * 31L + fatalMove

    // ---- internals ------------------------------------------------------------------------------

    private fun topLayerAfter(layers: List<List<Block>>): Int =
        layers.indexOfLast { layer -> layer.any { it.present } }.coerceAtLeast(0)

    private fun remainingAfter(layers: List<List<Block>>, layer: Int): Int =
        layers[layer].count { it.present }
}
