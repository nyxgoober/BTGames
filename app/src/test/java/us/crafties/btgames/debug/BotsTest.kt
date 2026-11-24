package us.crafties.btgames.debug

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import us.crafties.btgames.games.blocktower.BlockTowerCodec
import us.crafties.btgames.games.checkers.CheckersBoard
import us.crafties.btgames.games.checkers.CheckersCodec
import us.crafties.btgames.games.chess.ChessBoard
import us.crafties.btgames.games.chess.ChessCodec
import us.crafties.btgames.games.chess.ChessSide
import us.crafties.btgames.games.cupshuffle.CupShuffleCodec
import us.crafties.btgames.games.fourchips.Chip
import us.crafties.btgames.games.fourchips.FourChipsBoard
import us.crafties.btgames.games.fourchips.FourChipsCodec
import us.crafties.btgames.games.keymash.KeymashCodec
import us.crafties.btgames.games.keymash.KeymashMode
import us.crafties.btgames.games.last.LastCodec
import us.crafties.btgames.games.last.LastEngine
import us.crafties.btgames.games.last.LastMove
import us.crafties.btgames.games.last.LastRules
import us.crafties.btgames.games.picturematch.PictureMatchCodec
import us.crafties.btgames.games.shutthebox.ShutTheBoxCodec
import us.crafties.btgames.games.paddle.PaddleCodec
import us.crafties.btgames.games.paddle.PaddleState
import us.crafties.btgames.games.paddle.Phase
import us.crafties.btgames.games.rps.RpsCodec
import us.crafties.btgames.games.rps.Throw
import us.crafties.btgames.games.tetriminoes.TetriminoesCodec
import us.crafties.btgames.games.xo.Mark
import us.crafties.btgames.games.xo.XoBoard
import us.crafties.btgames.games.xo.XoCodec
import us.crafties.btgames.net.Control
import us.crafties.btgames.net.GameIds
import us.crafties.btgames.net.Link
import us.crafties.btgames.net.LoopbackTransport
import us.crafties.btgames.net.Peer
import us.crafties.btgames.net.Side

/**
 * Test mode without a second phone: drive each bot through the loopback and assert it speaks
 * its game's protocol back. Virtual time, so even a five-minute Tetriminoes race finishes
 * in milliseconds.
 */
class BotsTest {

    private fun playerSend(t: LoopbackTransport, payload: ByteArray) {
        t.sendControl(Control.Game(payload))
    }

    @Test fun connectingToTestModeGoesStraightToReady() = runTest {
        val t = LoopbackTransport(this)
        t.botFactory = ::makeBot
        t.prepare(GameIds.XO)
        t.connect(LoopbackTransport.fakePeer(GameIds.XO))
        val ready = t.link.value as Link.Ready
        assertEquals(LoopbackTransport.botNick(GameIds.XO), ready.peerNick)
        assertTrue(ready.playsFirst)
        t.leave()
    }

    @Test fun everyGameHasItsOwnBotName() {
        assertEquals("bt?pong", LoopbackTransport.botNick(GameIds.PADDLE))
        assertEquals("bt?xoxo", LoopbackTransport.botNick(GameIds.XO))
        assertEquals("bt?four", LoopbackTransport.botNick(GameIds.TETRIMINOES))
        assertEquals("bt?board", LoopbackTransport.botNick(GameIds.KEYMASH))
        assertEquals("bt?topple", LoopbackTransport.botNick(GameIds.BLOCK_TOWER))
        assertEquals("bt?hand", LoopbackTransport.botNick(GameIds.RPS))
        assertEquals("bt?connect", LoopbackTransport.botNick(GameIds.FOUR_CHIPS))
        assertEquals("bt?kings", LoopbackTransport.botNick(GameIds.CHECKERS))
        assertEquals("bt?cups", LoopbackTransport.botNick(GameIds.CUP_SHUFFLE))
        assertEquals("bt?castle", LoopbackTransport.botNick(GameIds.CHESS))
        assertEquals("bt?box", LoopbackTransport.botNick(GameIds.SHUT_THE_BOX))
        assertEquals("bt?cards", LoopbackTransport.botNick(GameIds.LAST))
        assertEquals("bt?pairs", LoopbackTransport.botNick(GameIds.PICTURE_MATCH))
        assertEquals(LoopbackTransport.FAKE_NICK, LoopbackTransport.botNick(GameIds.NONE))
        assertEquals("bt?xoxo", LoopbackTransport.fakePeer(GameIds.XO).nick)
    }

