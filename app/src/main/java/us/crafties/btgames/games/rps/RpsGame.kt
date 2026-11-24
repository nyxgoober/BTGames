package us.crafties.btgames.games.rps

import kotlinx.coroutines.CoroutineScope
import us.crafties.btgames.net.GameIds
import us.crafties.btgames.net.Side
import us.crafties.btgames.R
import us.crafties.btgames.net.Transport
import us.crafties.btgames.shell.GameDef
import us.crafties.btgames.shell.GameSession

object RpsGame : GameDef {
    override val id = GameIds.RPS
    override val titleRes = R.string.g_rps_title
    override val blurbRes = R.string.g_rps_blurb
    override val landscape = false

    override fun createSession(
        scope: CoroutineScope,
        transport: Transport,
        playsFirst: Boolean,
        mySide: Side,
        myNick: String,
        theirNick: String,
    ): GameSession = RpsSession(
        scope = scope,
        transport = transport,
        playsFirst = playsFirst,
        mySide = mySide,
        myNick = myNick,
        theirNick = theirNick,
    )
}
