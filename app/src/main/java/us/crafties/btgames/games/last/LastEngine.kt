package us.crafties.btgames.games.last

import java.util.Random

enum class LastColor(val code: Int) {
    RED(0), YELLOW(1), GREEN(2), BLUE(3);

    companion object {
        fun fromCode(c: Int): LastColor? = entries.firstOrNull { it.code == c }
    }
}

enum class LastKind(val code: Int) {
    NUMBER(0),
    /** Next player is banned: loses their turn. */
    BAN(1),
    /** Reverses direction (acts as a Ban with two players). */
    SPIN(2),
    /** Next player draws 2 and loses their turn. */
    PLUS2(3),
    /** Wild: pick the colour. */
    COLOR_CHANGE(4),
    /** Wild: pick the colour; next player draws 2² = 4 and loses their turn. */
    COLOR_PLUS2_SQ(5);

    val isWild: Boolean get() = this == COLOR_CHANGE || this == COLOR_PLUS2_SQ
}

/** A card. Wilds have no colour of their own; the colour in play lives in the engine. */
data class LastCard(val kind: LastKind, val color: LastColor?, val number: Int = 0)

/** What just happened, for the one line under the table. [seat] is who did it. */
data class LastEvent(
    val type: Type,
    val seat: Int,
    /** The player who was hit by a Ban / Plus2 / Plus2², or who was caught, or -1. */
    val target: Int = -1,
    /** Cards drawn (by [target], or by [seat] for DREW / SLAPPED). */
    val amount: Int = 0,
    val card: LastCard? = null,
    /** SLAPPED: every seat that drew, as a bit mask. */
    val mask: Int = 0,
) {
    /** DEFENDED: [seat] blocked or reversed an attack with a Ban or Spin. */
    enum class Type { PLAYED, DREW, PASSED, CALLED, CAUGHT, SLAPPED, DOUBLED, EXCHANGED, DEFENDED }
}

/** What happens when you cannot (or do not want to) play a card. */
enum class DrawRule(val code: Int) {
    /** Draw one; if it fits you may play it or pass. */
    ONE(0),
    /** Keep drawing until a card fits; you may play it or pass. */
    UNTIL_MATCH(1),
    /** Draw two and your turn is over. */
    TWO(2);

    companion object {
        fun fromCode(c: Int): DrawRule? = entries.firstOrNull { it.code == c }
    }
}

/** The host's choices for a match. Every phone builds its engine from the same rules. */
data class LastRules(
    val handSize: Int = DEFAULT_HAND,
    /** Playing a 6 starts a slap: everybody slaps, the last one to do so draws 2. */
    val slapSix: Boolean = false,
    val draw: DrawRule = DrawRule.ONE,
    /** A drawn card that fits must be played: no keeping it and passing. Drawing stays allowed any time. */
    val forcePlay: Boolean = false,
    /**
     * Doubling: after a number card, any cards of the same number in your hand (whatever their
     * colour) may follow it, one after another. Your turn only ends once the referee has seen
     * [DOUBLE_WINDOW_MS] pass without another one (or you have none left to play).
     */
    val doubling: Boolean = false,
    /**
     * Special 0 & 7. A played 0 moves every hand one seat on in the direction of play; a played 7
     * swaps the mover's hand with any other player's (you only ever see how many cards they hold).
     * 0 and 7 never start a double while this rule is on.
     */
    val zeroSeven: Boolean = false,
    /** A +2/+4 can be answered with a same-colour Ban (block) or Spin (reverse) before it lands. */
    val defense: Boolean = false,
    /** A +2 of the chosen colour may be stacked on a +4. A +4 on a +4 always stacks. */
    val stackLesser: Boolean = false,
) {
    init { require(handSize in MIN_HAND..MAX_HAND) }

    companion object {
        const val MIN_HAND = 3
        const val MAX_HAND = 15
        const val DEFAULT_HAND = 7
        /** How long the mover has to follow a number card with another of the same number. */
        const val DOUBLE_WINDOW_MS = 2000L
    }
}

/**
 * One accepted change to the game. The host decides the order of these and every phone applies
 * them in that order, so a Last! catch or a slap penalty can never land at a different point in
 * somebody's copy of the game.
 */