    @Test fun strangersAreIgnored() = runTest {
        val t = LoopbackTransport(this)
        t.prepare(GameIds.XO)
        t.connect(Peer(5, "addr", "Someone", -60))
        assertTrue(t.link.value is Link.Idle)
    }

    @Test fun xoBotAnswersWithO() = runTest {
        val t = LoopbackTransport(this)
        t.botFactory = ::makeBot
        t.prepare(GameIds.XO)
        val got = mutableListOf<Control>()
        backgroundScope.launch { t.control.collect { got.add(it) } }
        runCurrent()
        t.connect(LoopbackTransport.fakePeer())

        val afterX = XoBoard().play(4, Mark.X)!!
        playerSend(t, XoCodec.encodeState(0, afterX))
        advanceTimeBy(1000)

        val replies = got.mapNotNull { (it as? Control.Game)?.payload?.let(XoCodec::decode) }
        val states = replies.filterIsInstance<XoCodec.Msg.State>()
        assertTrue("bot should answer with a board carrying O", states.any { s ->
            s.round == 0 && s.board.cells.contains(Mark.O)
        })
        t.leave()
    }

    @Test fun xoBotAutoRematches() = runTest {
        val t = LoopbackTransport(this)
        t.botFactory = ::makeBot
        t.prepare(GameIds.XO)
        val got = mutableListOf<Control>()
        backgroundScope.launch { t.control.collect { got.add(it) } }
        runCurrent()
        t.connect(LoopbackTransport.fakePeer())

        playerSend(t, XoCodec.encodeRematch(1))
        runCurrent()

        val replies = got.mapNotNull { (it as? Control.Game)?.payload?.let(XoCodec::decode) }
        assertTrue(replies.contains(XoCodec.Msg.Rematch(1)))
        t.leave()
    }

    @Test fun fourChipsBotDropsAChip() = runTest {
        val t = LoopbackTransport(this)
        t.botFactory = ::makeBot
        t.prepare(GameIds.FOUR_CHIPS)
        val got = mutableListOf<Control>()
        backgroundScope.launch { t.control.collect { got.add(it) } }
        runCurrent()
        t.connect(LoopbackTransport.fakePeer())

        val afterFirst = FourChipsBoard().drop(3, Chip.FIRST)!!.board
        playerSend(t, FourChipsCodec.encodeState(0, afterFirst))
        advanceTimeBy(1000)

        val replies = got.mapNotNull { (it as? Control.Game)?.payload?.let(FourChipsCodec::decode) }
        val states = replies.filterIsInstance<FourChipsCodec.Msg.State>()
        assertTrue("bot should answer with a board carrying SECOND", states.any { s ->
            s.round == 0 && s.board.cells.contains(Chip.SECOND)
        })
        t.leave()
    }

    @Test fun rpsBotThrowsBack() = runTest {
        val t = LoopbackTransport(this)
        t.botFactory = ::makeBot
        t.prepare(GameIds.RPS)
        val got = mutableListOf<Control>()
        backgroundScope.launch { t.control.collect { got.add(it) } }
        runCurrent()
        t.connect(LoopbackTransport.fakePeer())

        playerSend(t, RpsCodec.encodeThrow(0, 2, Throw.ROCK))
        advanceTimeBy(1000)

        val replies = got.mapNotNull { (it as? Control.Game)?.payload?.let(RpsCodec::decode) }
        val throws = replies.filterIsInstance<RpsCodec.Msg.ThrowMsg>()
        assertTrue("bot should answer the same round", throws.any { it.match == 0 && it.round == 2 })
    }

