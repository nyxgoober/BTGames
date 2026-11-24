package us.crafties.btgames.games.checkers

/** A square: empty, or a man/king of whoever moves first / second this round. Values are the wire codes. */
enum class Piece(val code: Int) {
    EMPTY(0), FIRST_MAN(1), FIRST_KING(2), SECOND_MAN(3), SECOND_KING(4);

    fun side(): CheckerSide? = when (this) {
        FIRST_MAN, FIRST_KING -> CheckerSide.FIRST
        SECOND_MAN, SECOND_KING -> CheckerSide.SECOND
        EMPTY -> null
    }

    fun isKing(): Boolean = this == FIRST_KING || this == SECOND_KING

    companion object {
        fun fromCode(c: Int): Piece? = entries.firstOrNull { it.code == c }
    }
}

/** The two players, as marks. Colour is separate (random per match). Values are the wire codes. */
enum class CheckerSide(val code: Int) {
    FIRST(1), SECOND(2);

    fun other(): CheckerSide = if (this == FIRST) SECOND else FIRST

    fun man(): Piece = if (this == FIRST) Piece.FIRST_MAN else Piece.SECOND_MAN

    companion object {
        fun fromCode(c: Int): CheckerSide? = entries.firstOrNull { it.code == c }
    }
}

/** One step: a piece slides or jumps from [from] to [to] (cell indices 0..63). */
data class Step(val from: Int, val to: Int)

/**
 * Immutable Thai-draughts (Makhos) board. Cells are indexed row * 8 + col, row 0 at the top.
 * Only dark squares ((row + col) odd) are ever occupied; light squares are always EMPTY.
 *
 * Setup: 8 men a side on the two nearest rows — SECOND on rows 0..1 (top, moving down),
 * FIRST on rows 6..7 (bottom, moving up). FIRST moves first in even rounds, SECOND in odd
 * ones (see the session).
 *
 * Rules: men step one square diagonally forward; they jump (capture) diagonally forward only.
 * Jumps are compulsory, with free choice among them (no majority rule). Chained captures keep
 * the turn: while [pending] is set, the same piece must keep jumping. A man reaching the far
 * rank is crowned and stops, even mid-capture. Kings ("hot") fly: they slide any distance
 * diagonally, and capture from any distance but MUST land on the square immediately behind
 * the taken piece, continuing while another capture is available from there.
 * A side with no pieces, or no legal step on its turn, loses. There are no draws.
 *
 * [turn] is stored, not derived, because a chained capture keeps the move with one side.
 * [ply] counts applied steps so a receiver can tell a fresh board from a stale one.
 */
