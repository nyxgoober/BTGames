package us.crafties.btgames.games.last

import kotlinx.coroutines.CoroutineScope
import us.crafties.btgames.R
import us.crafties.btgames.net.GameIds
import us.crafties.btgames.net.Side
import us.crafties.btgames.net.Transport
import us.crafties.btgames.shell.GameDef
import us.crafties.btgames.shell.GameSession

/**
 * Last! – a shedding card game: match the top card by colour, number or action, call "Last!" at
 * one card, and be first to empty your hand. 2 players over a plain link, 2-6 in a room.
 * Cards, names and look are this app's own (flat cards, Ban / Spin / Plus2 / Color Change).
 */
object LastGame : GameDef {
    override val id = GameIds.LAST
    override val titleRes = R.string.g_last_title
    override val blurbRes = R.string.g_last_blurb
    override val landscape = false
    override val minPlayers: Int get() = LastEngine.MIN_PLAYERS
    override val maxPlayers: Int get() = LastEngine.MAX_PLAYERS

    override val supportsScreen: Boolean get() = true

    override fun createScreenSession(
        scope: CoroutineScope,
        transport: Transport,
        myNick: String,
    ): GameSession = LastSession(
        scope = scope,
        transport = transport,
        hostGuess = true,
        myNick = myNick,
        otherNick = myNick,
        twoPlayer = false,
        screenHost = true,
    )

    override fun createSession(
        scope: CoroutineScope,
        transport: Transport,
        playsFirst: Boolean,
        mySide: Side,
        myNick: String,
        theirNick: String,
    ): GameSession = LastSession(
        scope = scope,
        transport = transport,
        hostGuess = playsFirst,   // the phone that plays first is seat 0, the host
        myNick = myNick,
        otherNick = theirNick,
        twoPlayer = true,
    )

    override fun createRoomSession(
        scope: CoroutineScope,
        transport: Transport,
        myNick: String,
        hostNick: String,
    ): GameSession = LastSession(
        scope = scope,
        transport = transport,
        hostGuess = myNick == hostNick,
        myNick = myNick,
        otherNick = hostNick,
        twoPlayer = false,
    )
}
