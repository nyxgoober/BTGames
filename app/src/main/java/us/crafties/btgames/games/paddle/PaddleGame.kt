package us.crafties.btgames.games.paddle

import kotlinx.coroutines.CoroutineScope
import us.crafties.btgames.net.GameIds
import us.crafties.btgames.net.Side
import us.crafties.btgames.R
import us.crafties.btgames.net.Transport
import us.crafties.btgames.shell.GameDef
import us.crafties.btgames.shell.GameSession

object PaddleGame : GameDef {
    override val id = GameIds.PADDLE
    override val titleRes = R.string.g_paddle_title
    override val blurbRes = R.string.g_paddle_blurb
    override val landscape = true

    override fun createSession(
        scope: CoroutineScope,
        transport: Transport,
        playsFirst: Boolean,
        mySide: Side,
        myNick: String,
        theirNick: String,
    ): GameSession = PaddleSession(
        scope = scope,
        transport = transport,
        // The engine serves the first ball toward the host, so whoever plays first hosts the physics.
        isHost = playsFirst,
        mySide = mySide,
        myNick = myNick,
        theirNick = theirNick,
    )
}