data class CheckersBoard(
    val cells: List<Piece> = initialCells(),
    val firstMover: CheckerSide = CheckerSide.FIRST,
    val turn: CheckerSide = firstMover,
    /** Cell of the piece mid-chain, or -1 when the turn is free. */
    val pending: Int = -1,
    val ply: Int = 0,
) {
    init {
        require(cells.size == 64) { "board must have 64 cells" }
    }

    val isOver: Boolean get() = winner() != null

    /** The winning side, or null while play continues. */
    fun winner(): CheckerSide? {
        val first = cells.count { it.side() == CheckerSide.FIRST }
        val second = cells.count { it.side() == CheckerSide.SECOND }
        if (first == 0 && second == 0) return null // not reachable in play; refuse to call it
        if (first == 0) return CheckerSide.SECOND
        if (second == 0) return CheckerSide.FIRST
        if (!hasAnyStep()) return turn.other()
        return null
    }

    /** True if the side to move has any legal step (a jump, or a slide when free). */
    private fun hasAnyStep(): Boolean {
        if (pending >= 0) return jumpsOf(pending, cells[pending]).isNotEmpty()
        for (i in cells.indices) {
            if (cells[i].side() != turn) continue
            if (jumpsOf(i, cells[i]).isNotEmpty()) return true
        }
        // Jumps are mandatory: slides only count when no jump exists anywhere.
        for (i in cells.indices) {
            if (cells[i].side() == turn && slidesOf(i).isNotEmpty()) return true
        }
        return false
    }

    /** Every board one legal step from here leads to. Empty when the game is over. */
    fun nextBoards(): List<CheckersBoard> {
        if (winner() != null) return emptyList()
        val jumps = allJumps()
        val steps = if (pending >= 0) {
            jumps.filter { it.from == pending }
        } else {
            if (jumps.isNotEmpty()) jumps else allSlides()
        }
        return steps.mapNotNull { applyStep(it.from, it.to) }
    }

    /** Legal destinations for the piece on [cell], honouring mandatory capture. */
    fun stepsFrom(cell: Int): List<Step> {
        if (winner() != null || cell !in 0..63) return emptyList()
        if (cells[cell].side() != turn) return emptyList()
        val jumps = allJumps()
        return if (pending >= 0) {
            if (cell != pending) emptyList() else jumps.filter { it.from == cell }
        } else {
            val mine = jumps.filter { it.from == cell }
            if (jumps.isNotEmpty()) mine else slidesOf(cell).map { Step(cell, it) }
        }
    }

    /**
     * Applies one step, or null if it is illegal (wrong turn, blocked path, a slide while a
     * jump is available, or breaking a chain). Both phones run the same check, so a received
     * board is trusted only if it equals one of [nextBoards].
     */
    fun applyStep(from: Int, to: Int): CheckersBoard? {
        if (winner() != null) return null
        if (from !in 0..63 || to !in 0..63 || !isDark(to)) return null
        val piece = cells[from]
        if (piece.side() != turn || cells[to] != Piece.EMPTY) return null
        if (pending >= 0 && from != pending) return null
        val dr = to / 8 - from / 8
        val dc = to % 8 - from % 8
        if (dr == 0 || kotlin.math.abs(dr) != kotlin.math.abs(dc)) return null
        if (!piece.isKing()) return applyManStep(from, to, piece, dr, dc)
        return applyKingStep(from, to, piece, dr, dc)
    }

    /** One step of a man: a single slide forward, or a jump over the adjacent enemy forward. */
    private fun applyManStep(from: Int, to: Int, piece: Piece, dr: Int, dc: Int): CheckersBoard? {
        val forward = if (turn == CheckerSide.FIRST) -1 else 1
        val next = cells.toMutableList()
        if (kotlin.math.abs(dr) == 1 && kotlin.math.abs(dc) == 1) {
            // A slide: only when free (no chain, no jump anywhere) and heading the right way.
            if (pending >= 0 || allJumps().isNotEmpty()) return null
            if (dr != forward) return null
            next[from] = Piece.EMPTY
            next[to] = crowned(piece, to)
            return copy(cells = next, turn = turn.other(), pending = -1, ply = ply + 1)
        }
        if (kotlin.math.abs(dr) == 2 && kotlin.math.abs(dc) == 2) {
            if (dr != 2 * forward) return null
            val mid = (from + to) / 2 // adjacent diagonally, so the midpoint is the jumped square
            val victim = cells[mid]
            if (victim.side() != turn.other()) return null
            next[from] = Piece.EMPTY
            next[mid] = Piece.EMPTY
            val moved = crowned(piece, to)
            next[to] = moved
            // A freshly crowned man stops even mid-capture; otherwise the chain continues
            // while this piece has another jump on the board after it (victim already gone).
            val promoted = !piece.isKing() && moved.isKing()
            val continues = !promoted && jumpsOn(next, to, moved).isNotEmpty()
            return if (continues) {
                copy(cells = next, turn = turn, pending = to, ply = ply + 1)
            } else {
                copy(cells = next, turn = turn.other(), pending = -1, ply = ply + 1)
            }
        }
        return null
    }

    /**
     * One step of a flying king: a slide of any distance along a clear diagonal, or a capture
     * that lands on the square immediately behind a single enemy with a clear path to it.
     */
    private fun applyKingStep(from: Int, to: Int, piece: Piece, dr: Int, dc: Int): CheckersBoard? {
        val rStep = Integer.signum(dr)
        val cStep = Integer.signum(dc)
        // Squares strictly between, from nearest to farthest.
        val between = mutableListOf<Int>()
        var r = from / 8 + rStep
        var c = from % 8 + cStep
        while (r != to / 8 || c != to % 8) {
            between += r * 8 + c
            r += rStep
            c += cStep
        }
        val blockers = between.filter { cells[it] != Piece.EMPTY }
        val next = cells.toMutableList()
        if (blockers.isEmpty()) {
            // A slide: only when free (no chain, no jump anywhere).
            if (pending >= 0 || allJumps().isNotEmpty()) return null
            next[from] = Piece.EMPTY
            next[to] = piece
            return copy(cells = next, turn = turn.other(), pending = -1, ply = ply + 1)
        }
        // A capture: exactly one enemy between, and the landing directly behind it.
        if (blockers.size != 1) return null
        val victim = blockers.single()
        if (cells[victim].side() != turn.other()) return null
        if (victim + rStep * 8 + cStep != to) return null
        next[from] = Piece.EMPTY
        next[victim] = Piece.EMPTY
        next[to] = piece
        val continues = jumpsOn(next, to, piece).isNotEmpty()
        return if (continues) {
            copy(cells = next, turn = turn, pending = to, ply = ply + 1)
        } else {
            copy(cells = next, turn = turn.other(), pending = -1, ply = ply + 1)
        }
    }

    private fun allJumps(): List<Step> {
        val cellsToTry = if (pending >= 0) listOf(pending) else cells.indices.filter { cells[it].side() == turn }
        return cellsToTry.flatMap { from -> jumpsOf(from, cells[from]).map { Step(from, it) } }
    }

    private fun allSlides(): List<Step> =
        cells.indices.filter { cells[it].side() == turn }
            .flatMap { from -> slidesOf(from).map { Step(from, it) } }

    /** Landing squares of captures available to [piece] on [from]. */
    private fun jumpsOf(from: Int, piece: Piece): List<Int> = jumpsOn(cells, from, piece)

    /**
     * Captures available to [piece] on [from] against the squares [c]. Split out so a step can
     * test continuation on the board *after* it (with the victim already gone), not before.
     */
    private fun jumpsOn(c: List<Piece>, from: Int, piece: Piece): List<Int> {
        if (piece == Piece.EMPTY) return emptyList()
        if (!piece.isKing()) return manJumpsOn(c, from, piece)
        // A flying king takes the first enemy along each diagonal, landing directly behind it.
        val r = from / 8
        val cc = from % 8
        return listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1).mapNotNull { (rStep, cStep) ->
            var nr = r + rStep
            var nc = cc + cStep
            while (nr in 0..7 && nc in 0..7 && c[nr * 8 + nc] == Piece.EMPTY) {
                nr += rStep
                nc += cStep
            }
            if (nr !in 0..7 || nc !in 0..7) return@mapNotNull null
            if (c[nr * 8 + nc].side() != piece.side()?.other()) return@mapNotNull null
            val landR = nr + rStep
            val landC = nc + cStep
            if (landR !in 0..7 || landC !in 0..7) return@mapNotNull null
            if (c[landR * 8 + landC] != Piece.EMPTY) return@mapNotNull null
            landR * 8 + landC
        }
    }

    /** Landing squares of a man's forward captures (never backwards). */
    private fun manJumpsOf(from: Int, piece: Piece): List<Int> = manJumpsOn(cells, from, piece)

    private fun manJumpsOn(c: List<Piece>, from: Int, piece: Piece): List<Int> {
        val r = from / 8
        val cc = from % 8
        val f = if (piece.side() == CheckerSide.FIRST) -2 else 2
        return listOf(f to -2, f to 2).mapNotNull { (dr, dc) ->
            val nr = r + dr
            val nc = cc + dc
            if (nr !in 0..7 || nc !in 0..7) return@mapNotNull null
            val to = nr * 8 + nc
            val mid = (from + to) / 2
            if (c[to] == Piece.EMPTY && c[mid].side() == piece.side()?.other()) to else null
        }
    }

    private fun slidesOf(from: Int): List<Int> {
        val piece = cells[from]
        if (piece == Piece.EMPTY) return emptyList()
        val r = from / 8
        val c = from % 8
        if (!piece.isKing()) {
            val f = if (piece.side() == CheckerSide.FIRST) -1 else 1
            return listOf(f to -1, f to 1).mapNotNull { (dr, dc) ->
                val nr = r + dr
                val nc = c + dc
                if (nr !in 0..7 || nc !in 0..7) return@mapNotNull null
                val to = nr * 8 + nc
                if (cells[to] == Piece.EMPTY) to else null
            }
        }
        // A flying king slides to every empty square along each diagonal.
        return listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1).flatMap { (rStep, cStep) ->
            val landings = mutableListOf<Int>()
            var nr = r + rStep
            var nc = c + cStep
            while (nr in 0..7 && nc in 0..7 && cells[nr * 8 + nc] == Piece.EMPTY) {
                landings += nr * 8 + nc
                nr += rStep
                nc += cStep
            }
            landings
        }
    }

    private fun crowned(piece: Piece, to: Int): Piece {
        if (piece.isKing()) return piece
        val lastRank = if (piece.side() == CheckerSide.FIRST) 0 else 7
        if (to / 8 != lastRank) return piece
        return if (piece.side() == CheckerSide.FIRST) Piece.FIRST_KING else Piece.SECOND_KING
    }

    companion object {
        fun isDark(cell: Int): Boolean {
            val r = cell / 8
            val c = cell % 8
            return (r + c) % 2 == 1
        }

        fun initialCells(): List<Piece> = List(64) { i ->
            if (!isDark(i)) return@List Piece.EMPTY
            val r = i / 8
            when {
                r <= 1 -> Piece.SECOND_MAN
                r >= 6 -> Piece.FIRST_MAN
                else -> Piece.EMPTY
            }
        }

        /**
         * A board received from the network is only trusted if it is sane: light squares empty,
         * piece counts within a game that started 8-vs-8, and a pending piece that belongs to
         * the side to move. Full move legality is the session's check ([nextBoards] membership).
         */
        fun isSane(cells: List<Piece>, turn: CheckerSide, pending: Int): Boolean {
            if (cells.size != 64) return false
            if (cells.indices.any { !isDark(it) && cells[it] != Piece.EMPTY }) return false
            if (cells.count { it.side() == CheckerSide.FIRST } > 8) return false
            if (cells.count { it.side() == CheckerSide.SECOND } > 8) return false
            if (pending != -1) {
                if (pending !in 0..63) return false
                if (cells[pending].side() != turn) return false
            }
            return true
        }
    }
}
