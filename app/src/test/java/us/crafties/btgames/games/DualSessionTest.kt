package us.crafties.btgames.games

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import us.crafties.btgames.games.blocktower.BlockTowerCodec
import us.crafties.btgames.games.blocktower.BlockTowerSession
import us.crafties.btgames.games.checkers.CheckersSession
import us.crafties.btgames.games.checkers.Piece
import us.crafties.btgames.games.chess.ChessPiece
import us.crafties.btgames.games.chess.ChessSession
import us.crafties.btgames.games.cupshuffle.CupPhase
import us.crafties.btgames.games.cupshuffle.CupShuffleSession
import us.crafties.btgames.games.fourchips.FourChipsSession
import us.crafties.btgames.games.picturematch.PictureMatchSession
import us.crafties.btgames.games.picturematch.PictureIcons
import us.crafties.btgames.games.picturematch.TileState
import us.crafties.btgames.net.Control
import us.crafties.btgames.net.Link
import us.crafties.btgames.net.Peer
import us.crafties.btgames.net.Side
import us.crafties.btgames.net.RoomState
import us.crafties.btgames.net.Transport

/**
 * Two real sessions playing each other over an in-memory pipe: the closest thing to two
 * phones without Bluetooth. Catches the class of bug where both sides compute "not my
 * turn" from the same handshake and wait forever.
 */
class DualSessionTest {

    /**
     * Lossless cross-wired transport pair. Sessions only ever send Control.Game.
     *
     * Replay (not just buffer) stands in for BLE latency: on real phones the receiver's
     * listener is always up before the first packet arrives, while here a session can
     * send (BlockTower's seeder deals its seed synchronously in start()) before the
     * peer's collector first runs. Replay keeps those eager sends instead of dropping
     * them on the floor; the sessions' own resends would heal this on a real link.
     */
    private class PipeTransport : Transport {
        private val _room = MutableStateFlow<RoomState>(RoomState.None)
    override val room: StateFlow<RoomState> = _room.asStateFlow()

    // N-player room stubs – pipe transport is used only for 2‑player tests, so these are no‑ops.
    override fun createRoom(localNick: String, gameId: Int, min: Int, max: Int) {}
    override fun findRooms(localNick: String, gameId: Int) {}
    override fun startMatch() {}

        private val inbound = MutableSharedFlow<Control>(replay = 64, extraBufferCapacity = 64)
        override val control: Flow<Control> = inbound
        private val inboundStream = MutableSharedFlow<ByteArray>(replay = 64, extraBufferCapacity = 64)
        override val stream: Flow<ByteArray> = inboundStream
        override val link: StateFlow<Link> = MutableStateFlow(Link.Idle)
        override val peers: StateFlow<List<Peer>> = MutableStateFlow(emptyList())
        var peer: PipeTransport? = null
        override fun sendControl(msg: Control) {
            peer?.inbound?.tryEmit(msg)
        }
        override fun sendStream(bytes: ByteArray) {
            peer?.inboundStream?.tryEmit(bytes)
        }
        override fun startDiscovery(localNick: String, gameId: Int) {}
        override fun stopDiscovery() {}
        override fun connect(peer: Peer) {}
        override fun accept() {}
        override fun decline(reason: Int) {}
        override fun chooseFirst(iPlayFirst: Boolean) {}
        override fun leave() {}
        override fun release() {}
    }

    private fun pipes(): Pair<PipeTransport, PipeTransport> {
        val a = PipeTransport()
        val b = PipeTransport()
        a.peer = b
        b.peer = a
        return a to b
    }

    @Test fun fourChipsOpenerReachesBothSides() = runTest {
        val (aT, bT) = pipes()
        val a = FourChipsSession(backgroundScope, aT, true, Side.RED, "A", "B")
        val b = FourChipsSession(backgroundScope, bT, false, Side.BLUE, "B", "A")
        a.start()
        b.start()
        runCurrent()
        advanceTimeBy(1000)

        assertTrue("requester moves first", a.ui.value.myTurn)
        assertFalse("other side waits", b.ui.value.myTurn)

        a.dropDisc(3)
        runCurrent()
        advanceTimeBy(1000)

        assertFalse("opener now waits", a.ui.value.myTurn)
        assertTrue("peer picked up the opening drop", b.ui.value.myTurn)

        b.dropDisc(3)
        runCurrent()
        advanceTimeBy(1000)
        assertTrue("play returns to the opener", a.ui.value.myTurn)

        a.stop()
        b.stop()
    }

