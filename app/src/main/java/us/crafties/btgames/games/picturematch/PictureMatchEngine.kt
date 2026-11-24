package us.crafties.btgames.games.picturematch

import kotlin.random.Random

/**
 * One pool of pictures: fruits, vegetables and devices all mixed into every board. Emoji are
 * drawn by the phone's own emoji font, so there are no image assets to ship.
 */
object PictureIcons {
    val FRUITS = listOf(
        "🍎", "🍌", "🍇", "🍓", "🍊", "🍋", "🍒", "🍑",
        "🍍", "🥝", "🍉", "🍐", "🥥", "🫐", "🍈", "🍏",
    )
    val VEGGIES = listOf(
        "🥕", "🍅", "🥦", "🌽", "🥒", "🫑", "🥬", "🧄",
        "🧅", "🥔", "🍆", "🌶️", "🥑", "🍠", "🫛", "🍄",
    )
    val DEVICES = listOf(
        "📱", "💻", "⌚", "📷", "🎧", "📲", "🖥️", "🖱️",
        "⌨️", "🔋", "📠", "🎮", "📞", "💾", "🖨️", "📺",
    )
    val ALL: List<String> = FRUITS + VEGGIES + DEVICES

    val VALID_GRIDS = listOf(6, 8, 10)
    const val MAX_PLAYERS = 6
}

enum class TileState(val code: Int) {
    HIDDEN(0),
    /** Turned up as a temporary peek, waiting for its partner. */
    PEEK(1),
    /** Permanently matched. */
    MATCHED(2);

    companion object {
        fun of(code: Int): TileState = entries.firstOrNull { it.code == code } ?: HIDDEN
    }
}

enum class PickResult { ILLEGAL, FIRST, MATCH, MISS_OPEN }

/**
 * Pure rules for Picture Match. Both phones build the same shuffled deck from the host's seed;
 * after that only tile states, scores and the turn are shared. The host owns the authoritative
 * copy; clients apply its packed board snapshots.
 */
class PictureMatchEngine(
    val grid: Int,
    seed: Int,
    val players: Int,
) {
    val size: Int = grid * grid
    /** Emoji for every tile. Equal emoji form a pair. */
    val icons: List<String>

    var tiles: MutableList<TileState> = MutableList(size) { TileState.HIDDEN }
        private set
    var pending: Int = -1
        private set
    var last: Int = -1
        private set
    var missOpen: Boolean = false
        private set
    var turn: Int = 0
        private set
    var scores: IntArray = IntArray(players)
        private set

    val matched: Int get() = tiles.count { it == TileState.MATCHED }
    val isOver: Boolean get() = matched == size
    val pairCount: Int get() = size / 2

    init {
        require(grid in PictureIcons.VALID_GRIDS) { "grid must be 6, 8 or 10" }
        require(players in 2..PictureIcons.MAX_PLAYERS) { "players must be 2..6" }
        val pool = PictureIcons.ALL
        // Spread the picks across the whole pool so even a 6×6 board shows fruits, vegetables
        // and devices instead of just the first few fruits.
        val pairs = (0 until pairCount).map { k ->
            pool[(k.toLong() * pool.size / pairCount).toInt() % pool.size]
        }
        val deck = pairs.flatMap { listOf(it, it) }.toMutableList()
        deck.shuffle(Random(seed.toLong()))
        icons = deck
    }

    /** Turn a hidden tile up. A correct pair keeps the turn; a miss is resolved later. */
    fun pick(seat: Int, tile: Int): PickResult {
        if (isOver || missOpen || seat !in 0 until players || turn != seat ||
            tile !in 0 until size || tiles[tile] != TileState.HIDDEN
        ) return PickResult.ILLEGAL
        tiles[tile] = TileState.PEEK
        if (pending < 0) {
            pending = tile
            return PickResult.FIRST
        }
        if (icons[pending] == icons[tile]) {
            tiles[pending] = TileState.MATCHED
            tiles[tile] = TileState.MATCHED
            scores[seat]++
            pending = -1
            last = -1
            missOpen = false
            return PickResult.MATCH
        }
        last = tile
        missOpen = true
        return PickResult.MISS_OPEN
    }

    /** Hide the two unmatched tiles and hand the turn to the next player. */
    fun resolveMiss() {
        if (!missOpen) return
        tiles[pending] = TileState.HIDDEN
        tiles[last] = TileState.HIDDEN
        pending = -1
        last = -1
        missOpen = false
        turn = (turn + 1) % players
    }

    /** Two bits per tile, least significant first. */
    fun packedTiles(): ByteArray {
        val out = ByteArray((size + 3) / 4)
        for (i in 0 until size) {
            out[i / 4] = (out[i / 4].toInt() or (tiles[i].code shl ((i % 4) * 2))).toByte()
        }
        return out
    }

    /** Apply an authoritative snapshot sent by the host. */
    fun applyState(newTurn: Int, newScores: IntArray, newPending: Int, newLast: Int, packed: ByteArray) {
        turn = newTurn
        scores = newScores.copyOf()
        pending = newPending
        last = newLast
        missOpen = newPending >= 0 && newLast >= 0
        for (i in 0 until size) {
            val byte = packed.getOrElse(i / 4) { 0 }.toInt() and 0xFF
            tiles[i] = TileState.of((byte shr ((i % 4) * 2)) and 0x3)
        }
    }
}