sealed interface LastMove {
    data class Play(val seat: Int, val hand: Int, val colour: LastColor?) : LastMove
    data class Draw(val seat: Int) : LastMove
    data class Pass(val seat: Int) : LastMove
    /** Doubling: [seat] let the window pass without another card of the number, so the turn moves on. */
    data class EndDouble(val seat: Int) : LastMove
    /** [seat] says Last!: arms it when they hold two cards, or saves them when it is already open to catch. */
    data class Call(val seat: Int) : LastMove
    /** The played 7 swaps [seat]'s hand with [target]'s. */
    data class Exchange(val seat: Int, val hand: Int, val target: Int) : LastMove
    /** The target answers a +2/+4 with a same-colour Ban (block) or Spin (reverse). */
    data class Defend(val seat: Int, val hand: Int) : LastMove
    /** The defence window ran out: the target takes the pending penalty. */
    data class DefenseTimeout(val round: Int) : LastMove
    /** The window on [target] closed: [catcher] caught them (draw 2), or -1 = they got away with it. */
    data class Catch(val target: Int, val catcher: Int) : LastMove
    /** The slap round [round] is over; every seat in [losers] draws 2. */
    data class Slap(val round: Int, val losers: Int) : LastMove
}

/**
 * Pure rules for Last!, with no Android and no clock. Every phone runs its own copy from the
 * same [seed] and [rules] and applies the same [LastMove]s in the same order (the host numbers
 * them), so all copies stay identical and only moves ever cross Bluetooth. Shuffling uses
 * [java.util.Random] with an explicit seed, whose algorithm is fixed by the Java spec, so every
 * device deals the same cards.
 *
 * Rules:
 *  - Match the top card by colour, by number, or by action; wilds always play.
 *  - Nothing to play (or don't want to)? Draw, as the host's [DrawRule] says. If a drawn card fits
 *    you may play it, or keep everything and pass (unless the host turned on Force Play: then you
 *    must play it).
 *  - Down to one card: say "Last!" before or just after playing the second-to-last. Don't, and for
 *    a few seconds anyone can catch you (draw 2). The timing is the referee's job (see
 *    [LastReferee]); the engine only knows who is open to a catch.
 *  - With slap-on-6 on, a played 6 starts a slap; the last to slap draws 2.
 *  - With Doubling on, a number card may be followed by more cards of the same number (any
 *    colour). The turn stays with the mover until the referee ends it with [LastMove.EndDouble]
 *    (2 seconds without another), or at once when they have no card of that number left.
 *  - First to empty their hand wins.
 */
class LastEngine(val players: Int, private val seed: Long, val rules: LastRules = LastRules()) {

    init { require(players in MIN_PLAYERS..MAX_PLAYERS) }

    val hands: List<MutableList<LastCard>> = List(players) { mutableListOf() }

    private val drawPile = ArrayDeque<LastCard>()   // top of the pile is the LAST element
    private val discard = ArrayList<LastCard>()      // top of the pile is the LAST element
    /** Stack of played cards for visual stacking (limited to last 10). */
    private val playPile = mutableListOf<LastCard>()
    /** Running number of each card in [playPile] (the nth card ever played); stable, so the UI can give every card its own small offset and turn. */
    private val playIds = mutableListOf<Int>()
    private var playCount = 0
    /** Update the session's play pile (kept limited to the last 10 cards). */
    private fun updatePlayPile(card: LastCard) {
        playPile.add(card)
        playIds.add(playCount++)
        if (playPile.size > 10) { playPile.removeAt(0); playIds.removeAt(0) }
    }

    /** Snapshot of the last 10 played cards, oldest to newest, for stacking. */
    val playPileSnapshot: List<LastCard> get() = playPile.toList()
    /** The running numbers of [playPileSnapshot], same order. Identical on every phone. */
    val playPileIds: List<Int> get() = playIds.toList()

    private var reshuffles = 0

