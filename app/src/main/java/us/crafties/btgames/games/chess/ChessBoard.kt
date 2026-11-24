package us.crafties.btgames.games.chess

import kotlin.math.abs

enum class ChessSide(val code: Int) {
    WHITE(0),
    BLACK(1);

    fun other(): ChessSide = if (this == WHITE) BLACK else WHITE
}

/**
 * Piece identity. Codes 0..6 are white, 8..13 black (black = white + 8), so
 * [code] % 7 is the piece type for both sides. The screen draws each type as a
 * silhouette in the owner's colour (see ChessPieces.kt).
 */
enum class ChessPiece(val code: Int, val side: ChessSide?) {
    EMPTY(0, null),
    WP(1, ChessSide.WHITE),
    WN(2, ChessSide.WHITE),
    WB(3, ChessSide.WHITE),
    WR(4, ChessSide.WHITE),
    WQ(5, ChessSide.WHITE),
    WK(6, ChessSide.WHITE),
    BP(8, ChessSide.BLACK),
    BN(9, ChessSide.BLACK),
    BB(10, ChessSide.BLACK),
    BR(11, ChessSide.BLACK),
    BQ(12, ChessSide.BLACK),
    BK(13, ChessSide.BLACK);

    companion object {
        fun of(side: ChessSide, type: Int) = entries.first { it.side == side && it.code % 7 == type }
        fun from(c: Int) = entries.firstOrNull { it.code == c }
    }
}

data class ChessMove(val from: Int, val to: Int, val promotion: Int = 0)

/**
 * Standard chess. Row 0 is rank 8; columns 0..7 are files a..h.
 * [castling] holds KQkq as bits 1,2,4,8. [enPassant] is the target square, or -1.
 */
