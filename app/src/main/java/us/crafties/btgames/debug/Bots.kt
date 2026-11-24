package us.crafties.btgames.debug

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import us.crafties.btgames.games.blocktower.BlockTowerCodec
import us.crafties.btgames.games.blocktower.BlockTowerPhysics
import us.crafties.btgames.games.checkers.CheckerSide
import us.crafties.btgames.games.checkers.CheckersBoard
import us.crafties.btgames.games.checkers.CheckersCodec
import us.crafties.btgames.games.cupshuffle.CupShuffleCodec
import us.crafties.btgames.games.cupshuffle.CupShuffleLogic
import us.crafties.btgames.games.chess.ChessBoard
import us.crafties.btgames.games.chess.ChessCodec
import us.crafties.btgames.games.chess.ChessSide
import us.crafties.btgames.games.fourchips.Chip
import us.crafties.btgames.games.fourchips.FourChipsBoard
import us.crafties.btgames.games.fourchips.FourChipsCodec
import us.crafties.btgames.games.keymash.KeymashCodec
import us.crafties.btgames.games.keymash.KeymashMode
import us.crafties.btgames.games.last.LastCodec
import us.crafties.btgames.games.last.LastColor
import us.crafties.btgames.games.last.LastEngine
import us.crafties.btgames.games.last.LastKind
import us.crafties.btgames.games.last.LastMove
import us.crafties.btgames.games.paddle.PaddleCodec
import us.crafties.btgames.games.picturematch.PictureMatchCodec
import us.crafties.btgames.games.picturematch.PictureMatchEngine
import us.crafties.btgames.games.picturematch.TileState
import us.crafties.btgames.games.rps.RpsCodec
import us.crafties.btgames.games.rps.Throw
import us.crafties.btgames.games.shutthebox.ShutTheBoxBoard
import us.crafties.btgames.games.shutthebox.ShutTheBoxCodec
import us.crafties.btgames.games.shutthebox.StbMode
import us.crafties.btgames.games.shutthebox.StbTiles
import us.crafties.btgames.games.tetriminoes.TetriminoesCodec
import us.crafties.btgames.games.xo.Mark
import us.crafties.btgames.games.xo.XoBoard
import us.crafties.btgames.games.xo.XoCodec
import us.crafties.btgames.net.BotBrain
import us.crafties.btgames.net.Control
import us.crafties.btgames.net.GameIds
import us.crafties.btgames.net.LoopbackTransport
import us.crafties.btgames.net.Side
import kotlin.random.Random

/**
 * Test mode's opponents: one random-move bot per game. Each mirrors just enough of its
 * session's protocol to keep a real session happy — legal moves, resends, rematch
 * auto-accept — and nothing more. None of them ever opens: the player always moves first,
 * so a bot only ever replies, waits, or runs a timer.
 *
 * Delays are in whole hundreds of milliseconds so the bot feels like a distracted human,
 * and every timer lives on the transport's scope so leaving the match cancels everything.
 */
fun makeBot(transport: LoopbackTransport, gameId: Int, playerSide: Side): BotBrain? = when (gameId) {
    GameIds.XO -> XoBot(transport)
    GameIds.FOUR_CHIPS -> FourChipsBot(transport)
    GameIds.RPS -> RpsBot(transport)
    GameIds.PADDLE -> PaddleBot(transport)
    GameIds.TETRIMINOES -> TetriminoesBot(transport, playerSide)
    GameIds.KEYMASH -> KeymashBot(transport, playerSide)
    GameIds.BLOCK_TOWER -> BlockTowerBot(transport)
    GameIds.CHECKERS -> CheckersBot(transport)
    GameIds.CUP_SHUFFLE -> CupShuffleBot(transport, playerSide)
    GameIds.CHESS -> ChessBot(transport)
    GameIds.SHUT_THE_BOX -> ShutTheBoxBot(transport)
    GameIds.LAST -> LastBot(transport)
    GameIds.PICTURE_MATCH -> PictureMatchBot(transport)
    else -> null
}

private abstract class BaseBot(protected val transport: LoopbackTransport) : BotBrain {
    protected val scope: CoroutineScope get() = transport.scope
    private val jobs = mutableListOf<Job>()

    override fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    /** Run [block] after [ms]; tracked so [stop] cancels it. */
    protected fun later(ms: Long, block: () -> Unit) {
        jobs.removeAll { it.isCompleted }
        jobs += scope.launch { delay(ms); block() }
    }

