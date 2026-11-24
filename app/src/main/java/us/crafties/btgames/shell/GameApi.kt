package us.crafties.btgames.shell

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import kotlinx.coroutines.CoroutineScope
import us.crafties.btgames.net.Side
import us.crafties.btgames.net.Transport

/**
 * What the shell needs to know about a game. Adding a game means writing one of these plus
 * a [GameSession]; nothing in the shell changes except the list in [GameRegistry].
 */
interface GameDef {
    /** Minimum number of players (including the host) for this game. Default 2. */
    val minPlayers: Int get() = 2
    /** Maximum number of players for this game. Default 2. */
    val maxPlayers: Int get() = 2
    /** Optional: create a session for a host that opened a room. By default throws.
     *  Implementations that support N‑player rooms should override.
     */
    fun createRoomSession(
        scope: CoroutineScope,
        transport: Transport,
        myNick: String,
        hostNick: String,
    ): GameSession = throw IllegalStateException("Room mode not supported for this game")

    /**
     * Can a tablet or TV host a room as the "table screen": the Gamemaster, refereeing and showing
     * everybody's cards face down, but holding no seat itself? Default no.
     */
    val supportsScreen: Boolean get() = false
    /** Session for the table screen that opened a room (only called when [supportsScreen]). */
    fun createScreenSession(
        scope: CoroutineScope,
        transport: Transport,
        myNick: String,
    ): GameSession = throw IllegalStateException("Table screen not supported for this game")

    /** Stable on-the-wire id from [us.crafties.btgames.net.GameIds]. Never reuse or renumber. */
    val id: Int
    /** Resource id of the title on the menu card. */
    @get:StringRes
    val titleRes: Int
    /** Resource id of the one plain sentence shown on the menu card. */
    @get:StringRes
    val blurbRes: Int
    /** Whether the game wants the phone held sideways (Paddle) or upright (XO). */
    val landscape: Boolean

    /**
     * Create a fresh session for one match. The session owns all game state and talks only
     * through [transport].
     *
     * [playsFirst] is true on the phone that takes the first turn / first serve. It comes from the
     * handshake: the person who asked in an ordinary request, or whoever was chosen in a mutual one.
     * Games must use this, not anything about who connected to whom.
     *
     * [mySide] is which colour this player is. It is random per match and always the opposite of the
     * other phone's, so a game must NOT assume "me = red". Draw my things in the paint that
     * [us.crafties.btgames.ui.Pal.paint] returns for [mySide] and theirs in the opposite.
     */
    fun createSession(
        scope: CoroutineScope,
        transport: Transport,
        playsFirst: Boolean,
        mySide: Side,
        myNick: String,
        theirNick: String,
    ): GameSession
}

/** One running match. Created after the handshake is accepted; discarded when it ends. */
interface GameSession {
    /** Start listening to the transport and (if host) running any game loop. */
    fun start()

    /** Stop everything. Must be safe to call more than once. */
    fun stop()

    /** Draw the whole game screen. [onLeave] returns the player to the lobby. */
    @Composable
    fun Content(onLeave: () -> Unit)
}