data class ChessBoard(
    val cells: List<ChessPiece> = initial(),
    val turn: ChessSide = ChessSide.WHITE,
    val castling: Int = 15,
    val enPassant: Int = -1,
    val halfmove: Int = 0,
    val fullmove: Int = 1,
) {
    fun legalMoves(): List<ChessMove> = cells.indices
        .filter { cells[it].side == turn }
        .flatMap { pseudo(it) }
        .filter { m -> !applyRaw(m).inCheck(turn) }

    fun movesFrom(square: Int) = legalMoves().filter { it.from == square }

    fun apply(move: ChessMove): ChessBoard? = if (move in legalMoves()) applyRaw(move) else null

    fun inCheck(side: ChessSide = turn): Boolean {
        val king = cells.indexOf(if (side == ChessSide.WHITE) ChessPiece.WK else ChessPiece.BK)
        return king < 0 || attacked(king, side.other())
    }

    val checkmate: Boolean get() = inCheck() && legalMoves().isEmpty()
    val stalemate: Boolean get() = !inCheck() && legalMoves().isEmpty()
    val drawByFiftyMoves: Boolean get() = halfmove >= 100

    /** Position identity for repetition: pieces, side to move, castling rights, and usable EP file. */
    fun positionKey(): String {
        val epUsable = enPassant >= 0 && cells.indices.any { i ->
            cells[i] == ChessPiece.of(turn, 1) &&
                i / 8 == (enPassant / 8) + (if (turn == ChessSide.WHITE) 1 else -1) &&
                abs(i % 8 - enPassant % 8) == 1
        }
        return buildString {
            cells.forEach { append(it.code.toString(16)) }
            append('|').append(turn.code).append('|').append(castling).append('|')
            append(if (epUsable) enPassant % 8 else '-')
        }
    }

    private fun attacked(target: Int, by: ChessSide): Boolean {
        for (i in cells.indices) if (cells[i].side == by) {
            val p = cells[i]
            val dr = target / 8 - i / 8
            val dc = target % 8 - i % 8
            if (p == ChessPiece.of(by, 2) && abs(dr) * abs(dc) == 2) return true
            if (p == ChessPiece.of(by, 1) && dr == (if (by == ChessSide.WHITE) -1 else 1) && abs(dc) == 1) return true
            if (p == ChessPiece.of(by, 6) && maxOf(abs(dr), abs(dc)) == 1) return true
            val diagonal = abs(dr) == abs(dc)
            val straight = dr == 0 || dc == 0
            val slider = (diagonal && p in listOf(ChessPiece.of(by, 3), ChessPiece.of(by, 5))) ||
                (straight && p in listOf(ChessPiece.of(by, 4), ChessPiece.of(by, 5)))
            if (slider && clear(i, target)) return true
        }
        return false
    }

    private fun clear(a: Int, b: Int): Boolean {
        val dr = (b / 8 - a / 8).compareTo(0)
        val dc = (b % 8 - a % 8).compareTo(0)
        var r = a / 8 + dr
        var c = a % 8 + dc
        while (r != b / 8 || c != b % 8) {
            if (cells[r * 8 + c] != ChessPiece.EMPTY) return false
            r += dr
            c += dc
        }
        return true
    }

    private fun pseudo(from: Int): List<ChessMove> {
        val p = cells[from]
        val side = turn
        val r = from / 8
        val c = from % 8
        val out = mutableListOf<ChessMove>()

        fun add(rr: Int, cc: Int) {
            if (rr !in 0..7 || cc !in 0..7) return
            val to = rr * 8 + cc
            if (cells[to].side != side) {
                if (p in listOf(ChessPiece.WP, ChessPiece.BP) && rr in listOf(0, 7)) {
                    listOf(5, 4, 3, 2).forEach { out += ChessMove(from, to, it) }
                } else {
                    out += ChessMove(from, to)
                }
            }
        }

        when (p.code % 7) {
            1 -> {
                val d = if (side == ChessSide.WHITE) -1 else 1
                val nr = r + d
                if (nr in 0..7 && cells[nr * 8 + c] == ChessPiece.EMPTY) {
                    add(nr, c)
                    if (r == (if (side == ChessSide.WHITE) 6 else 1) && cells[(r + 2 * d) * 8 + c] == ChessPiece.EMPTY) {
                        out += ChessMove(from, (r + 2 * d) * 8 + c)
                    }
                }
                for (dc in listOf(-1, 1)) {
                    if (nr in 0..7 && c + dc in 0..7) {
                        val to = nr * 8 + c + dc
                        if (cells[to].side == side.other() || to == enPassant) add(nr, c + dc)
                    }
                }
            }
            2 -> for ((dr, dc) in listOf(
                -2 to -1, -2 to 1, -1 to -2, -1 to 2, 1 to -2, 1 to 2, 2 to -1, 2 to 1,
            )) add(r + dr, c + dc)
            3, 4, 5 -> {
                val dirs = mutableListOf<Pair<Int, Int>>()
                if (p.code % 7 in listOf(3, 5)) dirs += listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)
                if (p.code % 7 in listOf(4, 5)) dirs += listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
                for ((dr, dc) in dirs) {
                    var rr = r + dr
                    var cc = c + dc
                    while (rr in 0..7 && cc in 0..7) {
                        val t = rr * 8 + cc
                        if (cells[t].side == side) break
                        add(rr, cc)
                        if (cells[t] != ChessPiece.EMPTY) break
                        rr += dr
                        cc += dc
                    }
                }
            }
            6 -> {
                for (dr in -1..1) for (dc in -1..1) if (dr != 0 || dc != 0) add(r + dr, c + dc)
                val home = if (side == ChessSide.WHITE) 7 else 0
                val kingRight = if (side == ChessSide.WHITE) 1 else 4
                val queenRight = if (side == ChessSide.WHITE) 2 else 8
                if (r == home && c == 4 && !inCheck(side)) {
                    if (castling and kingRight != 0 &&
                        cells[home * 8 + 5] == ChessPiece.EMPTY &&
                        cells[home * 8 + 6] == ChessPiece.EMPTY &&
                        cells[home * 8 + 7] == ChessPiece.of(side, 4) &&
                        !attacked(home * 8 + 5, side.other()) &&
                        !attacked(home * 8 + 6, side.other())
                    ) {
                        out += ChessMove(from, home * 8 + 6)
                    }
                    if (castling and queenRight != 0 &&
                        cells[home * 8 + 1] == ChessPiece.EMPTY &&
                        cells[home * 8 + 2] == ChessPiece.EMPTY &&
                        cells[home * 8 + 3] == ChessPiece.EMPTY &&
                        cells[home * 8] == ChessPiece.of(side, 4) &&
                        !attacked(home * 8 + 3, side.other()) &&
                        !attacked(home * 8 + 2, side.other())
                    ) {
                        out += ChessMove(from, home * 8 + 2)
                    }
                }
            }
        }
        return out
    }

    private fun applyRaw(m: ChessMove): ChessBoard {
        val b = cells.toMutableList()
        val p = b[m.from]
        val captured = if (m.to == enPassant && p.code % 7 == 1 && b[m.to] == ChessPiece.EMPTY) {
            m.to + (if (turn == ChessSide.WHITE) 8 else -8)
        } else {
            m.to
        }
        val taken = b[captured]
        b[m.from] = ChessPiece.EMPTY
        b[captured] = ChessPiece.EMPTY
        b[m.to] = if (m.promotion > 0) ChessPiece.of(turn, m.promotion) else p
        // Castling: slide the rook across the king.
        if (p.code % 7 == 6 && abs(m.to - m.from) == 2) {
            val row = m.from / 8
            if (m.to % 8 == 6) {
                b[row * 8 + 5] = b[row * 8 + 7]
                b[row * 8 + 7] = ChessPiece.EMPTY
            } else {
                b[row * 8 + 3] = b[row * 8]
                b[row * 8] = ChessPiece.EMPTY
            }
        }
        var rights = castling
        if (p == ChessPiece.WK) rights = rights and 12
        if (p == ChessPiece.BK) rights = rights and 3
        if (m.from == 63 || captured == 63) rights = rights and 14
        if (m.from == 56 || captured == 56) rights = rights and 13
        if (m.from == 7 || captured == 7) rights = rights and 11
        if (m.from == 0 || captured == 0) rights = rights and 7
        val ep = if (p.code % 7 == 1 && abs(m.to - m.from) == 16) (m.to + m.from) / 2 else -1
        return ChessBoard(
            cells = b,
            turn = turn.other(),
            castling = rights,
            enPassant = ep,
            halfmove = if (p.code % 7 == 1 || taken != ChessPiece.EMPTY) 0 else halfmove + 1,
            fullmove = fullmove + (if (turn == ChessSide.BLACK) 1 else 0),
        )
    }

    companion object {
        fun initial(): List<ChessPiece> {
            val b = MutableList(64) { ChessPiece.EMPTY }
            val back = listOf(4, 2, 3, 5, 6, 3, 2, 4)
            for (c in 0..7) {
                b[c] = ChessPiece.of(ChessSide.BLACK, back[c])
                b[8 + c] = ChessPiece.BP
                b[48 + c] = ChessPiece.WP
                b[56 + c] = ChessPiece.of(ChessSide.WHITE, back[c])
            }
            return b
        }

        /** Cheap structural sanity for a board pulled off the wire. */
        fun sane(cells: List<ChessPiece>, rights: Int, ep: Int): Boolean =
            cells.size == 64 && rights in 0..15 && ep in -1..63 &&
                cells.count { it == ChessPiece.WK } == 1 &&
                cells.count { it == ChessPiece.BK } == 1
    }
}