    /** Re-run [block] every [ms] until [stop]; tracked like [later]. */
    protected fun every(ms: Long, block: () -> Unit): Job {
        jobs.removeAll { it.isCompleted }
        val job = scope.launch { while (true) { delay(ms); block() } }
        jobs += job
        return job
    }

    /** Track an externally-launched job so [stop] cancels it too. */
    protected fun track(job: Job) {
        jobs.removeAll { it.isCompleted }
        jobs += job
    }

    protected fun send(payload: ByteArray) = transport.botSend(Control.Game(payload))
    override fun onPlayerControl(msg: Control) {}
    override fun onPlayerStream(bytes: ByteArray) {}
}

// ---- turn-based board games ---------------------------------------------------------------

/** XO bot: always O (the player always plays first), random empty cell, auto-rematches. */
private class XoBot(transport: LoopbackTransport) : BaseBot(transport) {
    private var round = 0
    private var board = XoBoard(firstMover = Mark.X)

    override fun start() {
        // Player (X) opens round 0. After that, resend our latest board like a real session:
        // if our reply was lost, the player's resend can't advance us, so we must heal it.
        every(2500) {
            if (board.moveCount > 0 && !board.isOver) send(XoCodec.encodeState(round, board))
        }
    }

    override fun onPlayerControl(msg: Control) {
        val m = (msg as? Control.Game)?.payload?.let(XoCodec::decode) ?: return
        when (m) {
            is XoCodec.Msg.State -> onState(m)
            is XoCodec.Msg.Rematch -> {
                if (m.round != round + 1) return
                round = m.round
                board = XoBoard(firstMover = board.firstMover.other())
                send(XoCodec.encodeRematch(round))
                maybeMove()
            }
        }
    }

    private fun onState(m: XoCodec.Msg.State) {
        if (m.round < round) return
        if (m.round > round) {
            // We missed the rematch handshake; their round is authoritative, same rule as sessions.
            round = m.round
            board = XoBoard(firstMover = m.board.firstMover)
        }
        if (m.board.moveCount <= board.moveCount) return // duplicate or resend: nothing new
        board = m.board
        maybeMove()
    }

    private fun maybeMove() {
        if (board.isOver || board.turn != Mark.O) return
        later(Random.nextLong(400, 900)) {
            val empty = board.cells.indices.filter { board.cells[it] == Mark.EMPTY }
            if (empty.isEmpty() || board.turn != Mark.O) return@later
            board = board.play(empty.random(), Mark.O) ?: return@later
            send(XoCodec.encodeState(round, board))
        }
    }
}

/** 4 Chips bot: same shape as XO, random non-full column, auto-rematches. */
private class FourChipsBot(transport: LoopbackTransport) : BaseBot(transport) {
    private var round = 0
    private var board = FourChipsBoard(firstMover = Chip.FIRST)

    override fun start() {
        every(2500) {
            if (board.moveCount > 0 && !board.isOver) send(FourChipsCodec.encodeState(round, board))
        }
    }

    override fun onPlayerControl(msg: Control) {
        val m = (msg as? Control.Game)?.payload?.let(FourChipsCodec::decode) ?: return
        when (m) {
            is FourChipsCodec.Msg.State -> onState(m)
            is FourChipsCodec.Msg.Rematch -> {
                if (m.round != round + 1) return
                round = m.round
                board = FourChipsBoard(firstMover = board.firstMover.other())
                send(FourChipsCodec.encodeRematch(round))
                maybeMove()
            }
        }
    }

    private fun onState(m: FourChipsCodec.Msg.State) {
        if (m.round < round) return
        if (m.round > round) {
            round = m.round
            board = FourChipsBoard(firstMover = m.board.firstMover)
        }
        if (m.board.moveCount <= board.moveCount) return
        board = m.board
        maybeMove()
    }

    private fun maybeMove() {
        val botMark = board.firstMover.other() // the player always moves first, so we never do
        if (board.isOver || board.turn != botMark) return
        later(Random.nextLong(400, 900)) {
            // A column is playable while its top cell is empty.
            val open = (0 until FourChipsBoard.COLS).filter { board.cells[it] == Chip.EMPTY }
            if (open.isEmpty() || board.turn != botMark) return@later
            val next = board.drop(open.random(), botMark) ?: return@later
            board = next.board
            send(FourChipsCodec.encodeState(round, board))
        }
    }
}

// ---- simultaneous reveal --------------------------------------------------------------------

/** RPS bot: answers every throw with a random one, auto-accepts rematches. Stateless. */
private class RpsBot(transport: LoopbackTransport) : BaseBot(transport) {
    override fun start() {}