    @Test fun blockTowerSeedHandshakeConverges() = runTest {        val (sT, nT) = pipes()
        val s = BlockTowerSession(backgroundScope, sT, true, Side.RED, "S", "N")
        val n = BlockTowerSession(backgroundScope, nT, false, Side.BLUE, "N", "S")
        val toSeeder = mutableListOf<Control>()
        backgroundScope.launch { sT.control.collect { toSeeder.add(it) } }
        // Subscribe the peer first, like a real phone whose listener is up long before
        // the first packet crosses BLE latency.
        n.start()
        runCurrent()
        advanceTimeBy(100)
        s.start()
        runCurrent()
        advanceTimeBy(1000)

        assertNotNull("seeder builds locally", s.ui.value.tower)
        assertNotNull("peer builds from the seed", n.ui.value.tower)
        val acks = toSeeder.mapNotNull { (it as? Control.Game)?.payload?.let(BlockTowerCodec::decode) }
            .filterIsInstance<BlockTowerCodec.Msg.Ack>()
        assertTrue("peer acked the seed, got=$acks", acks.isNotEmpty())
        assertFalse("seeder sees the ack", s.ui.value.waitingForPeer)
        assertTrue("seeder to move first", s.ui.value.myTurn)
        assertFalse("peer waits", n.ui.value.myTurn)

        s.stop()
        n.stop()
    }

    @Test fun checkersOpenerReachesBothSides() = runTest {
        val (aT, bT) = pipes()
        val a = CheckersSession(backgroundScope, aT, true, Side.RED, "A", "B")
        val b = CheckersSession(backgroundScope, bT, false, Side.BLUE, "B", "A")
        a.start()
        b.start()
        runCurrent()
        advanceTimeBy(1000)

        assertTrue("requester moves first", a.ui.value.myTurn)
        assertFalse("other side waits", b.ui.value.myTurn)

        // The edge man on (6,1) has two slides: select, then step to (5,0).
        a.tapCell(6 * 8 + 1)
        runCurrent()
        assertEquals(6 * 8 + 1, a.ui.value.selected)
        a.tapCell(5 * 8 + 0)
        runCurrent()
        advanceTimeBy(1000)

        assertEquals(Piece.FIRST_MAN, b.ui.value.board.cells[5 * 8 + 0])
        assertEquals("opener now waits", false, a.ui.value.myTurn)
        assertTrue("peer picked up the opening step", b.ui.value.myTurn)

        a.stop()
        b.stop()
    }

    @Test fun checkersDragDropPlaysAndSyncs() = runTest {
        val (aT, bT) = pipes()
        val a = CheckersSession(backgroundScope, aT, true, Side.RED, "A", "B")
        val b = CheckersSession(backgroundScope, bT, false, Side.BLUE, "B", "A")
        a.start()
        b.start()
        runCurrent()
        advanceTimeBy(500)

        assertTrue("legal drag plays", a.dropPiece(6 * 8 + 1, 5 * 8 + 0))
        assertFalse("nothing left to drag on their turn", a.dropPiece(6 * 8 + 3, 5 * 8 + 2))
        runCurrent()
        advanceTimeBy(500)
        assertEquals(Piece.FIRST_MAN, b.ui.value.board.cells[5 * 8 + 0])

        // SECOND edge man (1,0) slides down to (2,1) in reply.
        assertTrue("reply drag plays", b.dropPiece(1 * 8 + 0, 2 * 8 + 1))
        runCurrent()
        advanceTimeBy(500)
        assertEquals(Piece.SECOND_MAN, a.ui.value.board.cells[2 * 8 + 1])
        assertTrue("play returns to the opener", a.ui.value.myTurn)

        a.stop()
        b.stop()
    }