    @Test fun blockTowerBotAcksAndPulls() = runTest {
        val t = LoopbackTransport(this)
        t.botFactory = ::makeBot
        t.prepare(GameIds.BLOCK_TOWER)
        val got = mutableListOf<Control>()
        backgroundScope.launch { t.control.collect { got.add(it) } }
        runCurrent()
        t.connect(LoopbackTransport.fakePeer())

        playerSend(t, BlockTowerCodec.encodeSeed(0, 999L))
        runCurrent()
        val acks = got.mapNotNull { (it as? Control.Game)?.payload?.let(BlockTowerCodec::decode) }
        assertTrue(acks.contains(BlockTowerCodec.Msg.Ack(0)))

        // (0, 0) is a legal opening pull on a fresh tower.
        playerSend(t, BlockTowerCodec.encodeMove(0, 0, 0, 0))
        advanceTimeBy(1500)
        val moves = got.mapNotNull { (it as? Control.Game)?.payload?.let(BlockTowerCodec::decode) }
            .filterIsInstance<BlockTowerCodec.Msg.Move>()
        assertTrue("bot should answer move 1", moves.any { it.round == 0 && it.n == 1 })
    }

    @Test fun tetriminoesBotChoosesWhenPlayerWouldNot() = runTest {
        // Player drew BLUE: round 0 belongs to RED, so the bot must pick the duration.
        val t = LoopbackTransport(this)
        t.botFactory = { tr, gameId, _ -> makeBot(tr, gameId, Side.BLUE) }
        t.prepare(GameIds.TETRIMINOES)
        val got = mutableListOf<Control>()
        backgroundScope.launch { t.control.collect { got.add(it) } }
        runCurrent()
        t.connect(LoopbackTransport.fakePeer())

        advanceTimeBy(1500)
        val choices = got.mapNotNull { (it as? Control.Game)?.payload?.let(TetriminoesCodec::decode) }
            .filterIsInstance<TetriminoesCodec.Msg.Choice>()
        assertEquals(1, choices.size)
        val duration = choices.single().durationSec
        assertTrue(duration in TetriminoesCodec.DURATIONS)

        // Let its fake race run to the whistle.
        advanceTimeBy(3000L + duration * 1000L + 8000L)
        val finals = got.mapNotNull { (it as? Control.Game)?.payload?.let(TetriminoesCodec::decode) }
            .filterIsInstance<TetriminoesCodec.Msg.Update>()
        assertTrue("bot should finish with done=true", finals.any { it.done && it.score > 0 })
    }

    @Test fun tetriminoesBotFollowsThePlayersChoice() = runTest {
        // Player drew RED: they choose, the bot must stay quiet until it sees the pick.
        val t = LoopbackTransport(this)
        t.botFactory = { tr, gameId, _ -> makeBot(tr, gameId, Side.RED) }
        t.prepare(GameIds.TETRIMINOES)
        val got = mutableListOf<Control>()
        backgroundScope.launch { t.control.collect { got.add(it) } }
        runCurrent()
        t.connect(LoopbackTransport.fakePeer())

        advanceTimeBy(5000)
        assertTrue("bot must not pick when the player chooses", got.isEmpty())

        playerSend(t, TetriminoesCodec.encodeChoice(0, 60))
        advanceTimeBy(3000L + 60 * 1000L + 8000L)
        val finals = got.mapNotNull { (it as? Control.Game)?.payload?.let(TetriminoesCodec::decode) }
            .filterIsInstance<TetriminoesCodec.Msg.Update>()
        assertTrue("bot should finish the player's race", finals.any { it.done })
    }

    @Test fun keymashBotRacesToDone() = runTest {
        // Player drew BLUE: the bot picks the mode and runs.
        val t = LoopbackTransport(this)
        t.botFactory = { tr, gameId, _ -> makeBot(tr, gameId, Side.BLUE) }
        t.prepare(GameIds.KEYMASH)
        val got = mutableListOf<Control>()
        backgroundScope.launch { t.control.collect { got.add(it) } }
        runCurrent()
        t.connect(LoopbackTransport.fakePeer())

        advanceTimeBy(1500)
        val choices = got.mapNotNull { (it as? Control.Game)?.payload?.let(KeymashCodec::decode) }
            .filterIsInstance<KeymashCodec.Msg.Choice>()
        assertEquals(1, choices.size)
        val total = if (choices.single().mode == KeymashMode.WORDS) 25 else 3

        advanceTimeBy(3000L + total * 2600L + 8000L)
        val finals = got.mapNotNull { (it as? Control.Game)?.payload?.let(KeymashCodec::decode) }
            .filterIsInstance<KeymashCodec.Msg.Update>()
        assertTrue(
            "bot should complete every prompt",
            finals.any { it.done && it.completed == total && it.total == total },
        )
    }