    var currentColor: LastColor = LastColor.RED
        private set
    var turn: Int = 0
        private set
    /** +1 or -1 round the table. */
    var direction: Int = 1
        private set
    var winner: Int = -1
        private set
    /** The cards the mover just drew sit at [drewFrom] until [drewTo] (exclusive) in their hand; -1 when none are playable. */
    var drewFrom: Int = -1
        private set
    var drewTo: Int = -1
        private set
    /** True while the player to move has just drawn something playable and may play it or pass. */
    val drewPlayable: Boolean get() = drewFrom >= 0
    /** May the card at [index] of the mover's hand be played? After a draw only the drawn cards may, and only if they fit. */
    fun playableAt(index: Int, card: LastCard): Boolean =
        if (slapBlocksTurn || defenseOpen) false
        else if (doubleOpen) card.kind == LastKind.NUMBER && card.number == doubleNumber   // a double: same number, any colour
        else canPlay(card) && (drewFrom < 0 || index in drewFrom until drewTo)

    /** Doubling: the number the mover may still follow up with, or -1 when no double window is open. */
    var doubleNumber: Int = -1
        private set
    val doubleOpen: Boolean get() = doubleNumber >= 0
    /** A slap round holds the turn until it is settled; a double in progress may still continue. */
    val slapBlocksTurn: Boolean get() = slapOpen && !doubleOpen
    /** Cards played so far in the current double (counts the first card of the run). */
    private var doubleCount = 0
    /** Counts accepted moves, so a stale or repeated message is recognised and ignored. */
    var moveNo: Int = 0
        private set
    var lastEvent: LastEvent? = null
        private set

    private val armedFlags = BooleanArray(players)
    private val windows = mutableSetOf<Int>()
    /** Seats holding one card they never called, still open to a catch. */
    val catchable: Set<Int> get() = windows
    /** Has [seat] said Last! ahead of playing down to one card? */
    fun armed(seat: Int): Boolean = armedFlags[seat]

    /** Counts slap rounds; a round is open from a played 6 until the referee closes it. */
    var slapRound: Int = 0
        private set
    var slapOpen: Boolean = false
        private set

    /** Counts defence windows; one opens when a +2/+4 is aimed at somebody. */
    var defenseRound: Int = 0
        private set
    var defenseOpen: Boolean = false
        private set
    var defenseTarget: Int = -1
        private set
    var defenseSource: Int = -1
        private set
    var defenseColor: LastColor? = null
        private set
    var defenseAmount: Int = 0
        private set

    /** The running punishment on the player the last +2/+4 was aimed at. */
    var penaltyTarget: Int = -1
        private set
    var penaltyAmount: Int = 0
        private set
    var penaltyKind: LastKind? = null
        private set
    var penaltyColor: LastColor? = null
        private set

    /** Cards the targeted player may play while an attack is pending. */
    fun defenseCards(seat: Int): List<Int> {
        if (!defenseOpen || seat != defenseTarget) return emptyList()
        return responseIndices(seat, defenseColor ?: return emptyList(), penaltyKind ?: return emptyList())
    }

    val topCard: LastCard get() = discard.last()
    val drawCount: Int get() = drawPile.size

    init {
        val deck = fullDeck().toMutableList()
        shuffle(deck, Random(seed))
        drawPile.addAll(deck)
        repeat(rules.handSize) { for (p in 0 until players) hands[p].add(drawPile.removeLast()) }
        // Start on a plain number card; anything else goes to the bottom of the pile.
        while (true) {
            val c = drawPile.removeLast()
            if (c.kind == LastKind.NUMBER) { discard.add(c); updatePlayPile(c); currentColor = c.color!!; break }
            drawPile.addFirst(c)
        }
    }

    fun canPlay(card: LastCard): Boolean {
        if (card.kind.isWild) return true
        if (card.color == currentColor) return true
        val top = topCard
        return if (card.kind == LastKind.NUMBER) top.kind == LastKind.NUMBER && top.number == card.number
        else card.kind == top.kind
    }