    override fun onPlayerControl(msg: Control) {
        val m = (msg as? Control.Game)?.payload?.let(RpsCodec::decode) ?: return
        when (m) {
            is RpsCodec.Msg.ThrowMsg -> later(Random.nextLong(300, 800)) {
                send(RpsCodec.encodeThrow(m.match, m.round, Throw.entries.random()))
            }
            is RpsCodec.Msg.Rematch -> send(RpsCodec.encodeRematch(m.match))
        }
    }
}

// ---- realtime -------------------------------------------------------------------------------

/**
 * Paddle bot, playing guest (the player always hosts). Chases the ball with lag and wobble:
 * beatable, not furniture. Ignores control events; the host owns scores and restarts.
 */
private class PaddleBot(transport: LoopbackTransport) : BaseBot(transport) {
    @Volatile private var ballY = 0.5f
    @Volatile private var paddle = 0.5f

    override fun start() {
        later(500) {
            every(33) {
                paddle += (ballY - paddle) * 0.10f + (Random.nextFloat() - 0.5f) * 0.01f
                paddle = paddle.coerceIn(0f, 1f)
                transport.botSendStream(PaddleCodec.encodeInput(paddle))
            }
        }
    }

    override fun onPlayerStream(bytes: ByteArray) {
        PaddleCodec.decodeState(bytes)?.let { ballY = it.state.ballY }
    }
}

// ---- score-attack races -----------------------------------------------------------------------

/**
 * Tetriminoes bot: fakes a plausible score curve instead of playing. Chooses the duration
 * exactly when the session rules say it should: RED picks even rounds, BLUE odd ones, and
 * the bot is always the opposite colour of the player.
 */
private class TetriminoesBot(
    transport: LoopbackTransport,
    private val playerSide: Side,
) : BaseBot(transport) {
    private var round = 0
    private var durationSec = 0
    private var race: Job? = null
    private var seenUpdate = false

    private fun playerChooses(r: Int) = (r % 2 == 0) == (playerSide == Side.RED)

    override fun start() {
        if (!playerChooses(0)) chooseAndRace()
    }

    override fun stop() {
        super.stop()
        race?.cancel()
        race = null
    }

    override fun onPlayerControl(msg: Control) {
        val m = (msg as? Control.Game)?.payload?.let(TetriminoesCodec::decode) ?: return
        when (m) {
            is TetriminoesCodec.Msg.Choice -> {
                if (m.round != round || durationSec != 0) return // first choice wins
                durationSec = m.durationSec
                beginRace()
            }
            is TetriminoesCodec.Msg.Update -> {
                if (m.round == round) seenUpdate = true
            }
            is TetriminoesCodec.Msg.Rematch -> {
                if (m.round != round + 1) return
                send(TetriminoesCodec.encodeRematch(m.round))
                race?.cancel()
                race = null
                round = m.round
                durationSec = 0
                seenUpdate = false
                if (!playerChooses(round)) chooseAndRace()
            }
        }
    }

    private fun chooseAndRace() {
        // Delayed so the player's session is subscribed before our first packet lands.
        later(1000) {
            durationSec = TetriminoesCodec.DURATIONS.random()
            send(TetriminoesCodec.encodeChoice(round, durationSec))
            beginRace()
        }
    }

    private fun beginRace() {
        if (race != null) return
        val rate = Random.nextInt(8, 20) // points per second: sometimes wins, sometimes not
        race = scope.launch {
            delay(3000) // the shared countdown both phones observe
            var t = 0
            while (t < durationSec) {
                delay(1000)
                t++
                if (!seenUpdate && !playerChooses(round)) {
                    // They haven't proven they are playing: re-send the pick, like sessions do.
                    send(TetriminoesCodec.encodeChoice(round, durationSec))
                }
                val score = t * rate
                send(
                    TetriminoesCodec.encodeUpdate(
                        round, score, score / 300, (1 + 9 * t / durationSec).coerceIn(1, 10), false,
                    ),
                )
            }
            val final = durationSec * rate
            repeat(3) {
                send(
                    TetriminoesCodec.encodeUpdate(
                        round, final, final / 300, 10, true,
                    ),
                )
                delay(1500)
            }
        }.also(::track)
    }
}

/**
 * Keymash bot: same deal, fakes steady typing instead. Picks the mode exactly when the
 * session rules hand it the choice (opposite colour of the player).
 */
