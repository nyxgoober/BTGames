package us.crafties.btgames.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * Small pieces of English that have to agree with a player's name. Pure Kotlin so the rules can be
 * unit tested.
 */
object Wording {

    /**
     * A sentence subject and its verb: "Sam wins", but "They win" when there is no name to use.
     * A name takes the -s form and "they" does not, which is easy to get wrong when the subject
     * is filled in from a variable.
     *
     * @param plural   the verb after "they": "win", "want".
     * @param singular the verb after a name: "wins", "wants".
     */
    fun subject(nick: String, plural: String, singular: String): String =
        if (nick.isBlank()) "They $plural" else "${nick.trim()} $singular"

    /**
     * The localized version: [namedRes] holds a `%1$s` for the name ("Sam wins"), [anonRes]
     * is the no-name fallback ("They win"). Each language carries both forms, since grammars
     * differ (no -s agreement outside English).
     */
    @Composable
    fun subject(nick: String, @StringRes namedRes: Int, @StringRes anonRes: Int): String {
        val n = nick.trim()
        return if (n.isEmpty()) stringResource(anonRes) else stringResource(namedRes, n)
    }
}