    @Test fun chessOpenerReachesBothSides() = runTest {
        val (aT, bT) = pipes()
        val a = ChessSession(backgroundScope, aT, true, Side.RED, "A", "B")
        val b = ChessSession(backgroundScope, bT, false, Side.BLUE, "B", "A")
        a.start()
        b.start()
        runCurrent()
        advanceTimeBy(1000)

        assertTrue("requester is White and moves first", a.ui.value.myTurn)
        assertTrue(a.ui.value.whiteMe)
        assertFalse("other side waits as Black", b.ui.value.myTurn)
        assertFalse(b.ui.value.whiteMe)

        // e2-e4: select the pawn, then its landing.
        a.tap(52)
        runCurrent()
        assertEquals(52, a.ui.value.selected)
        a.tap(36)
        runCurrent()
        advanceTimeBy(1000)

        assertEquals(ChessPiece.WP, b.ui.value.board.cells[36])
        assertFalse("opener now waits", a.ui.value.myTurn)
        assertTrue("peer picked up the opening", b.ui.value.myTurn)

        a.stop()
        b.stop()
    }

    @Test fun chessDragDropPlaysAndSyncs() = runTest {
        val (aT, bT) = pipes()
        val a = ChessSession(backgroundScope, aT, true, Side.RED, "A", "B")
        val b = ChessSession(backgroundScope, bT, false, Side.BLUE, "B", "A")
        a.start()
        b.start()
        runCurrent()
        advanceTimeBy(1000)

        // e2-e4 by drag.
        assertTrue("legal drag plays", a.drop(52, 36))
        assertFalse("nothing to drag on their turn", a.drop(12, 28))
        assertFalse("a king's two-square hop is not a move", a.drop(60, 62))
        runCurrent()
        advanceTimeBy(500)
        assertEquals(ChessPiece.WP, b.ui.value.board.cells[36])
        assertFalse("opener now waits", a.ui.value.myTurn)
        assertTrue("peer picked up the opening", b.ui.value.myTurn)

        // e7-e5 by drag in reply.
        assertTrue("reply drag plays", b.drop(12, 28))
        runCurrent()
        advanceTimeBy(500)
        assertEquals(ChessPiece.BP, a.ui.value.board.cells[28])
        assertTrue("play returns to the opener", a.ui.value.myTurn)

        // A drop that is not legal changes nothing.
        assertFalse("a pawn does not leap three rows", a.drop(36, 20))
        assertEquals(ChessPiece.WP, a.ui.value.board.cells[36])
        assertFalse("dropping on its own square is not a move", a.drop(36, 36))

        a.stop()
        b.stop()
    }

    @Test fun chessRematchSwapsWhite() = runTest {
        val (aT, bT) = pipes()
        val a = ChessSession(backgroundScope, aT, true, Side.RED, "A", "B")
        val b = ChessSession(backgroundScope, bT, false, Side.BLUE, "B", "A")
        a.start()
        b.start()
        runCurrent()
        advanceTimeBy(500)

        fun play(s: ChessSession, from: Int, to: Int) {
            s.tap(from)
            runCurrent()
            s.tap(to)
            runCurrent()
            advanceTimeBy(200)
        }
        // Fool's mate: A (White) loses, B (Black) wins.
        play(a, 53, 45) // f3
        play(b, 12, 28) // e5
        play(a, 54, 38) // g4
        play(b, 3, 39)  // Qh4#
        assertTrue("game is over", a.ui.value.isOver)
        assertEquals("A is mated", 0, a.ui.value.myWins)
        assertEquals(1, a.ui.value.theirWins)
        assertEquals("B delivered mate", 1, b.ui.value.myWins)

        a.requestRematch()
        b.requestRematch()
        runCurrent()
        advanceTimeBy(200)
        assertEquals(1, a.ui.value.round)
        assertFalse("White swapped to B", a.ui.value.whiteMe)
        assertTrue(b.ui.value.whiteMe)
        assertEquals(1, a.ui.value.theirWins) // scores carry across rounds
        assertEquals(1, b.ui.value.myWins)

        a.stop()
        b.stop()
    }

