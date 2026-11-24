package us.crafties.btgames.games.rps

import androidx.annotation.StringRes
import us.crafties.btgames.R

/** A throw. Codes double as the wire encoding. */
enum class Throw(val code: Int) {
    ROCK(0),
    PAPER(1),
    SCISSORS(2);

    companion object {
        fun fromCode(c: Int): Throw? = entries.firstOrNull { it.code == c }
    }
}

enum class RpsResult { WIN, LOSS, DRAW }

/** Pure rock-paper-scissors rules. Fully unit tested. */
object RpsRules {
    /** Points to take the match. */
    const val MATCH_POINT = 5

    fun outcome(mine: Throw, theirs: Throw): RpsResult = when {
        mine == theirs -> RpsResult.DRAW
        beats(mine, theirs) -> RpsResult.WIN
        else -> RpsResult.LOSS
    }

    private fun beats(a: Throw, b: Throw): Boolean = when (a) {
        Throw.ROCK -> b == Throw.SCISSORS
        Throw.PAPER -> b == Throw.ROCK
        Throw.SCISSORS -> b == Throw.PAPER
    }

    /** Resource id of the one line on how the winning throw won, for the reveal. */
    @StringRes
    fun verb(winner: Throw): Int = when (winner) {
        Throw.ROCK -> R.string.rps_verb_rock
        Throw.PAPER -> R.string.rps_verb_paper
        Throw.SCISSORS -> R.string.rps_verb_scissors
    }
}
