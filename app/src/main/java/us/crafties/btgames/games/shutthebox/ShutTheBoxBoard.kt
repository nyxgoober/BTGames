package us.crafties.btgames.games.shutthebox

/** Tile counts a match can use: the classic 1..9 box or the bigger 1..12 box. */
object StbTiles {
    const val NINE = 9
    const val TWELVE = 12
    fun valid(n: Int) = n == NINE || n == TWELVE
}

/** Shut-The-Box board for one player with tiles 1..[count]. Bit n-1 of the mask set = tile n is shut. */
class ShutTheBoxBoard(private var mask: Int = 0, val count: Int = StbTiles.NINE) {

    fun isClosed(num: Int): Boolean = num in 1..count && mask and (1 shl (num - 1)) != 0

    fun availableNumbers(): List<Int> = (1..count).filter { !isClosed(it) }

    fun closedMask(): Int = mask

    /** True once every tile is shut: the box is shut. */
    fun isShut(): Boolean = mask == (1 shl count) - 1

    /** Classic rule: once 7 through the last tile are all shut, the player rolls a single die. */
    fun rollsOneDie(): Boolean = (7..count).all { isClosed(it) }

    fun canClose(nums: List<Int>, roll: Int): Boolean {
        if (nums.isEmpty() || nums.sum() != roll) return false
        if (nums.toSet().size != nums.size) return false
        return nums.all { it in 1..count && !isClosed(it) }
    }

    /** Is there any combination of open tiles adding up to [roll]? */
    fun hasMove(roll: Int): Boolean {
        val open = availableNumbers()
        fun search(i: Int, left: Int): Boolean {
            if (left == 0) return true
            if (i >= open.size || left < 0) return false
            return search(i + 1, left - open[i]) || search(i + 1, left)
        }
        return search(0, roll)
    }

    fun applyClose(nums: List<Int>) {
        for (n in nums) if (n in 1..count) mask = mask or (1 shl (n - 1))
    }

    /** Sum of the tiles still open: the score (lower is better). */
    fun score(): Int = availableNumbers().sum()

    companion object {
        fun scoreOf(mask: Int, count: Int): Int = ShutTheBoxBoard(mask, count).score()
    }
}
