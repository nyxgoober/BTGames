package us.crafties.btgames.games.tetriminoes

/**
 * One player's Tetriminoes board. Pure Kotlin (no Android), fully unit tested.
 *
 * Board is [W]x[H] (10x20, no hidden rows stored: pieces may hang above y=0 and that is
 * free space). Cells hold 0 (empty) or the piece [Kind] code 1..7.
 *
 * Pieces spawn from a 7-bag ([next] is always the piece that spawns after [current]).
 * Rotation is 90 deg clockwise inside the piece's NxN box (I: 4x4, O: fixed 2x2, rest 3x3)
 * with a small kick table so spins near walls and stacks still turn.
 *
 * Scoring (multiplied by [level], which the session derives from elapsed time):
 * single 100, double 300, triple 500, tetris 800, soft-drop cell 1, hard-drop cell 2.
 */
class TetriminoesEngine(
    var level: Int = 1,
    private val random: kotlin.random.Random = kotlin.random.Random.Default,
) {
    val grid: Array<IntArray> = Array(H) { IntArray(W) }

    var current: Kind = Kind.entries.random(random); private set
    var rotation: Int = 0; private set
    var px: Int = 0; private set   // left of the NxN box in board coords
    var py: Int = 0; private set   // top of the NxN box (may be negative)
    var next: Kind = Kind.entries.random(random); private set

    var score: Int = 0; private set
    var lines: Int = 0; private set
    var pieces: Int = 0; private set
    var gameOver: Boolean = false; private set

    private val bag = ArrayDeque<Kind>()

    init {
        // Deal the head of the bag straight away so first spawn is uniform.
        refill()
        spawn()
    }

    private fun refill() {
        if (bag.isNotEmpty()) return
        bag.addAll(Kind.entries.shuffled(random))
    }

    /** Cells of [kind] at [rot] (0..3 CW steps from spawn) inside its box. */
    fun shapeOf(kind: Kind, rot: Int): List<Pair<Int, Int>> {
        var cells = kind.spawn
        repeat(((rot % 4) + 4) % 4) {
            cells = cells.map { (x, y) -> (kind.box - 1 - y) to x }
        }
        return cells
    }

    fun currentCells(): List<Pair<Int, Int>> =
        shapeOf(current, rotation).map { (x, y) -> px + x to py + y }

    private fun collides(cells: List<Pair<Int, Int>>): Boolean =
        cells.any { (x, y) ->
            x < 0 || x >= W || y >= H || (y >= 0 && grid[y][x] != 0)
        }

    private fun spawn() {
        refill()
        current = bag.removeFirst()
        refill()
        // Peek the next head without removing so the preview is honest.
        next = bag.first()
        rotation = 0
        px = (W - current.box) / 2
        py = if (current == Kind.I) -1 else 0
        if (collides(currentCells())) gameOver = true
    }

    fun moveLeft(): Boolean {
        if (gameOver) return false
        val moved = currentCells().map { (x, y) -> x - 1 to y }
        if (collides(moved)) return false
        px--
        return true
    }

    fun moveRight(): Boolean {
        if (gameOver) return false
        val moved = currentCells().map { (x, y) -> x + 1 to y }
        if (collides(moved)) return false
        px++
        return true
    }

    fun rotateCW(): Boolean {
        if (gameOver) return false
        if (current == Kind.O) return true // square: nothing to do, counts as handled
        val nextRot = (rotation + 1) % 4
        val shape = shapeOf(current, nextRot)
        for ((dx, dy) in KICKS) {
            val at = shape.map { (x, y) -> px + dx + x to py + dy + y }
            if (!collides(at)) {
                px += dx; py += dy; rotation = nextRot
                return true
            }
        }
        return false
    }

    /** One gravity step: fall a row, or lock if blocked. Never scores. */
    fun gravityTick(): Boolean {
        if (gameOver) return false
        val down = currentCells().map { (x, y) -> x to y + 1 }
        if (collides(down)) {
            lock()
            return false
        }
        py++
        return true
    }

    /** One soft-drop step: fall a row for a point, or lock if blocked. */
    fun softDrop(): Boolean {
        if (gameOver) return false
        val down = currentCells().map { (x, y) -> x to y + 1 }
        if (collides(down)) {
            lock()
            return false
        }
        py++
        score += 1
        return true
    }

    /** Backwards-compatible fall used by tests: same as a soft-drop step. */
    fun stepDown(): Boolean = softDrop()

    /** Teleport to the ghost position (+2 per cell), lock, and report the distance. */
    fun hardDrop(): Int {
        if (gameOver) return 0
        val dist = ghostDistance()
        py += dist
        score += dist * 2
        lock()
        return dist
    }

    /** How far the piece would fall with a hard drop. */
    fun ghostDistance(): Int {
        var d = 0
        while (true) {
            val probe = currentCells().map { (x, y) -> x to y + 1 + d }
            if (collides(probe)) return d
            d++
        }
    }

    fun ghostCells(): List<Pair<Int, Int>> {
        val d = ghostDistance()
        return currentCells().map { (x, y) -> x to y + d }
    }

    private fun lock() {
        var anyVisible = false
        for ((x, y) in currentCells()) {
            if (y >= 0) {
                grid[y][x] = current.code
                anyVisible = true
            }
        }
        pieces++
        if (!anyVisible) {
            gameOver = true
            return
        }
        clearLines()
        spawn()
    }

    private fun clearLines() {
        var cleared = 0
        var row = H - 1
        while (row >= 0) {
            if (grid[row].all { it != 0 }) {
                cleared++
                for (r in row downTo 1) grid[r] = grid[r - 1].copyOf()
                grid[0] = IntArray(W)
                // Stay on this row: the shifted-down row above may also be full.
            } else {
                row--
            }
        }
        if (cleared > 0) {
            lines += cleared
            score += LINE_POINTS[cleared.coerceAtMost(4)] * level
        }
    }

    /** Test helper: fill cells directly ('.' empty, 'X' filled, or a 1..7 digit). */
    fun setRows(vararg rows: String) {
        require(rows.size == H)
        rows.forEachIndexed { y, row ->
            require(row.length == W)
            row.forEachIndexed { x, c ->
                grid[y][x] = when (c) {
                    '.' -> 0
                    'X' -> 1
                    else -> c.digitToInt()
                }
            }
        }
    }

    /** Test helper: force the falling piece. */
    fun setPiece(kind: Kind, rot: Int = 0, x: Int = 3, y: Int = 0) {
        current = kind; rotation = ((rot % 4) + 4) % 4; px = x; py = y
    }

    companion object {
        const val W = 10
        const val H = 20

        /** Gravity: 750 ms a row at the start, 110 ms at the whistle. */
        const val START_INTERVAL_MS = 750L
        const val END_INTERVAL_MS = 110L

        /** Time-driven level 1..10; scoring multiplies by this. */
        fun levelFor(elapsedSec: Long, totalSec: Long): Int {
            if (totalSec <= 0) return 1
            val p = (elapsedSec.coerceAtLeast(0).toDouble() / totalSec).coerceIn(0.0, 1.0)
            return (1 + (p * 9).toInt()).coerceIn(1, 10)
        }

        /** Row interval for the current moment of the match. */
        fun intervalMs(elapsedSec: Long, totalSec: Long): Long {
            if (totalSec <= 0) return START_INTERVAL_MS
            val p = (elapsedSec.coerceAtLeast(0).toDouble() / totalSec).coerceIn(0.0, 1.0)
            return (START_INTERVAL_MS - ((START_INTERVAL_MS - END_INTERVAL_MS) * p)).toLong()
        }

        private val LINE_POINTS = intArrayOf(0, 100, 300, 500, 800)

        private val KICKS = listOf(
            0 to 0, -1 to 0, 1 to 0, 0 to -1, -1 to -1, 1 to -1, 0 to 1,
        )
    }
}

/** The seven tetrominoes. [code] doubles as the stored cell value. */
enum class Kind(val code: Int, val box: Int, val spawn: List<Pair<Int, Int>>) {
    I(1, 4, listOf(0 to 1, 1 to 1, 2 to 1, 3 to 1)),
    O(2, 2, listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1)),
    T(3, 3, listOf(1 to 0, 0 to 1, 1 to 1, 2 to 1)),
    S(4, 3, listOf(1 to 0, 2 to 0, 0 to 1, 1 to 1)),
    Z(5, 3, listOf(0 to 0, 1 to 0, 1 to 1, 2 to 1)),
    J(6, 3, listOf(0 to 0, 0 to 1, 1 to 1, 2 to 1)),
    L(7, 3, listOf(2 to 0, 0 to 1, 1 to 1, 2 to 1)),
}