private class KeymashBot(
    transport: LoopbackTransport,
    private val playerSide: Side,
) : BaseBot(transport) {
    private var round = 0
    private var total = 0
    private var race: Job? = null
    private var seenUpdate = false

    private fun playerChooses(r: Int) = (r % 2 == 0) == (playerSide == Side.RED)

    override fun start() {
        if (!playerChooses(0)) chooseAndRace()
    }

    override fun stop() {
        super.stop()
        race?.cancel()
        race = null
    }

    override fun onPlayerControl(msg: Control) {
        val m = (msg as? Control.Game)?.payload?.let(KeymashCodec::decode) ?: return
        when (m) {
            is KeymashCodec.Msg.Choice -> {
                if (m.round != round || total != 0) return // first choice wins
                beginRace(m.mode)
            }
            is KeymashCodec.Msg.Update -> {
                if (m.round == round) seenUpdate = true
            }
            is KeymashCodec.Msg.Rematch -> {
                if (m.round != round + 1) return
                send(KeymashCodec.encodeRematch(m.round))
                race?.cancel()
                race = null
                round = m.round
                total = 0
                seenUpdate = false
                if (!playerChooses(round)) chooseAndRace()
            }
        }
    }

    private fun chooseAndRace() {
        // Delayed so the player's session is subscribed before our first packet lands.
        later(1000) {
            val mode = KeymashMode.entries.random()
            val seed = Random.nextLong() and 0xFFFFFFFFL
            send(KeymashCodec.encodeChoice(round, mode, seed))
            beginRace(mode)
        }
    }

    private fun beginRace(mode: KeymashMode) {
        if (race != null) return
        total = if (mode == KeymashMode.WORDS) 25 else 3
        val wpm = Random.nextInt(30, 60)
        race = scope.launch {
            delay(3000) // the shared countdown both phones observe
            var done = 0
            var score = 0
            while (done < total) {
                delay(Random.nextLong(1500, 2500))
                done++
                score += wpm * 2
                val last = done >= total
                send(KeymashCodec.encodeUpdate(round, last, done, total, wpm, score))
            }
            repeat(2) {
                delay(1500)
                send(KeymashCodec.encodeUpdate(round, true, total, total, wpm, score))
            }
        }
    }
}

// ---- deterministic duel -------------------------------------------------------------------------

/**
 * Block Tower bot: keeps its own tower from the shared seed and pulls random legal blocks.
 * Seeds odd rounds itself (the player, who always plays first, seeds even ones).
 */
private class BlockTowerBot(transport: LoopbackTransport) : BaseBot(transport) {
    private var round = 0
    private var seed = 0L
    private var tower: BlockTowerPhysics.TowerState? = null
    private var seedLoop: Job? = null

    /** The bot plays second, so it seeds exactly the rounds the player doesn't: the odd ones. */
    private fun iSeed() = round % 2 == 1

    override fun start() {} // round 0: the player seeds and moves first; we just wait

    override fun stop() {
        super.stop()
        seedLoop?.cancel()
        seedLoop = null
    }

    override fun onPlayerControl(msg: Control) {
        val m = (msg as? Control.Game)?.payload?.let(BlockTowerCodec::decode) ?: return
        when (m) {
            is BlockTowerCodec.Msg.Seed -> {
                if (m.round != round) return
                seed = m.seed
                tower = BlockTowerPhysics.buildTower(seed)
                send(BlockTowerCodec.encodeAck(round))
            }
            is BlockTowerCodec.Msg.Ack -> Unit // only the seeder cares; that can only be us
            is BlockTowerCodec.Msg.Move -> onMove(m)
            is BlockTowerCodec.Msg.Rematch -> {
                if (m.round != round + 1) return
                send(BlockTowerCodec.encodeRematch(m.round))
                seedLoop?.cancel()
                seedLoop = null
                round = m.round
                tower = null
                if (iSeed()) seedAndOffer()
            }
        }
    }

    private fun onMove(m: BlockTowerCodec.Msg.Move) {
        val t = tower ?: return
        if (m.round != round || m.n != t.moveCount) return
        val r = BlockTowerPhysics.applyPull(t, m.layer, m.slot, seed)
        tower = r.state
        seedLoop?.cancel() // our seed (if any) landed: no more resends
        seedLoop = null
        if (r.collapsed) return // they felled it; nothing to answer with
        if (tower!!.moveCount % 2 == 0) return // not our turn (paranoia; the player moves on evens)
        later(Random.nextLong(500, 1000)) {
            val cur = tower ?: return@later
            val options = mutableListOf<Pair<Int, Int>>()
            for (layer in 0..BlockTowerPhysics.topLayer(cur)) {
                for (slot in 0..2) {
                    if (BlockTowerPhysics.isLegalPull(cur, layer, slot)) options += layer to slot
                }
            }
            if (options.isEmpty()) return@later
            val (layer, slot) = options.random()
            val before = cur.moveCount
            val res = BlockTowerPhysics.applyPull(cur, layer, slot, seed)
            tower = res.state
            send(BlockTowerCodec.encodeMove(round, before, layer, slot))
        }
    }

