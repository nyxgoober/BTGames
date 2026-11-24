package us.crafties.btgames.games.keymash

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import us.crafties.btgames.net.Control
import us.crafties.btgames.net.Side
import us.crafties.btgames.net.Transport
import us.crafties.btgames.shell.GameSession

enum class KeymashPhase { SETUP, COUNTDOWN, PLAYING, OVER }

enum class KeymashResult { WIN, LOSS, DRAW }

/** Everything the Keymash screen draws. A fresh copy is published on every keystroke and tick. */
data class KeymashUi(
    val phase: KeymashPhase,
    val round: Int,
    /** This phone picks the mode for [round]. RED picks even rounds, BLUE odd ones. */
    val iChoose: Boolean,
    val mode: KeymashMode?,
    val countdownLeft: Int,
    val targets: List<String>,
    /** Which prompt is being typed. */
    val index: Int,
    /** Correctness of each submitted word (words mode only; sentences must match exactly). */
    val wordOk: List<Boolean>,
    /** The live contents of the input field. */
    val field: String,
    val liveWpm: Int,
    val liveAcc: Int,
    val myScore: Int,
    val myWpm: Int,
    val myAcc: Int,
    val theirCompleted: Int,
    val theirTotal: Int,
    val theirWpm: Int,
    val theirScore: Int,
    val myDone: Boolean,
    val theyDone: Boolean,
    val outcome: KeymashResult?,
    val myWins: Int,
    val theirWins: Int,
    val draws: Int,
    val iWantRematch: Boolean,
    val theyWantRematch: Boolean,
    val myNick: String,
    val theirNick: String,
    val mySide: Side,
)

/**
 * A typing race: both phones type the same prompts, generated from one shared seed so the text
 * itself never crosses the wire. Points are words-per-minute times accuracy; the higher score
 * when both are done wins.
 *
 * Words mode is monkeytype-style: 25 random words, space submits each one, submitted words lock.
 * Sentence mode is typeracer-style: 3 sentences, accepted case-insensitively with an
 * optional closing dot.
 * A player who stalls gets their partial run scored after [KeymashContent.PLAY_TIMEOUT_MS].
 */
