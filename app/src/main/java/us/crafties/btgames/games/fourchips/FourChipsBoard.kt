package us.crafties.btgames.games.fourchips

/** A cell: empty, or a disc of whoever moved first / second this round. Values are the wire codes. */
enum class Chip(val code: Int) {
    EMPTY(0), FIRST(1), SECOND(2);

    fun other(): Chip = when (this) {
        FIRST -> SECOND
        SECOND -> FIRST
        EMPTY -> EMPTY
    }

    companion object {
        fun fromCode(c: Int): Chip? = entries.firstOrNull { it.code == c }
    }
}

enum class FourOutcome { IN_PROGRESS, FIRST_WINS, SECOND_WINS, DRAW }

/**
 * Immutable 7x6 board, row 0 at the top. Cells are indexed row * COLS + col.
 *
 * Discs fall: [drop] lands on the lowest empty cell of the column, or refuses. [turn] comes
 * from the move count and [firstMover], so both phones always agree on whose move it is.
 */
data class FourChipsBoard(
    val cells: List<Chip> = List(COLS * ROWS) { Chip.EMPTY },
    val firstMover: Chip = Chip.FIRST,
) {
    init {
        require(cells.size == COLS * ROWS) { "board must have 42 cells" }
        require(firstMover != Chip.EMPTY) { "first mover must be FIRST or SECOND" }
    }

    val moveCount: Int get() = cells.count { it != Chip.EMPTY }

    val turn: Chip get() = if (moveCount % 2 == 0) firstMover else firstMover.other()

    val outcome: FourOutcome get() = outcomeOf(cells)

    val isOver: Boolean get() = outcome != FourOutcome.IN_PROGRESS

    /** The four winning cell indices, or null if nobody has connected four. */
    val winningCells: List<Int>? get() = findWin(cells)

    fun canDrop(col: Int, by: Chip): Boolean =
        !isOver && col in 0 until COLS && by == turn && cells[col] == Chip.EMPTY

    /** Returns the new board plus the landing row, or null if the drop is illegal. */
    fun drop(col: Int, by: Chip): DropResult? {
        if (!canDrop(col, by)) return null
        val row = (ROWS - 1 downTo 0).first { cells[it * COLS + col] == Chip.EMPTY }
        val next = cells.toMutableList()
        next[row * COLS + col] = by
        return DropResult(copy(cells = next), row)
    }

    data class DropResult(val board: FourChipsBoard, val row: Int)

    companion object {
        const val COLS = 7
        const val ROWS = 6

        private val DIRS = listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)

        fun outcomeOf(cells: List<Chip>): FourOutcome {
            val win = findWin(cells)
            if (win != null) {
                return if (cells[win[0]] == Chip.FIRST) FourOutcome.FIRST_WINS else FourOutcome.SECOND_WINS
            }
            return if (cells.all { it != Chip.EMPTY }) FourOutcome.DRAW else FourOutcome.IN_PROGRESS
        }

        fun findWin(cells: List<Chip>): List<Int>? {
            for (r in 0 until ROWS) {
                for (c in 0 until COLS) {
                    val mark = cells[r * COLS + c]
                    if (mark == Chip.EMPTY) continue
                    for ((dr, dc) in DIRS) {
                        val line = (0..3).map { i -> (r + dr * i) to (c + dc * i) }
                        if (line.all { (rr, cc) -> rr in 0 until ROWS && cc in 0 until COLS }) {
                            if (line.all { (rr, cc) -> cells[rr * COLS + cc] == mark }) {
                                return line.map { (rr, cc) -> rr * COLS + cc }
                            }
                        }
                    }
                }
            }
            return null
        }

        /**
         * A board received from the network is only trusted if it could arise from legal play:
         * no floating discs, counts consistent with [firstMover], at most one winner, and the
         * winner having made the last move.
         */
        fun isReachable(cells: List<Chip>, firstMover: Chip): Boolean {
            if (cells.size != COLS * ROWS) return false
            if (firstMover == Chip.EMPTY) return false
            // Gravity: reading bottom-up, once a hole appears the rest of the column is holes.
            for (c in 0 until COLS) {
                var seenHole = false
                for (r in ROWS - 1 downTo 0) {
                    if (cells[r * COLS + c] == Chip.EMPTY) seenHole = true
                    else if (seenHole) return false
                }
            }
            val first = cells.count { it == firstMover }
            val second = cells.count { it == firstMover.other() }
            if (first != second && first != second + 1) return false
            val winners = DIRS.flatMap { (dr, dc) ->
                (0 until ROWS).flatMap { r ->
                    (0 until COLS).mapNotNull { c ->
                        val line = (0..3).map { i -> (r + dr * i) to (c + dc * i) }
                        if (!line.all { (rr, cc) -> rr in 0 until ROWS && cc in 0 until COLS }) null
                        else {
                            val mark = cells[r * COLS + c]
                            if (mark != Chip.EMPTY && line.all { (rr, cc) -> cells[rr * COLS + cc] == mark }) mark
                            else null
                        }
                    }
                }
            }.toSet()
            if (winners.size > 1) return false
            if (winners.size == 1) {
                val w = winners.first()
                if (w == firstMover && first != second + 1) return false
                if (w != firstMover && first != second) return false
            }
            return true
        }
    }
}