    private fun seedAndOffer() {
        seed = Random.nextLong() and 0xFFFFFFFFL
        tower = BlockTowerPhysics.buildTower(seed)
        send(BlockTowerCodec.encodeSeed(round, seed))
        // Re-send until their first move proves the seed landed.
        seedLoop?.cancel()
        seedLoop = scope.launch {
            while (true) {
                delay(1500)
                if ((tower?.moveCount ?: 0) > 0) break
                send(BlockTowerCodec.encodeSeed(round, seed))
            }
        }
    }
}

// ---- checkers and cup shuffle ---------------------------------------------------------------

/**
 * Checkers bot: always SECOND (the player always plays first, hence always FIRST), random
 * legal step, auto-rematches. Opens the odd rounds itself, since the starter alternates.
 */
private class CheckersBot(transport: LoopbackTransport) : BaseBot(transport) {
    private var round = 0
    private var board = CheckersBoard(firstMover = CheckerSide.FIRST)

    override fun start() {
        every(2500) {
            if (board.ply > 0 && !board.isOver) send(CheckersCodec.encodeState(round, board))
        }
    }

    override fun onPlayerControl(msg: Control) {
        val m = (msg as? Control.Game)?.payload?.let(CheckersCodec::decode) ?: return
        when (m) {
            is CheckersCodec.Msg.State -> onState(m)
            is CheckersCodec.Msg.Rematch -> {
                if (m.round != round + 1) return
                round = m.round
                board = CheckersBoard(firstMover = if (round % 2 == 0) CheckerSide.FIRST else CheckerSide.SECOND)
                send(CheckersCodec.encodeRematch(round))
                maybeMove()
            }
        }
    }

    private fun onState(m: CheckersCodec.Msg.State) {
        if (m.round < round) return
        if (m.round > round) {
            // We missed the rematch handshake; their round is authoritative, same rule as sessions.
            if (m.round != round + 1) return
            round = m.round
            board = CheckersBoard(firstMover = m.board.firstMover)
        }
        if (m.board.firstMover != board.firstMover) return
        if (m.board !in board.nextBoards()) return // duplicate, resend, or nonsense: nothing new
        board = m.board
        maybeMove()
    }

    private fun maybeMove() {
        if (board.isOver || board.turn != CheckerSide.SECOND) return
        later(Random.nextLong(400, 900)) {
            val next = board.nextBoards()
            if (next.isEmpty() || board.turn != CheckerSide.SECOND) return@later
            board = next.random()
            send(CheckersCodec.encodeState(round, board))
        }
    }
}

/**
 * Cup Shuffle bot. Shuffles exactly when the session rules hand it the deal (RED on even
 * rounds, BLUE on odd ones — always the opposite colour of the player): hides the ball under
 * a random cup of a random count, deals a handful of swaps, and calls time. Guessing, it
 * watches the order and picks a random cup. Auto-rematches.
 */