    /** Apply one move; false (and nothing changed) if it is not legal right now. */
    fun apply(m: LastMove): Boolean = when (m) {
        is LastMove.Play -> play(m.seat, m.hand, m.colour)
        is LastMove.Draw -> draw(m.seat)
        is LastMove.Pass -> pass(m.seat)
        is LastMove.EndDouble -> endDouble(m.seat)
        is LastMove.Call -> call(m.seat)
        is LastMove.Catch -> catchResult(m.target, m.catcher)
        is LastMove.Slap -> slapResult(m.round, m.losers)
        is LastMove.Exchange -> exchange(m.seat, m.hand, m.target)
        is LastMove.Defend -> defend(m.seat, m.hand)
        is LastMove.DefenseTimeout -> defenseTimeout(m.round)
    }

    /** Cards the target may use to answer a pending attack: defence or a legal stack. */
    private fun responseIndices(seat: Int, color: LastColor, attackKind: LastKind): List<Int> {
        val hand = hands.getOrElse(seat) { emptyList() }
        val out = mutableListOf<Int>()
        hand.forEachIndexed { i, c ->
            val defence = rules.defense && c.color == color && (c.kind == LastKind.BAN || c.kind == LastKind.SPIN)
            val stack = attackKind == LastKind.COLOR_PLUS2_SQ &&
                (c.kind == LastKind.COLOR_PLUS2_SQ || (rules.stackLesser && c.kind == LastKind.PLUS2 && c.color == color))
            if (defence || stack) out.add(i)
        }
        return out
    }

    private fun canRespond(seat: Int, color: LastColor, attackKind: LastKind): Boolean =
        responseIndices(seat, color, attackKind).isNotEmpty()

    /** Play the card at [handIndex] of [seat]'s hand. [chosen] is required for a wild. */
    fun play(seat: Int, handIndex: Int, chosen: LastColor?): Boolean {
        if (winner >= 0 || seat != turn || slapBlocksTurn || defenseOpen) return false
        val hand = hands[seat]
        if (handIndex !in hand.indices) return false
        val card = hand[handIndex]
        if (card.kind.isWild && chosen == null) return false
        // After drawing, only the cards just drawn may be played, and only if they fit.
        if (!playableAt(handIndex, card)) return false
        val wasDouble = doubleOpen

        hand.removeAt(handIndex)
        discard.add(card)
        currentColor = card.color ?: chosen!!
        // Update UI play pile for visual stacking
        updatePlayPile(card)
        drewFrom = -1; drewTo = -1
        moveNo++

        if (hand.isEmpty()) {
            winner = seat
            doubleNumber = -1; doubleCount = 0
            windows.clear()
            slapOpen = false
            lastEvent = LastEvent(LastEvent.Type.PLAYED, seat, card = card)
            return true
        }

        // Down to one card: safe if Last! was said ahead of time, otherwise open to a catch.
        if (hand.size == 1 && !armedFlags[seat]) windows.add(seat)
        armedFlags[seat] = false

        // In a double only the first 6 starts a slap: the rest of the run does not restart it.
        if (rules.slapSix && !wasDouble && card.kind == LastKind.NUMBER && card.number == SLAP_NUMBER) {
            slapRound++
            slapOpen = true
        }

        var target = -1
        var amount = 0
        doubleNumber = -1
        when (card.kind) {
        LastKind.NUMBER -> {
            // Doubling: another card of this number in hand keeps the turn open for a follow-up.
            // A 6 never doubles with slap on 6 on, and a special 0/7 never doubles while the
            // special rule is on (their effect belongs to one move, not to a follow-up window).
            val special = rules.zeroSeven && (card.number == 0 || card.number == 7)
            val more = rules.doubling && !(rules.slapSix && card.number == SLAP_NUMBER) && !special &&
                hand.any { it.kind == LastKind.NUMBER && it.number == card.number }
            if (more) { doubleNumber = card.number; doubleCount = (if (wasDouble) doubleCount else 0) + 1 }
            else { doubleNumber = -1; advance(1) }
        }
            LastKind.COLOR_CHANGE -> advance(1)
            LastKind.BAN -> { target = step(1); advance(2) }
            LastKind.SPIN ->
                if (players == 2) { target = step(1); advance(2) }
                else { direction = -direction; advance(1) }
            LastKind.PLUS2 -> {
                val color = card.color!!
                target = step(1)
                penaltyTarget = target; penaltyAmount = 2
                penaltyKind = card.kind; penaltyColor = color
                if (canRespond(target, color, card.kind)) {
                    defenseRound++
                    defenseOpen = true
                    defenseTarget = target; defenseSource = seat
                    defenseColor = color; defenseAmount = 2
                } else {
                    amount = give(target, 2)
                }
                advance(2)
            }
            LastKind.COLOR_PLUS2_SQ -> {
                val color = currentColor
                target = step(1)
                penaltyTarget = target; penaltyAmount = 4
                penaltyKind = card.kind; penaltyColor = color
                if (canRespond(target, color, card.kind)) {
                    defenseRound++
                    defenseOpen = true
                    defenseTarget = target; defenseSource = seat
                    defenseColor = color; defenseAmount = 4
                } else {
                    amount = give(target, 4)
                }
                advance(2)
            }
        }
        lastEvent = LastEvent(LastEvent.Type.PLAYED, seat, target, amount, card)
        // Special 0: every hand moves one seat on in the direction of play.
        if (rules.zeroSeven && card.kind == LastKind.NUMBER && card.number == 0) rotateHands(direction)
        return true
    }