    @Test fun paddleBotTracksTheBall() = runTest {
        val t = LoopbackTransport(this)
        t.botFactory = ::makeBot
        t.prepare(GameIds.PADDLE)
        val heard = mutableListOf<ByteArray>()
        backgroundScope.launch { t.stream.collect { heard.add(it) } }
        runCurrent()
        t.connect(LoopbackTransport.fakePeer())

        // Park the ball near the top and let the bot chase it for a second.
        repeat(30) {
            t.sendStream(PaddleCodec.encodeState(it, PaddleState(ballY = 0.9f, phase = Phase.PLAYING)))
        }
        advanceTimeBy(1000)

        val inputs = heard.mapNotNull(PaddleCodec::decodeInput)
        assertTrue("bot should be sending paddle input", inputs.isNotEmpty())
        assertTrue("bot should have chased the ball up, got ${inputs.last()}", inputs.last() > 0.7f)
        t.leave()
    }

    @Test fun checkersBotAnswersFirstsStep() = runTest {
        val t = LoopbackTransport(this)
        t.botFactory = ::makeBot
        t.prepare(GameIds.CHECKERS)
        val got = mutableListOf<Control>()
        backgroundScope.launch { t.control.collect { got.add(it) } }
        runCurrent()
        t.connect(LoopbackTransport.fakePeer())

        // FIRST edge man (6,1) slides to (5,0).
        val afterFirst = CheckersBoard().applyStep(6 * 8 + 1, 5 * 8 + 0)!!
        playerSend(t, CheckersCodec.encodeState(0, afterFirst))
        advanceTimeBy(1500)

        val replies = got.mapNotNull { (it as? Control.Game)?.payload?.let(CheckersCodec::decode) }
        val states = replies.filterIsInstance<CheckersCodec.Msg.State>()
        assertTrue("bot should answer with its own step", states.any { s ->
            s.round == 0 && s.board.ply == 2 && s.board.turn == us.crafties.btgames.games.checkers.CheckerSide.FIRST
        })
        t.leave()
    }

    @Test fun cupShuffleBotDealsWhenItShuffles() = runTest {
        // Player drew BLUE: round 0 belongs to RED, so the bot deals.
        val t = LoopbackTransport(this)
        t.botFactory = { tr, gameId, _ -> makeBot(tr, gameId, Side.BLUE) }
        t.prepare(GameIds.CUP_SHUFFLE)
        val got = mutableListOf<Control>()
        backgroundScope.launch { t.control.collect { got.add(it) } }
        runCurrent()
        t.connect(LoopbackTransport.fakePeer())

        advanceTimeBy(2000)
        val choices = got.mapNotNull { (it as? Control.Game)?.payload?.let(CupShuffleCodec::decode) }
            .filterIsInstance<CupShuffleCodec.Msg.Choice>()
        assertEquals(1, choices.size)
        assertTrue(choices.single().cups in 3..6)

        advanceTimeBy(8000)
        val ends = got.mapNotNull { (it as? Control.Game)?.payload?.let(CupShuffleCodec::decode) }
            .filterIsInstance<CupShuffleCodec.Msg.End>()
        assertTrue("bot should call time", ends.any { it.round == 0 })

        // Guess, and the bot must reveal authoritatively.
        playerSend(t, CupShuffleCodec.encodeGuess(0, 0))
        advanceTimeBy(1000)
        val results = got.mapNotNull { (it as? Control.Game)?.payload?.let(CupShuffleCodec::decode) }
            .filterIsInstance<CupShuffleCodec.Msg.Result>()
        assertTrue("bot should answer the guess", results.any { it.round == 0 && it.guess == 0 })
        t.leave()
    }