private class CupShuffleBot(
    transport: LoopbackTransport,
    private val playerSide: Side,
) : BaseBot(transport) {
    private var round = 0
    private var cups = 0
    private var order: List<Int> = emptyList()
    private var ballCup = -1
    private var seq = 0
    private var race: Job? = null

    private val botSide: Side get() = playerSide.other()
    private fun botShuffles(r: Int) = (r % 2 == 0) == (botSide == Side.RED)

    override fun start() {
        if (botShuffles(0)) deal()
    }

    override fun stop() {
        super.stop()
        race?.cancel()
        race = null
    }

    override fun onPlayerControl(msg: Control) {
        val m = (msg as? Control.Game)?.payload?.let(CupShuffleCodec::decode) ?: return
        when (m) {
            is CupShuffleCodec.Msg.Choice -> {
                if (m.round != round || botShuffles(round)) return // first choice wins
                cups = m.cups
                order = CupShuffleLogic.initialOrder(cups)
                ballCup = -1
                seq = 0
            }
            is CupShuffleCodec.Msg.Shuffle -> {
                if (m.round != round || botShuffles(round)) return
                if (m.cups != cups || m.seq <= seq) return
                order = m.order
                seq = m.seq
            }
            is CupShuffleCodec.Msg.End -> {
                if (m.round != round || botShuffles(round)) return
                if (cups == 0 || m.cups != cups) {
                    // Missed the choice entirely: still play the guessing half.
                    cups = m.cups
                    order = CupShuffleLogic.initialOrder(cups)
                    seq = 0
                }
                later(Random.nextLong(600, 1200)) {
                    val guess = Random.nextInt(cups)
                    repeat(3) {
                        send(CupShuffleCodec.encodeGuess(round, guess))
                    }
                }
            }
            is CupShuffleCodec.Msg.Guess -> {
                if (m.round != round || !botShuffles(round)) return
                val ball = order.indexOf(ballCup)
                if (ball < 0 || m.pos >= cups) return
                race?.cancel()
                repeat(3) { send(CupShuffleCodec.encodeResult(round, ball, m.pos)) }
            }
            is CupShuffleCodec.Msg.Result -> Unit // only the guesser cares; that can only be us
            is CupShuffleCodec.Msg.Rematch -> {
                if (m.round != round + 1) return
                send(CupShuffleCodec.encodeRematch(m.round))
                race?.cancel()
                race = null
                round = m.round
                cups = 0
                order = emptyList()
                ballCup = -1
                seq = 0
                if (botShuffles(round)) deal()
            }
        }
    }

    private fun deal() {
        // Delayed so the player's session is subscribed before our first packet lands.
        later(1000) {
            val dealtRound = round
            cups = Random.nextInt(CupShuffleLogic.MIN_CUPS, CupShuffleLogic.MAX_CUPS + 1)
            ballCup = Random.nextInt(cups)
            order = CupShuffleLogic.initialOrder(cups)
            seq = 0
            send(CupShuffleCodec.encodeChoice(dealtRound, cups, ballCup))
            race?.cancel()
            race = scope.launch {
                // The show runs first: early swaps would land while the player still watches
                // the ball and be ignored.
                delay(3000)
                repeat(Random.nextInt(5, 10)) {
                    delay(350)
                    if (round != dealtRound) return@launch
                    val (a, b) = CupShuffleLogic.randomAdjacentPair(Random, cups)
                    order = CupShuffleLogic.swapped(order, a, b)
                    seq++
                    send(CupShuffleCodec.encodeShuffle(dealtRound, seq, order))
                }
                if (round != dealtRound) return@launch
                repeat(3) {
                    send(CupShuffleCodec.encodeEnd(dealtRound, seq, cups))
                    delay(1500)
                }
            }.also(::track)
        }
    }
}

// ---- chess -------------------------------------------------------------------------------------

/**
 * Chess bot: always SECOND (the player always plays first, hence always White in round 0),
 * random legal move, auto-rematches. Opens the odd rounds itself, since White swaps every
 * rematch — the player is White on even rounds, the bot on odd ones.
 */
private class ChessBot(transport: LoopbackTransport) : BaseBot(transport) {
    private var round = 0
    private var board = ChessBoard()

    /** The player is White exactly on even rounds (they always play first in round 0). */
    private val botIsWhite: Boolean get() = round % 2 == 1
    private val botTurn: ChessSide get() = if (botIsWhite) ChessSide.WHITE else ChessSide.BLACK

    override fun start() {
        // Periodic resend like a real session: if our reply was lost, the player's
        // resend can't advance us, so we must heal it.
        every(2500) {
            val moved = board.turn == ChessSide.BLACK || board.fullmove > 1
            if (moved && !isOver()) send(ChessCodec.encodeState(round, board))
        }
    }

    override fun onPlayerControl(msg: Control) {
        val m = (msg as? Control.Game)?.payload?.let(ChessCodec::decode) ?: return
        when (m) {
            is ChessCodec.Msg.State -> onState(m)
            is ChessCodec.Msg.Rematch -> {
                if (m.round != round + 1) return
                round = m.round
                board = ChessBoard()
                send(ChessCodec.encodeRematch(round))
                maybeMove()
            }
        }
    }

    private fun onState(m: ChessCodec.Msg.State) {
        if (m.round < round) return
        if (m.round > round) {
            // We missed the rematch handshake; their round is authoritative, same rule as sessions.
            if (m.round != round + 1) return
            round = m.round
            board = ChessBoard()
        }
        // Accept only a direct legal successor of our board (duplicate, resend, or nonsense).
        if (board.legalMoves().none { board.apply(it) == m.board }) return
        board = m.board
        maybeMove()
    }