    /**
     * Special 7: play the card at [handIndex] (a 7) and swap hands with [target], all as one move.
     * You only ever see how many cards the others hold — the choice is blind by design.
     */
    fun exchange(seat: Int, handIndex: Int, target: Int): Boolean {
        if (!rules.zeroSeven) return false
        if (target !in 0 until players || target == seat) return false
        val card = hands.getOrNull(seat)?.getOrNull(handIndex) ?: return false
        if (!(card.kind == LastKind.NUMBER && card.number == 7 && playableAt(handIndex, card))) return false
        if (!play(seat, handIndex, null)) return false
        if (winner >= 0) return true          // the 7 was the last card: they won with their own hand
        val mine = ArrayList(hands[seat])
        hands[seat].apply { clear(); addAll(hands[target]) }
        hands[target].apply { clear(); addAll(mine) }
        settle(seat); settle(target)
        lastEvent = LastEvent(LastEvent.Type.EXCHANGED, seat, target, card = card)
        return true
    }

    /**
     * Answer a pending attack. A Ban blocks it, a Spin sends it back, and a matching attack card
     * stacks it: the accumulated punishment passes to the next player, who may answer in turn.
     */
    fun defend(seat: Int, handIndex: Int): Boolean {
        if (!defenseOpen || seat != defenseTarget || winner >= 0) return false
        val hand = hands.getOrNull(seat) ?: return false
        val card = hand.getOrNull(handIndex) ?: return false
        if (handIndex !in responseIndices(seat, defenseColor ?: return false, penaltyKind ?: return false)) return false
        hand.removeAt(handIndex)
        discard.add(card)
        updatePlayPile(card)
        moveNo++

        val source = defenseSource
        val owed = penaltyAmount
        val myCards = hand.size
        var keepWindow = false

        when (card.kind) {
            LastKind.BAN -> {
                // Blocked: nobody draws, the turn continues from where the attack left it.
                lastEvent = LastEvent(LastEvent.Type.DEFENDED, seat, source, card = card)
            }
            LastKind.SPIN -> {
                // Reversed: the attacker takes the punishment instead.
                direction = -direction
                val drawn = give(source, owed)
                settle(source)
                turn = source
                lastEvent = LastEvent(LastEvent.Type.DEFENDED, seat, source, drawn, card)
            }
            LastKind.PLUS2, LastKind.COLOR_PLUS2_SQ -> {
                // Stacked: the next player now faces the accumulated punishment.
                val added = if (card.kind == LastKind.PLUS2) 2 else 4
                val newTarget = nextAfter(seat)
                penaltyAmount = owed + added
                penaltyTarget = newTarget
                penaltyKind = LastKind.COLOR_PLUS2_SQ
                penaltyColor = defenseColor
                turn = nextAfter(newTarget)
                if (canRespond(newTarget, penaltyColor!!, penaltyKind!!)) {
                    defenseRound++
                    defenseOpen = true
                    defenseTarget = newTarget
                    defenseSource = seat
                    defenseColor = penaltyColor
                    defenseAmount = penaltyAmount
                    keepWindow = true
                    lastEvent = LastEvent(LastEvent.Type.DEFENDED, seat, newTarget, card = card)
                } else {
                    val drawn = give(newTarget, penaltyAmount)
                    settle(newTarget)
                    lastEvent = LastEvent(LastEvent.Type.DEFENDED, seat, newTarget, drawn, card)
                }
            }
            else -> return false
        }

        if (myCards == 0) winner = seat
        if (!keepWindow) {
            defenseOpen = false
            defenseTarget = -1; defenseSource = -1; defenseColor = null; defenseAmount = 0
            penaltyTarget = -1; penaltyAmount = 0; penaltyKind = null; penaltyColor = null
        }
        return true
    }