class KeymashSession(
    private val scope: CoroutineScope,
    private val transport: Transport,
    playsFirst: Boolean,
    private val mySide: Side,
    myNick: String,
    theirNick: String,
) : GameSession {

    private var round = 0
    private var phase = KeymashPhase.SETUP
    private var mode: KeymashMode? = null
    private var seed = 0L
    private var targets: List<String> = emptyList()

    private var index = 0
    private val wordOk = mutableListOf<Boolean>()
    private var field = ""
    private var addedChars = 0
    private var correctChars = 0

    private var countdownEnds = 0L
    private var playStart = 0L
    private var myDone = false
    private var myScore = 0
    private var myWpm = 0
    private var myAcc = 0

    private var theirCompleted = 0
    private var theirTotal = 0
    private var theirWpm = 0
    private var theirScore = 0
    private var theyDone = false

    private var outcome: KeymashResult? = null
    private var myWins = 0
    private var theirWins = 0
    private var draws = 0

    private var iWantRematch = false
    private var theyWantRematch = false

    private var lastSentAt = 0L
    private var seenPeerUpdate = false

    private val nickMe = myNick
    private val nickThem = theirNick

    private val _ui = MutableStateFlow(snapshot())
    val ui: StateFlow<KeymashUi> = _ui.asStateFlow()

    private var listeners: Job? = null
    private var loop: Job? = null

    private fun iChoose(): Boolean = (round % 2 == 0) == (mySide == Side.RED)

    override fun start() {
        listeners = scope.launch {
            transport.control.collect { msg ->
                if (msg is Control.Game) KeymashCodec.decode(msg.payload)?.let(::onMessage)
            }
        }
        loop = scope.launch {
            while (true) {
                tick()
                delay(TICK_MS)
            }
        }
    }

    override fun stop() {
        listeners?.cancel(); loop?.cancel()
    }

    // ---- local input --------------------------------------------------------------------

    fun chooseMode(m: KeymashMode) {
        if (phase != KeymashPhase.SETUP || !iChoose()) return
        mode = m
        seed = kotlin.random.Random.nextLong() and 0xFFFFFFFFL
        transport.sendControl(Control.Game(KeymashCodec.encodeChoice(round, m, seed)))
        enterCountdown()
    }

    /** Every keystroke in the input field comes through here. */
    fun onFieldChange(new: String) {
        val m = mode
        if (phase != KeymashPhase.PLAYING || myDone || m == null || index >= targets.size) return
        val clean = new.replace('\n', ' ')
        if (clean.length > field.length) addedChars += clean.length - field.length
        when (m) {
            KeymashMode.WORDS -> onWordsChange(clean)
            KeymashMode.SENTENCE -> {
                field = clean
                // A keyboard often leaves a trailing space after the last word.
                if (KeymashContent.sentenceMatches(field.trimEnd(), targets[index])) submitSentence()
                else publish()
            }
        }
    }

    /** Keyboard Done button: submit the field in either mode. */
    fun submitCurrentWord() {
        val m = mode
        if (phase != KeymashPhase.PLAYING || myDone || index >= targets.size || field.isBlank()) return
        if (m == KeymashMode.SENTENCE) {
            if (KeymashContent.sentenceMatches(field.trim(), targets[index])) submitSentence()
            return
        }
        if (m == KeymashMode.WORDS) {
            submitWord(field.trim())
            field = ""
            publish()
        }
    }

    // ---- network input ------------------------------------------------------------------

    private fun onMessage(m: KeymashCodec.Msg) {
        when (m) {
            is KeymashCodec.Msg.Choice -> {
                if (m.round != (round and 0xFF) || phase != KeymashPhase.SETUP || iChoose()) return
                mode = m.mode
                seed = m.seed
                enterCountdown()
            }
            is KeymashCodec.Msg.Update -> {
                // Once the verdict is locked in, a duplicate or delayed update must never
                // rewrite the score shown under it.
                if (m.round != (round and 0xFF) || phase == KeymashPhase.OVER) return
                seenPeerUpdate = true
                if (m.completed >= theirCompleted) {
                    theirCompleted = m.completed
                    theirTotal = m.total
                    theirWpm = m.wpm
                    theirScore = m.score
                }
                if (m.done) theyDone = true
                publish()
            }
            is KeymashCodec.Msg.Rematch -> {
                if (m.round == ((round + 1) and 0xFF) && phase == KeymashPhase.OVER) {
                    theyWantRematch = true
                    maybeStartRematch()
                    publish()
                }
            }
        }
    }

    // ---- typing ---------------------------------------------------------------------------

    private fun onWordsChange(clean: String) {
        var rest = clean
        var guard = 0
        // A space submits the word; loop so a paste with several words submits them all.
        while (true) {
            val space = rest.indexOf(' ')
            if (space < 0 || guard++ > targets.size + 2) {
                field = rest
                publish()
                return
            }
            val token = rest.substring(0, space)
            rest = rest.substring(space + 1).trimStart()
            if (token.isNotEmpty()) {
                submitWord(token)
                if (myDone) {
                    field = ""
                    return // the race is over; drop whatever trailed the last word
                }
            }
        }
    }

    private fun submitWord(token: String) {
        val target = targets[index]
        correctChars += KeymashContent.wordCredit(token, target)
        wordOk.add(token == target)
        index++
        if (index >= targets.size) finish(partial = false) else sendUpdate(force = true)
    }

    private fun submitSentence() {
        correctChars += field.length // accepted text is all correct; the final dot is optional
        index++
        field = ""
        if (index >= targets.size) finish(partial = false) else sendUpdate(force = true)
    }

    private fun finish(partial: Boolean) {
        if (myDone) return
        if (partial && index < targets.size) {
            // Stalled out: bank the correct prefix of the unfinished prompt and stop.
            correctChars += if (mode == KeymashMode.SENTENCE) {
                KeymashContent.sentencePrefixMatches(field, targets[index])
            } else {
                KeymashContent.prefixMatches(field, targets[index])
            }
        }
        val elapsed = SystemClock.uptimeMillis() - playStart
        myWpm = KeymashContent.wpmOf(correctChars, elapsed).toInt()
        myAcc = (KeymashContent.accuracyOf(correctChars, addedChars) * 100).toInt()
        myScore = KeymashContent.scoreOf(correctChars, addedChars, elapsed)
        myDone = true
        field = ""
        sendUpdate(force = true)
        publish()
    }

    // ---- loop -------------------------------------------------------------------------------

    private fun tick() {
        val now = SystemClock.uptimeMillis()
        when (phase) {
            KeymashPhase.SETUP -> Unit
            KeymashPhase.COUNTDOWN -> {
                if (iChoose() && !seenPeerUpdate && now - lastSentAt > RESEND_MS) {
                    lastSentAt = now
                    val m = mode
                    if (m != null) {
                        transport.sendControl(Control.Game(KeymashCodec.encodeChoice(round, m, seed)))
                    }
                }
                if (now >= countdownEnds) {
                    phase = KeymashPhase.PLAYING
                    playStart = now
                    sendUpdate(force = true)
                }
                publish()
            }
            KeymashPhase.PLAYING -> {
                if (!myDone) {
                    if (now - playStart >= KeymashContent.PLAY_TIMEOUT_MS) finish(partial = true)
                    else if (now - lastSentAt > HEARTBEAT_MS) sendUpdate(force = false)
                } else {
                    if (now - lastSentAt > RESEND_MS) sendUpdate(force = true)
                    // Decide only when both runs are final. Comparing my final score with their
                    // last live score can give the two phones opposite winners.
                    if (theyDone) finishMatch()
                }
                publish()
            }
            KeymashPhase.OVER -> {
                if (iWantRematch && now - lastSentAt > RESEND_MS) {
                    lastSentAt = now
                    transport.sendControl(Control.Game(KeymashCodec.encodeRematch(round + 1)))
                }
            }
        }
    }

    private fun liveScore(): Int {
        val m = mode ?: return 0
        if (m == KeymashMode.WORDS && index >= targets.size) return myScore
        val elapsed = SystemClock.uptimeMillis() - playStart
        return KeymashContent.scoreOf(correctChars, addedChars, elapsed)
    }

    private fun liveWpm(): Int {
        if (myDone) return myWpm
        val elapsed = SystemClock.uptimeMillis() - playStart
        return KeymashContent.wpmOf(correctChars, elapsed).toInt()
    }

    private fun sendUpdate(force: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastSentAt < HEARTBEAT_MS) return
        lastSentAt = now
        val m = mode ?: return
        val total = targets.size
        transport.sendControl(
            Control.Game(
                KeymashCodec.encodeUpdate(
                    round = round,
                    done = myDone,
                    completed = index.coerceAtMost(255),
                    total = total.coerceAtMost(255),
                    wpm = if (myDone) myWpm else liveWpm(),
                    score = if (myDone) myScore else liveScore(),
                ),
            ),
        )
    }

    private fun finishMatch() {
        if (phase == KeymashPhase.OVER) return
        phase = KeymashPhase.OVER
        sendUpdate(force = true)
        outcome = when {
            myScore > theirScore -> KeymashResult.WIN
            myScore < theirScore -> KeymashResult.LOSS
            else -> KeymashResult.DRAW
        }
        when (outcome) {
            KeymashResult.WIN -> myWins++
            KeymashResult.LOSS -> theirWins++
            KeymashResult.DRAW -> draws++
            null -> Unit
        }
        publish()
    }

    private fun enterCountdown() {
        val m = mode ?: return
        targets = KeymashContent.targetsFor(m, seed)
        index = 0
        wordOk.clear()
        field = ""
        addedChars = 0
        correctChars = 0
        phase = KeymashPhase.COUNTDOWN
        countdownEnds = SystemClock.uptimeMillis() + COUNTDOWN_MS
        publish()
    }

    private fun maybeStartRematch() {
        if (!iWantRematch || !theyWantRematch) return
        round++
        phase = KeymashPhase.SETUP
        mode = null
        targets = emptyList()
        index = 0
        wordOk.clear()
        field = ""
        addedChars = 0
        correctChars = 0
        myDone = false
        theyDone = false
        myScore = 0; myWpm = 0; myAcc = 0
        theirCompleted = 0; theirTotal = 0; theirWpm = 0; theirScore = 0
        outcome = null
        iWantRematch = false
        theyWantRematch = false
        seenPeerUpdate = false
        lastSentAt = 0L
        publish()
    }

    // ---- snapshot -----------------------------------------------------------------------------

    private fun snapshot(): KeymashUi {
        val now = SystemClock.uptimeMillis()
        val countdownLeft = if (phase == KeymashPhase.COUNTDOWN) {
            ((countdownEnds - now) / 1000).toInt().coerceIn(0, 3)
        } else 0
        return KeymashUi(
            phase = phase,
            round = round,
            iChoose = iChoose(),
            mode = mode,
            countdownLeft = countdownLeft,
            targets = targets,
            index = index,
            wordOk = wordOk.toList(),
            field = field,
            liveWpm = if (phase == KeymashPhase.PLAYING && !myDone) liveWpm() else 0,
            liveAcc = if (phase == KeymashPhase.PLAYING && addedChars > 0) {
                (KeymashContent.accuracyOf(correctChars, addedChars) * 100).toInt()
            } else 0,
            myScore = myScore,
            myWpm = myWpm,
            myAcc = myAcc,
            theirCompleted = theirCompleted,
            theirTotal = theirTotal,
            theirWpm = theirWpm,
            theirScore = theirScore,
            myDone = myDone,
            theyDone = theyDone,
            outcome = outcome,
            myWins = myWins,
            theirWins = theirWins,
            draws = draws,
            iWantRematch = iWantRematch,
            theyWantRematch = theyWantRematch,
            myNick = nickMe,
            theirNick = nickThem,
            mySide = mySide,
        )
    }

    private fun publish() { _ui.value = snapshot() }

    @Composable
    override fun Content(onLeave: () -> Unit) {
        val state by ui.collectAsStateWithLifecycle()
        KeymashScreen(
            state = state,
            onChoose = ::chooseMode,
            onField = ::onFieldChange,
            onSubmitWord = ::submitCurrentWord,
            onRematch = ::requestRematch,
            onLeave = onLeave,
        )
    }

    fun requestRematch() {
        if (phase != KeymashPhase.OVER || iWantRematch) return
        iWantRematch = true
        transport.sendControl(Control.Game(KeymashCodec.encodeRematch(round + 1)))
        maybeStartRematch()
        publish()
    }

    companion object {
        const val TICK_MS = 100L
        const val COUNTDOWN_MS = 3000L
        const val HEARTBEAT_MS = 1000L
        const val RESEND_MS = 1500L
    }
}
