package us.crafties.btgames.games.keymash

import kotlinx.coroutines.CoroutineScope
import us.crafties.btgames.net.GameIds
import us.crafties.btgames.net.Side
import us.crafties.btgames.R
import us.crafties.btgames.net.Transport
import us.crafties.btgames.shell.GameDef
import us.crafties.btgames.shell.GameSession

object KeymashGame : GameDef {
    override val id = GameIds.KEYMASH
    override val titleRes = R.string.g_key_title
    override val blurbRes = R.string.g_key_blurb
    override val landscape = false

    override fun createSession(
        scope: CoroutineScope,
        transport: Transport,
        playsFirst: Boolean,
        mySide: Side,
        myNick: String,
        theirNick: String,
    ): GameSession = KeymashSession(
        scope = scope,
        transport = transport,
        playsFirst = playsFirst,
        mySide = mySide,
        myNick = myNick,
        theirNick = theirNick,
    )
}
