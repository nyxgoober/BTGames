package us.crafties.btgames.games.shutthebox

import kotlinx.coroutines.CoroutineScope
import us.crafties.btgames.net.GameIds
import us.crafties.btgames.net.Side
import us.crafties.btgames.R
import us.crafties.btgames.net.Transport
import us.crafties.btgames.shell.GameDef
import us.crafties.btgames.shell.GameSession

/**
 * Shut‑The‑Box – a dice game where each player tries to shut the tiles 1‑9.
 * The lowest final sum wins. Supports 2‑6 players (N‑player room).
 */
object ShutTheBoxGame : GameDef {
    override val id = GameIds.SHUT_THE_BOX
    override val titleRes = R.string.g_shut_the_box_title
    override val blurbRes = R.string.g_shut_the_box_blurb
    override val landscape = false
    override val minPlayers: Int get() = 2
    override val maxPlayers: Int get() = 6

    // 2‑player fallback – same session works for both modes.
    override fun createSession(
        scope: CoroutineScope,
        transport: Transport,
        playsFirst: Boolean,
        mySide: Side,
        myNick: String,
        theirNick: String,
    ): GameSession = ShutTheBoxSession(
        scope = scope,
        transport = transport,
        hostGuess = playsFirst, // In a 2‑player match, the player who plays first is seat 0.
        myNick = myNick,
        otherNick = theirNick,
        twoPlayer = true,
    )

    override val supportsScreen: Boolean get() = true

    override fun createScreenSession(
        scope: CoroutineScope,
        transport: Transport,
        myNick: String,
    ): GameSession = ShutTheBoxSession(
        scope = scope,
        transport = transport,
        hostGuess = true,
        myNick = myNick,
        otherNick = myNick,
        twoPlayer = false,
        screenHost = true,
    )

    // N‑player room – host creates the session; clients use the same session class.
    override fun createRoomSession(
        scope: CoroutineScope,
        transport: Transport,
        myNick: String,
        hostNick: String,
    ): GameSession = ShutTheBoxSession(
        scope = scope,
        transport = transport,
        hostGuess = myNick == hostNick,
        myNick = myNick,
        otherNick = hostNick,
        twoPlayer = false,
    )
}