    @Test fun cupShuffleFullRoundAgrees() = runTest {
        var now = 100_000L
        val (sT, gT) = pipes()
        val s = CupShuffleSession(
            backgroundScope, sT, true, Side.RED, "S", "G",
            clock = { now }, rnd = kotlin.random.Random(7),
        )
        val g = CupShuffleSession(
            backgroundScope, gT, false, Side.BLUE, "G", "S",
            clock = { now }, rnd = kotlin.random.Random(9),
        )
        s.start()
        g.start()
        runCurrent()
        advanceTimeBy(200)

        assertTrue("red shuffles round 0", s.ui.value.iShuffle)
        assertFalse("blue guesses round 0", g.ui.value.iShuffle)

        s.setCups(4)
        s.tapSlot(1) // hide the ball under slot 1
        runCurrent()
        advanceTimeBy(200)
        assertEquals(CupPhase.SHOW, s.ui.value.phase)
        assertEquals(CupPhase.SHOW, g.ui.value.phase)
        assertEquals(4, g.ui.value.cups)
        assertEquals(listOf(0, 1, 2, 3), g.ui.value.order)
        assertEquals("the show displays the ball to both", 1, s.ui.value.ballSlot)
        assertEquals(1, g.ui.value.ballSlot)

        now += 3000 // past the show: the cups cover the ball and the window opens
        runCurrent()
        advanceTimeBy(500)
        assertEquals(CupPhase.SHUFFLING, s.ui.value.phase)
        assertEquals(CupPhase.SHUFFLING, g.ui.value.phase)
        assertEquals("hidden from the guesser again", -1, g.ui.value.ballSlot)

        now += 300
        s.tapSlot(0) // a shuffle tap (the slot is ignored while shuffling)
        runCurrent()
        advanceTimeBy(200)
        assertEquals(1, s.ui.value.swaps)
        assertEquals("the guesser tracks the swap", s.ui.value.order, g.ui.value.order)
        assertTrue("x-ray vision for the shuffler", s.ui.value.ballSlot >= 0)

        now += 6000 // past the 5-second window
        runCurrent()
        advanceTimeBy(500)
        assertEquals(CupPhase.GUESSING, s.ui.value.phase)
        assertEquals(CupPhase.GUESSING, g.ui.value.phase)

        g.tapSlot(s.ui.value.ballSlot) // guess right
        runCurrent()
        advanceTimeBy(500)
        assertEquals(CupPhase.REVEAL, s.ui.value.phase)
        assertEquals(CupPhase.REVEAL, g.ui.value.phase)
        assertEquals(true, g.ui.value.guessCorrect)
        assertEquals("guesser scores", 1, g.ui.value.myWins)
        assertEquals(1, s.ui.value.theirWins)
        assertEquals("the reveal shows the ball", s.ui.value.ballSlot, g.ui.value.ballSlot)

        s.requestRematch()
        g.requestRematch()
        runCurrent()
        advanceTimeBy(200)
        assertEquals(1, s.ui.value.round)
        assertFalse("roles swap", s.ui.value.iShuffle)
        assertTrue(g.ui.value.iShuffle)

        s.stop()
        g.stop()
    }

    @Test fun pictureMatchDealAndPairReachBothSides() = runTest {
        val (aT, bT) = pipes()
        val a = PictureMatchSession(backgroundScope, aT, true, Side.RED, "A", "B")
        val b = PictureMatchSession(backgroundScope, bT, false, Side.BLUE, "B", "A")
        a.start(); b.start(); runCurrent(); advanceTimeBy(100)

        assertTrue(b.ui.value.states.isEmpty())
        a.chooseGrid(6)
        a.deal()
        runCurrent(); advanceTimeBy(100)

        assertTrue(a.ui.value.ready)
        assertTrue(b.ui.value.ready)
        assertEquals(36, a.ui.value.states.size)
        assertEquals(a.ui.value.icons, b.ui.value.icons)
        assertTrue(a.ui.value.myTurn)
        assertFalse(b.ui.value.myTurn)

        val icons = b.ui.value.icons
        val first = 0
        val partner = icons.indices.first { it != first && icons[it] == icons[first] }
        a.flip(first)
        runCurrent(); advanceTimeBy(50)
        a.flip(partner)
        runCurrent(); advanceTimeBy(50)

        assertEquals(1, a.ui.value.scores[0])
        assertEquals(1, b.ui.value.scores[0])
        assertTrue("a pair keeps the turn", a.ui.value.myTurn)
        assertEquals(TileState.MATCHED, b.ui.value.states[first])

        a.stop(); b.stop()
    }
}