    @Test fun cupShuffleBotGuessesWhenPlayerDeals() = runTest {
        // Player drew RED: they deal, the bot must stay quiet until time is called.
        val t = LoopbackTransport(this)
        t.botFactory = { tr, gameId, _ -> makeBot(tr, gameId, Side.RED) }
        t.prepare(GameIds.CUP_SHUFFLE)
        val got = mutableListOf<Control>()
        backgroundScope.launch { t.control.collect { got.add(it) } }
        runCurrent()
        t.connect(LoopbackTransport.fakePeer())

        advanceTimeBy(3000)
        assertTrue("bot must not deal when the player shuffles", got.isEmpty())

        playerSend(t, CupShuffleCodec.encodeChoice(0, 3, 2))
        playerSend(t, CupShuffleCodec.encodeShuffle(0, 1, listOf(1, 0, 2)))
        playerSend(t, CupShuffleCodec.encodeEnd(0, 1, 3))
        advanceTimeBy(3000)

        val guesses = got.mapNotNull { (it as? Control.Game)?.payload?.let(CupShuffleCodec::decode) }
            .filterIsInstance<CupShuffleCodec.Msg.Guess>()
        assertTrue("bot should guess after time", guesses.any { it.round == 0 && it.pos in 0..2 })
        t.leave()
    }

    @Test fun chessBotAnswersAsBlack() = runTest {
        val t = LoopbackTransport(this)
        t.botFactory = ::makeBot
        t.prepare(GameIds.CHESS)
        val got = mutableListOf<Control>()
        backgroundScope.launch { t.control.collect { got.add(it) } }
        runCurrent()
        t.connect(LoopbackTransport.fakePeer())

        // Player (White) opens 1. e4.
        val open = ChessBoard().legalMoves().first { it.from == 52 && it.to == 36 }
        val afterE4 = ChessBoard().apply(open)!!
        playerSend(t, ChessCodec.encodeState(0, afterE4))
        advanceTimeBy(2000)

        val replies = got.mapNotNull { (it as? Control.Game)?.payload?.let(ChessCodec::decode) }
        val states = replies.filterIsInstance<ChessCodec.Msg.State>()
        assertTrue("bot should answer with a legal black move", states.any { s ->
            s.round == 0 && s.board.turn == ChessSide.WHITE && s.board.fullmove == 2
        })
        t.leave()
    }

    @Test fun chessBotAutoRematches() = runTest {
        val t = LoopbackTransport(this)
        t.botFactory = ::makeBot
        t.prepare(GameIds.CHESS)
        val got = mutableListOf<Control>()
        backgroundScope.launch { t.control.collect { got.add(it) } }
        runCurrent()
        t.connect(LoopbackTransport.fakePeer())

        playerSend(t, ChessCodec.encodeRematch(1))
        runCurrent()

        val replies = got.mapNotNull { (it as? Control.Game)?.payload?.let(ChessCodec::decode) }
        assertTrue(replies.contains(ChessCodec.Msg.Rematch(1)))
        t.leave()
    }

    @Test fun shutTheBoxBotRollsOnItsTurnAndEndsIt() = runTest {
        val t = LoopbackTransport(this)
        t.botFactory = ::makeBot
        t.prepare(GameIds.SHUT_THE_BOX)
        val got = mutableListOf<Control>()
        backgroundScope.launch { t.control.collect { got.add(it) } }
        runCurrent()
        t.connect(LoopbackTransport.fakePeer())
        assertTrue(got.isEmpty())   // the player rolls first

        playerSend(t, ShutTheBoxCodec.encode(ShutTheBoxCodec.Msg.Turn(1, 0, 9)))
        advanceTimeBy(5000)

        val replies = got.mapNotNull { (it as? Control.Game)?.payload?.let(ShutTheBoxCodec::decode) }
        assertTrue("bot should have rolled or finished", replies.any { it is ShutTheBoxCodec.Msg.State || it is ShutTheBoxCodec.Msg.Done })
        // It rolls until no move is left, then passes the turn back, so there must be a Done.
        assertTrue("bot should end its turn", replies.any { it is ShutTheBoxCodec.Msg.Done && it.seat == 1 })
        t.leave()
    }

    @Test fun lastBotAnswersReadyAndPlaysOnItsTurn() = runTest {
        val t = LoopbackTransport(this)
        t.botFactory = ::makeBot
        t.prepare(GameIds.LAST)
        val got = mutableListOf<Control>()
        backgroundScope.launch { t.control.collect { got.add(it) } }
        runCurrent()
        t.connect(LoopbackTransport.fakePeer())

        val rules = LastRules()
        val engine = LastEngine(2, 77L, rules)
        playerSend(t, LastCodec.encode(LastCodec.Msg.Seed(4, 77, 2, rules)))
        runCurrent()
        val ready = got.mapNotNull { (it as? Control.Game)?.payload?.let(LastCodec::decode) }
        assertTrue(ready.contains(LastCodec.Msg.Ready(4)))

        // The host plays a legal card then hands the turn to seat 1.
        val i = engine.hands[0].indices.first { engine.canPlay(engine.hands[0][it]) }
        assertTrue(engine.play(0, i, null))
        playerSend(t, LastCodec.encode(LastCodec.Msg.Move(4, 0, LastMove.Play(0, i, null))))
        advanceTimeBy(1500)

        val replies = got.mapNotNull { (it as? Control.Game)?.payload?.let(LastCodec::decode) }
        assertTrue("bot should play or draw on its turn", replies.any { m ->
            m is LastCodec.Msg.Play || m is LastCodec.Msg.Draw
        })
        t.leave()
    }

