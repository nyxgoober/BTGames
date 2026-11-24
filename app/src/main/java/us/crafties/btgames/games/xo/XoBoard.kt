package us.crafties.btgames.games.xo

/** Cell contents, and also the two players' marks. Values double as the wire encoding. */
enum class Mark(val code: Int) {
    EMPTY(0), X(1), O(2);

    fun other(): Mark = when (this) { X -> O; O -> X; EMPTY -> EMPTY }

    companion object {
        fun fromCode(c: Int): Mark? = entries.firstOrNull { it.code == c }
    }
}

enum class Outcome { IN_PROGRESS, X_WINS, O_WINS, DRAW }

/**
 * Immutable 3x3 board. Cells are indexed 0..8, left-to-right then top-to-bottom:
 *
 *   0 | 1 | 2
 *   3 | 4 | 5
 *   6 | 7 | 8
 *
 * [moveCount] is the number of marks placed so far. It is what keeps two phones in agreement
 * about whose turn it is and lets a receiver tell a fresh board from a stale/duplicate one.
 */
data class XoBoard(
    val cells: List<Mark> = List(9) { Mark.EMPTY },
    val firstMover: Mark = Mark.X,
) {
    init {
        require(cells.size == 9) { "board must have 9 cells" }
        require(firstMover != Mark.EMPTY) { "first mover must be X or O" }
    }

    val moveCount: Int get() = cells.count { it != Mark.EMPTY }

    /** Whose turn it is next. Alternates from [firstMover]; meaningless once the game is over. */
    val turn: Mark get() = if (moveCount % 2 == 0) firstMover else firstMover.other()

    val outcome: Outcome get() = outcomeOf(cells)

    val isOver: Boolean get() = outcome != Outcome.IN_PROGRESS

    /** The three cell indices of the winning line, or null if nobody has won. */
    val winningLine: List<Int>? get() = LINES.firstOrNull { (a, b, c) ->
        cells[a] != Mark.EMPTY && cells[a] == cells[b] && cells[b] == cells[c]
    }?.toList()

    fun canPlay(cell: Int, by: Mark): Boolean =
        !isOver && cell in 0..8 && cells[cell] == Mark.EMPTY && by == turn

    /** Returns the new board, or null if the move is illegal (wrong turn, occupied, game over). */
    fun play(cell: Int, by: Mark): XoBoard? {
        if (!canPlay(cell, by)) return null
        return copy(cells = cells.toMutableList().also { it[cell] = by })
    }

    companion object {
        private val LINES = listOf(
            Triple(0, 1, 2), Triple(3, 4, 5), Triple(6, 7, 8), // rows
            Triple(0, 3, 6), Triple(1, 4, 7), Triple(2, 5, 8), // columns
            Triple(0, 4, 8), Triple(2, 4, 6),                  // diagonals
        )

        fun outcomeOf(cells: List<Mark>): Outcome {
            for ((a, b, c) in LINES) {
                val m = cells[a]
                if (m != Mark.EMPTY && m == cells[b] && m == cells[c]) {
                    return if (m == Mark.X) Outcome.X_WINS else Outcome.O_WINS
                }
            }
            return if (cells.all { it != Mark.EMPTY }) Outcome.DRAW else Outcome.IN_PROGRESS
        }

        /**
         * A board received from the network is only trusted if it is reachable by legal play:
         * mark counts must be consistent with [firstMover], and a finished game cannot have
         * extra moves after the win. Guards against corrupt or hostile packets.
         */
        fun isReachable(cells: List<Mark>, firstMover: Mark): Boolean {
            if (cells.size != 9) return false
            val first = cells.count { it == firstMover }
            val second = cells.count { it == firstMover.other() }
            if (first != second && first != second + 1) return false
            val winners = LINES.filter { (a, b, c) ->
                cells[a] != Mark.EMPTY && cells[a] == cells[b] && cells[b] == cells[c]
            }.map { cells[it.first] }.toSet()
            if (winners.size > 1) return false // both players cannot have a line
            if (winners.size == 1) {
                val w = winners.first()
                // The winner made the last move: if the first mover won they have one more mark, else equal.
                if (w == firstMover && first != second + 1) return false
                if (w != firstMover && first != second) return false
            }
            return true
        }
    }
}