    /** The defence window ran out: the target takes the accumulated punishment. */
    fun defenseTimeout(round: Int): Boolean {
        if (!defenseOpen || round != defenseRound || winner >= 0) return false
        val target = defenseTarget
        val owed = penaltyAmount
        defenseOpen = false
        defenseTarget = -1; defenseSource = -1; defenseColor = null; defenseAmount = 0
        penaltyTarget = -1; penaltyAmount = 0; penaltyKind = null; penaltyColor = null
        moveNo++
        val drawn = give(target, owed)
        settle(target)
        lastEvent = LastEvent(LastEvent.Type.PLAYED, target, amount = drawn)
        return true
    }

    /**
     * Draw as the [DrawRule] says. If any drawn card fits the turn stays: play one of the drawn
     * cards that fits, or keep them all and pass. Otherwise the turn moves on.
     */
    fun draw(seat: Int): Boolean {
        if (winner >= 0 || seat != turn || drewFrom >= 0 || doubleOpen || slapOpen || defenseOpen) return false
        val hand = hands[seat]
        val start = hand.size
        when (rules.draw) {
            DrawRule.ONE -> take()?.let { hand.add(it) }
            DrawRule.UNTIL_MATCH -> while (true) {
                val c = take() ?: break
                hand.add(c)
                if (canPlay(c)) break
            }
            DrawRule.TWO -> repeat(2) { take()?.let { hand.add(it) } }
        }
        val drawn = hand.size - start
        moveNo++
        settle(seat)
        if (drawn == 0) {
            lastEvent = LastEvent(LastEvent.Type.PASSED, seat)
            advance(1)
            return true
        }
        lastEvent = LastEvent(LastEvent.Type.DREW, seat, amount = drawn)
        if ((start until hand.size).any { canPlay(hand[it]) }) { drewFrom = start; drewTo = hand.size }
        else advance(1)
        return true
    }

    /** Doubling: the window for another card of the same number is over; the turn moves on. */
    fun endDouble(seat: Int): Boolean {
        if (winner >= 0 || !doubleOpen || seat != turn || defenseOpen) return false
        val n = doubleCount
        doubleNumber = -1; doubleCount = 0
        moveNo++
        lastEvent = LastEvent(LastEvent.Type.DOUBLED, seat, amount = n)
        advance(1)
        return true
    }

    /** After drawing something playable, keep the cards and end the turn. */
    fun pass(seat: Int): Boolean {
        if (winner >= 0 || seat != turn || drewFrom < 0 || rules.forcePlay || slapOpen || defenseOpen) return false
        drewFrom = -1; drewTo = -1
        moveNo++
        lastEvent = LastEvent(LastEvent.Type.PASSED, seat)
        advance(1)
        return true
    }

    /**
     * "Last!". With two cards in hand it arms the call for the play that leaves one; with one card
     * left and the window still open it closes the window, saving the caller.
     */
    fun call(seat: Int): Boolean {
        if (winner >= 0 || seat !in 0 until players) return false
        val size = hands[seat].size
        when {
            size == 2 && !armedFlags[seat] -> armedFlags[seat] = true
            size == 1 && seat in windows -> windows.remove(seat)
            else -> return false
        }
        moveNo++
        lastEvent = LastEvent(LastEvent.Type.CALLED, seat)
        return true
    }