    private fun isOver(): Boolean =
        board.checkmate || board.stalemate || board.drawByFiftyMoves

    private fun maybeMove() {
        if (isOver() || board.turn != botTurn) return
        later(Random.nextLong(500, 1200)) {
            val moves = board.legalMoves()
            if (moves.isEmpty() || board.turn != botTurn || isOver()) return@later
            // Prefer a promotion when one is on offer; otherwise a uniformly random legal move.
            val move = moves.filter { it.promotion != 0 }.randomOrNull() ?: moves.random()
            board = board.apply(move) ?: return@later
            send(ChessCodec.encodeState(round, board))
        }
    }
}

// ---- dice and cards -------------------------------------------------------------------------

/** Shut-The-Box bot: rolls, shuts any legal combination, and passes the turn back like a session. */
private class ShutTheBoxBot(transport: LoopbackTransport) : BaseBot(transport) {
    override fun start() {}   // the player always rolls first; we only reply to Turn messages

    private var mode = StbMode.UNTIL_DONE
    private var tiles = StbTiles.NINE
    private var board = ShutTheBoxBoard(0, tiles)
    private var myTurn = false
    private var rollId = 0
    private var busy = false

    override fun onPlayerControl(msg: Control) {
        val m = (msg as? Control.Game)?.payload?.let(ShutTheBoxCodec::decode) ?: return
        when (m) {
            is ShutTheBoxCodec.Msg.Mode -> config(m.mode, m.tiles)
            is ShutTheBoxCodec.Msg.Turn -> {
                config(m.mode, m.tiles)
                myTurn = m.seat == MY_SEAT
                busy = false
                if (myTurn) play()
            }
            is ShutTheBoxCodec.Msg.Final -> myTurn = false
            else -> Unit
        }
    }

    private fun config(newMode: Int, newTiles: Int) {
        mode = newMode
        if (newTiles != tiles && StbTiles.valid(newTiles)) {
            tiles = newTiles
            board = ShutTheBoxBoard(0, tiles)
        }
    }

    private fun play() {
        if (busy || !myTurn) return
        busy = true
        later(Random.nextLong(400, 800)) { roll() }
    }

    private fun roll() {
        if (!myTurn) return
        if (board.isShut()) { finish(out = true); return }
        val d1 = Random.nextInt(6) + 1
        val d2 = if (board.rollsOneDie()) 0 else Random.nextInt(6) + 1
        rollId = rollId % 255 + 1
        val nums = combination(d1 + d2)
        if (nums == null) { finish(out = true); return }   // no legal move: out for the match
        board.applyClose(nums)
        send(
            ShutTheBoxCodec.encode(
                ShutTheBoxCodec.Msg.State(MY_SEAT, board.closedMask(), d1, d2, rollId),
            ),
        )
        when {
            board.isShut() -> finish(out = true)
            mode == StbMode.ONE_FLIP -> finish(out = false)
            else -> later(Random.nextLong(500, 900)) { roll() }   // until done: keep rolling
        }
    }

    /** Any subset of the open tiles adding up to the roll; the session accepts any legal move. */
    private fun combination(roll: Int): List<Int>? {
        val found = mutableListOf<Int>()
        fun search(open: List<Int>, i: Int, left: Int): Boolean {
            if (left == 0) return true
            if (i >= open.size || left < 0) return false
            found.add(open[i])
            if (search(open, i + 1, left - open[i])) return true
            found.removeAt(found.lastIndex)
            return search(open, i + 1, left)
        }
        return if (search(board.availableNumbers(), 0, roll)) found.toList() else null
    }

    private fun finish(out: Boolean) {
        myTurn = false
        busy = false
        send(ShutTheBoxCodec.encode(ShutTheBoxCodec.Msg.Done(MY_SEAT, board.score(), out)))
    }

    private companion object { const val MY_SEAT = 1 }
}

/**
 * Last! bot: keeps its own engine from the host's SEED and numbered moves, then sends legal
 * play/draw/swap requests when the host gives it the turn, and slaps on every 6.
 */
private class LastBot(transport: LoopbackTransport) : BaseBot(transport) {
    override fun start() {}   // the host deals and moves first; we only reply to Seed and Move

    private var match = -1
    private var rules = us.crafties.btgames.games.last.LastRules()
    private var engine: LastEngine? = null
    private val pending = HashMap<Int, LastMove>()
    private var slappedRound = -1
    private var defendedRound = -1
    private var actedMoveNo = -1

