package us.crafties.btgames.shell

import us.crafties.btgames.games.blocktower.BlockTowerGame
import us.crafties.btgames.games.checkers.CheckersGame
import us.crafties.btgames.games.chess.ChessGame
import us.crafties.btgames.games.cupshuffle.CupShuffleGame
import us.crafties.btgames.games.shutthebox.ShutTheBoxGame
import us.crafties.btgames.games.fourchips.FourChipsGame
import us.crafties.btgames.games.keymash.KeymashGame
import us.crafties.btgames.games.last.LastGame
import us.crafties.btgames.games.paddle.PaddleGame
import us.crafties.btgames.games.picturematch.PictureMatchGame
import us.crafties.btgames.games.rps.RpsGame
import us.crafties.btgames.games.tetriminoes.TetriminoesGame
import us.crafties.btgames.games.xo.XoGame

/** Add a game here and it appears on the main menu. Order is display order. */
object GameRegistry {
    val all: List<GameDef> = listOf(PaddleGame, XoGame, TetriminoesGame, KeymashGame, BlockTowerGame, RpsGame, FourChipsGame, CheckersGame, ChessGame, CupShuffleGame, ShutTheBoxGame, LastGame, PictureMatchGame)

    fun byId(id: Int): GameDef? = all.firstOrNull { it.id == id }
}