    /** The window on [target] is over: [catcher] caught them (draw 2) or, if -1, they got away. */
    fun catchResult(target: Int, catcher: Int): Boolean {
        if (winner >= 0 || target !in windows || hands[target].size != 1) return false
        if (catcher == target || catcher >= players) return false
        windows.remove(target)
        moveNo++
        if (catcher >= 0) {
            val n = give(target, PENALTY)
            settle(target)
            lastEvent = LastEvent(LastEvent.Type.CAUGHT, catcher, target, n)
        }
        return true
    }

    /** The slap round is over: every seat in [losers] (a bit mask) draws 2. */
    fun slapResult(round: Int, losers: Int): Boolean {
        if (winner >= 0 || !slapOpen || round != slapRound) return false
        slapOpen = false
        moveNo++
        var first = -1
        var drew = 0
        for (s in 0 until players) if (losers and (1 shl s) != 0) {
            if (first < 0) first = s
            drew = give(s, PENALTY)
            settle(s)
        }
        if (first >= 0) lastEvent = LastEvent(LastEvent.Type.SLAPPED, first, amount = drew, mask = losers)
        return true
    }

    // ---- internals ------------------------------------------------------------------------

    private fun step(by: Int): Int = (((turn + direction * by) % players) + players) % players
    /** Seat [by] places after [from] in the direction of play. */
    private fun nextAfter(from: Int, by: Int = 1): Int = (((from + direction * by) % players) + players) % players

    private fun advance(by: Int) { turn = step(by) }

    /** Cards entering or leaving a hand cancel a Last! call or catch that no longer applies. */
    private fun settle(seat: Int) {
        val n = hands[seat].size
        if (n != 1) windows.remove(seat)
        if (n != 2) armedFlags[seat] = false
    }

    /** Special 0: hand [from]'s cards land in [to]'s lap, for every seat, following [dir]. */
    private fun rotateHands(dir: Int) {
        val moved = List(players) { ArrayList(hands[it]) }
        for (p in 0 until players) {
            val from = ((p - dir) % players + players) % players
            hands[p].clear(); hands[p].addAll(moved[from])
        }
        for (p in 0 until players) settle(p)
    }

    /** Give [seat] up to [n] cards; returns how many it actually got. */
    private fun give(seat: Int, n: Int): Int {
        var got = 0
        repeat(n) { take()?.let { hands[seat].add(it); got++ } }
        settle(seat)
        return got
    }

    /** One card off the draw pile, reshuffling the discard pile (minus its top) if it ran out. */
    private fun take(): LastCard? {
        if (drawPile.isEmpty() && discard.size > 1) {
            val top = discard.removeAt(discard.lastIndex)
            val rest = ArrayList(discard)
            discard.clear()
            discard.add(top)
            shuffle(rest, Random(seed * 31 + (++reshuffles)))
            drawPile.addAll(rest)
            // the top card stays on the table, so it stays in the visual stack too
            val topId = playIds.last()
            playPile.clear(); playIds.clear()
            playPile.add(top); playIds.add(topId)
        }
        return if (drawPile.isEmpty()) null else drawPile.removeLast()
    }

    companion object {
        const val MIN_PLAYERS = 2
        const val MAX_PLAYERS = 6
        const val PENALTY = 2
        const val SLAP_NUMBER = 6

        /** 108 cards: 0 and 1-9 twice, Ban/Spin/Plus2 twice, per colour; plus 4 of each wild. */
        fun fullDeck(): List<LastCard> = buildList {
            for (c in LastColor.entries) {
                add(LastCard(LastKind.NUMBER, c, 0))
                for (n in 1..9) repeat(2) { add(LastCard(LastKind.NUMBER, c, n)) }
                repeat(2) {
                    add(LastCard(LastKind.BAN, c))
                    add(LastCard(LastKind.SPIN, c))
                    add(LastCard(LastKind.PLUS2, c))
                }
            }
            repeat(4) {
                add(LastCard(LastKind.COLOR_CHANGE, null))
                add(LastCard(LastKind.COLOR_PLUS2_SQ, null))
            }
        }

        private fun <T> shuffle(list: MutableList<T>, rnd: Random) {
            for (i in list.size - 1 downTo 1) {
                val j = rnd.nextInt(i + 1)
                val t = list[i]; list[i] = list[j]; list[j] = t
            }
        }
    }
}