    @Test fun lastBotSlapsASix() = runTest {
        val t = LoopbackTransport(this)
        t.botFactory = ::makeBot
        t.prepare(GameIds.LAST)
        val got = mutableListOf<Control>()
        backgroundScope.launch { t.control.collect { got.add(it) } }
        runCurrent()
        t.connect(LoopbackTransport.fakePeer())

        val rules = LastRules(slapSix = true)
        var seed = -1L
        var six = -1
        for (s in 1L..500L) {
            val e = LastEngine(2, s, rules)
            val i = e.hands[0].indices.firstOrNull {
                e.canPlay(e.hands[0][it]) && e.hands[0][it].number == 6
            }
            if (i != null) { seed = s; six = i; break }
        }
        assertTrue("a seed with a playable six", six >= 0)
        playerSend(t, LastCodec.encode(LastCodec.Msg.Seed(1, seed.toInt(), 2, rules)))
        runCurrent()
        // A Move that opens a slap round: the bot must slap without waiting for its turn.
        playerSend(
            t,
            LastCodec.encode(LastCodec.Msg.Move(1, 0, LastMove.Play(0, six, null))),
        )
        advanceTimeBy(1200)
        val replies = got.mapNotNull { (it as? Control.Game)?.payload?.let(LastCodec::decode) }
        assertTrue("bot should slap the six", replies.any { it is LastCodec.Msg.Slap && it.round == 1 })
        t.leave()
    }

    @Test fun pictureMatchBotAnswersReadyAndFlips() = runTest {
        val t = LoopbackTransport(this)
        t.botFactory = ::makeBot
        t.prepare(GameIds.PICTURE_MATCH)
        val got = mutableListOf<Control>()
        backgroundScope.launch { t.control.collect { got.add(it) } }
        runCurrent()
        t.connect(LoopbackTransport.fakePeer())

        playerSend(t, PictureMatchCodec.encode(PictureMatchCodec.Msg.Seed(1, 6, 42)))
        runCurrent()
        val replies = got.mapNotNull { (it as? Control.Game)?.payload?.let(PictureMatchCodec::decode) }
        assertTrue(replies.contains(PictureMatchCodec.Msg.Ready(1)))

        // Give the bot a state snapshot with the turn on seat 1.
        playerSend(
            t,
            PictureMatchCodec.encode(
                PictureMatchCodec.Msg.State(1, 6, 1, IntArray(6), -1, -1, ByteArray(9)),
            ),
        )
        advanceTimeBy(1500)
        val picks = got.mapNotNull { (it as? Control.Game)?.payload?.let(PictureMatchCodec::decode) }
            .filterIsInstance<PictureMatchCodec.Msg.Pick>()
        assertTrue("bot should flip a tile on its turn", picks.any { it.match == 1 && it.tile in 0..35 })
        t.leave()
    }

    @Test fun leaveResetsTheLink() = runTest {        val t = LoopbackTransport(this)
        t.botFactory = ::makeBot
        t.prepare(GameIds.XO)
        t.connect(LoopbackTransport.fakePeer())
        assertTrue(t.link.value is Link.Ready)
        t.leave()
        assertTrue(t.link.value is Link.Idle)
    }

    // (Each test launches its own collector on the test scope's backgroundScope.)

    @Test fun unknownGameHasNoBot() = runTest {
        val t = LoopbackTransport(this)
        t.botFactory = ::makeBot
        t.prepare(99)
        t.connect(LoopbackTransport.fakePeer())
        // Still Ready (the shell can show the match); there is just nobody home.
        assertTrue(t.link.value is Link.Ready)
    }
}
