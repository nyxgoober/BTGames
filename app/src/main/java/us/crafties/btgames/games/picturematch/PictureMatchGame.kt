package us.crafties.btgames.games.picturematch

import kotlinx.coroutines.CoroutineScope
import us.crafties.btgames.R
import us.crafties.btgames.net.GameIds
import us.crafties.btgames.net.Side
import us.crafties.btgames.net.Transport
import us.crafties.btgames.shell.GameDef
import us.crafties.btgames.shell.GameSession

/** Picture Match: a competitive memory game over a plain two-player link. */
object PictureMatchGame : GameDef {
    override val id = GameIds.PICTURE_MATCH
    override val titleRes = R.string.g_pm_title
    override val blurbRes = R.string.g_pm_blurb
    override val landscape = false
    override val minPlayers: Int get() = 2
    override val maxPlayers: Int get() = PictureIcons.MAX_PLAYERS

    override fun createSession(
        scope: CoroutineScope,
        transport: Transport,
        playsFirst: Boolean,
        mySide: Side,
        myNick: String,
        theirNick: String,
    ): GameSession = PictureMatchSession(scope, transport, playsFirst, mySide, myNick, theirNick)

    override fun createRoomSession(
        scope: CoroutineScope,
        transport: Transport,
        myNick: String,
        hostNick: String,
    ): GameSession = PictureMatchSession(
        scope, transport,
        playsFirst = myNick == hostNick,
        mySide = Side.RED,
        myNick = myNick,
        otherNick = hostNick,
        twoPlayer = false,
    )
}