    override fun onPlayerControl(msg: Control) {
        val m = (msg as? Control.Game)?.payload?.let(LastCodec::decode) ?: return
        when (m) {
            is LastCodec.Msg.Seed -> onSeed(m)
            is LastCodec.Msg.Move -> onMove(m)
            else -> Unit
        }
    }

    private fun onSeed(m: LastCodec.Msg.Seed) {
        if (m.players != 2) return   // test mode is a plain two-player link
        if (engine == null || m.match != match) {
            match = m.match
            rules = m.rules
            engine = LastEngine(m.players, m.seed.toLong(), m.rules)
            pending.clear()
            slappedRound = -1
            defendedRound = -1
            actedMoveNo = -1
        }
        // The host repeats SEED until it hears READY; answer every copy, like a real client.
        send(LastCodec.encode(LastCodec.Msg.Ready(m.match)))
    }

    private fun onMove(m: LastCodec.Msg.Move) {
        val e = engine ?: return
        if (m.match != match) return
        pending[(m.seq and 0xFF)] = m.move
        while (true) {
            val mv = pending.remove(e.moveNo and 0xFF) ?: break
            e.apply(mv)
        }
        later(Random.nextLong(300, 600)) { act() }
    }

    private fun act() {
        val e = engine ?: return
        if (e.winner >= 0) return
        if (e.defenseOpen && e.defenseTarget == MY_SEAT) {
            if (defendedRound != e.defenseRound) {
                val card = e.defenseCards(MY_SEAT).firstOrNull()
                if (card != null) {
                    defendedRound = e.defenseRound
                    send(LastCodec.encode(LastCodec.Msg.Defend(match, card)))
                }
            }
            return
        }
        if (e.slapOpen) {
            if (slappedRound != e.slapRound) {
                slappedRound = e.slapRound
                send(LastCodec.encode(LastCodec.Msg.Slap(match, e.slapRound, LastCodec.NO_TS)))
            }
            return
        }
        if (e.turn != MY_SEAT || actedMoveNo == e.moveNo) return
        actedMoveNo = e.moveNo
        val hand = e.hands[MY_SEAT]
        val index = hand.indices.firstOrNull { e.playableAt(it, hand[it]) }
        if (index == null) {
            send(LastCodec.encode(LastCodec.Msg.Draw(match)))
            return
        }
        val card = hand[index]
        when {
            rules.zeroSeven && card.kind == LastKind.NUMBER && card.number == 7 ->
                send(LastCodec.encode(LastCodec.Msg.Swap(match, index, OTHER_SEAT)))
            card.kind.isWild ->
                send(LastCodec.encode(LastCodec.Msg.Play(match, index, LastColor.entries.random())))
            else -> send(LastCodec.encode(LastCodec.Msg.Play(match, index, null)))
        }
    }

    private companion object {
        const val MY_SEAT = 1
        const val OTHER_SEAT = 0
    }
}

/** Picture Match bot: rebuilds the deck from the host's seed and plays legal pairs on its turn. */
private class PictureMatchBot(transport: LoopbackTransport) : BaseBot(transport) {
    private var match = -1
    private var engine: PictureMatchEngine? = null

    override fun start() {
        // Re-try while it is our turn: a rejected pick produces no new snapshot, so without this
        // the bot could sit on a stale board after a dropped packet.
        every(1200) { play() }
    }

    override fun onPlayerControl(msg: Control) {
        val m = (msg as? Control.Game)?.payload?.let(PictureMatchCodec::decode) ?: return
        when (m) {
            is PictureMatchCodec.Msg.Seed -> {
                if (m.match != match || engine == null) {
                    match = m.match
                    engine = PictureMatchEngine(m.grid, m.seed, 2)
                }
                send(PictureMatchCodec.encode(PictureMatchCodec.Msg.Ready(m.match)))
            }
            is PictureMatchCodec.Msg.State -> {
                val e = engine ?: return
                if (m.match != match) return
                e.applyState(m.turn, m.scores, m.pending, m.last, m.packed)
                play()
            }
            else -> Unit
        }
    }

    private fun play() {
        val e = engine ?: return
        if (e.turn != 1 || e.isOver || e.missOpen) return
        val hidden = e.tiles.indices.filter { e.tiles[it] == TileState.HIDDEN }
        if (hidden.isEmpty()) return
        val tile = if (e.pending >= 0) {
            hidden.firstOrNull { e.icons[it] == e.icons[e.pending] } ?: hidden.random()
        } else hidden.random()
        send(PictureMatchCodec.encode(PictureMatchCodec.Msg.Pick(match, tile)))
    }
}
