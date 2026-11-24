package us.crafties.btgames.games.last

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LastEngineTest {

    private fun card(kind: LastKind, color: LastColor?, n: Int = 0) = LastCard(kind, color, n)
    private fun num(color: LastColor, n: Int) = card(LastKind.NUMBER, color, n)

    /** An engine whose top card is [color] [n], found by trying seeds. Hands are then set by the test. */
    private fun engineOnTop(players: Int, color: LastColor, n: Int): LastEngine {
        for (seed in 1L..5000L) {
            val e = LastEngine(players, seed)
            if (e.topCard == num(color, n)) return e
        }
        error("no seed found")
    }

    private fun setHand(e: LastEngine, seat: Int, vararg cards: LastCard) {
        e.hands[seat].clear(); e.hands[seat].addAll(cards)
    }

    /** Resolve a pending attack the way the host's timeout would. */
    private fun settleAttack(e: LastEngine) {
        if (!e.defenseOpen) return
        val move = LastMove.DefenseTimeout(e.defenseRound)
        e.apply(move)
    }

    @Test fun deckHas108CardsInTheRightMix() {
        val d = LastEngine.fullDeck()
        assertEquals(108, d.size)
        assertEquals(76, d.count { it.kind == LastKind.NUMBER })
        for (c in LastColor.entries) {
            assertEquals(1, d.count { it.kind == LastKind.NUMBER && it.color == c && it.number == 0 })
            for (n in 1..9) assertEquals(2, d.count { it.kind == LastKind.NUMBER && it.color == c && it.number == n })
            for (k in listOf(LastKind.BAN, LastKind.SPIN, LastKind.PLUS2)) assertEquals(2, d.count { it.kind == k && it.color == c })
        }
        assertEquals(4, d.count { it.kind == LastKind.COLOR_CHANGE })
        assertEquals(4, d.count { it.kind == LastKind.COLOR_PLUS2_SQ })
    }

    @Test fun dealGivesSevenEachAndStartsOnANumber() {
        for (n in 2..6) {
            val e = LastEngine(n, 99L)
            assertTrue(e.hands.all { it.size == 7 })
            assertEquals(LastKind.NUMBER, e.topCard.kind)
            assertEquals(e.topCard.color, e.currentColor)
            assertEquals(108 - 7 * n - 1, e.drawCount)
            assertEquals(0, e.turn)
        }
    }

    @Test fun sameSeedDealsTheSameAndDifferentSeedsDiffer() {
        assertEquals(LastEngine(4, 5L).hands, LastEngine(4, 5L).hands)
        assertNotEquals(LastEngine(4, 5L).hands, LastEngine(4, 6L).hands)
    }

    @Test fun matchByColourOrNumberButNotNeither() {
        val e = engineOnTop(3, LastColor.BLUE, 7)
        assertTrue(e.canPlay(num(LastColor.BLUE, 2)))
        assertTrue(e.canPlay(num(LastColor.RED, 7)))
        assertFalse(e.canPlay(num(LastColor.RED, 2)))
        assertFalse(e.canPlay(card(LastKind.BAN, LastColor.RED)))
        assertTrue(e.canPlay(card(LastKind.COLOR_CHANGE, null)))
        assertTrue(e.canPlay(card(LastKind.COLOR_PLUS2_SQ, null)))
    }

    @Test fun illegalOrOutOfTurnMovesChangeNothing() {
        val e = engineOnTop(3, LastColor.BLUE, 7)
        setHand(e, 0, num(LastColor.RED, 2), num(LastColor.BLUE, 1), num(LastColor.GREEN, 3))
        setHand(e, 1, num(LastColor.BLUE, 4), num(LastColor.BLUE, 5))
        val before = e.moveNo
        assertFalse(e.play(0, 0, null))      // red 2 on blue 7
        assertFalse(e.play(1, 0, null))      // not seat 1's turn
        assertFalse(e.play(0, 9, null))      // no such card
        assertEquals(before, e.moveNo)
        assertEquals(0, e.turn)
        assertEquals(3, e.hands[0].size)
    }

    @Test fun playingMovesTheTurnAndChangesTheColour() {
        val e = engineOnTop(3, LastColor.BLUE, 7)
        setHand(e, 0, num(LastColor.BLUE, 1), num(LastColor.GREEN, 3), num(LastColor.GREEN, 4))
        assertTrue(e.play(0, 0, null))
        assertEquals(1, e.turn)
        assertEquals(num(LastColor.BLUE, 1), e.topCard)
        // a number of another colour matches by number and switches the colour in play
        setHand(e, 1, num(LastColor.RED, 1), num(LastColor.RED, 9), num(LastColor.RED, 8))
        assertTrue(e.play(1, 0, null))
        assertEquals(LastColor.RED, e.currentColor)
    }

    @Test fun wildNeedsAColourAndSetsIt() {
        val e = engineOnTop(3, LastColor.BLUE, 7)
        setHand(e, 0, card(LastKind.COLOR_CHANGE, null), num(LastColor.RED, 1), num(LastColor.RED, 2))
        assertFalse(e.play(0, 0, null))
        assertTrue(e.play(0, 0, LastColor.GREEN))
        assertEquals(LastColor.GREEN, e.currentColor)
        assertEquals(1, e.turn)
    }

    @Test fun banSkipsTheNextPlayer() {
        val e = engineOnTop(3, LastColor.BLUE, 7)
        setHand(e, 0, card(LastKind.BAN, LastColor.BLUE), num(LastColor.RED, 1), num(LastColor.RED, 2))
        assertTrue(e.play(0, 0, null))
        assertEquals(2, e.turn)
        assertEquals(LastEvent.Type.PLAYED, e.lastEvent?.type)
        assertEquals(1, e.lastEvent?.target)
    }

    @Test fun spinReversesWithThreeOrMorePlayers() {
        val e = engineOnTop(3, LastColor.BLUE, 7)
        setHand(e, 0, card(LastKind.SPIN, LastColor.BLUE), num(LastColor.RED, 1), num(LastColor.RED, 2))
        assertTrue(e.play(0, 0, null))
        assertEquals(-1, e.direction)
        assertEquals(2, e.turn)          // 0 going the other way lands on seat 2
    }

    @Test fun spinActsAsABanWithTwoPlayers() {
        val e = engineOnTop(2, LastColor.BLUE, 7)
        setHand(e, 0, card(LastKind.SPIN, LastColor.BLUE), num(LastColor.RED, 1), num(LastColor.RED, 2))
        assertTrue(e.play(0, 0, null))
        assertEquals(0, e.turn)          // I go again
    }

    @Test fun plusTwoMakesTheNextPlayerDrawTwoAndLoseTheTurn() {
        val e = engineOnTop(3, LastColor.BLUE, 7)
        setHand(e, 0, card(LastKind.PLUS2, LastColor.BLUE), num(LastColor.RED, 1), num(LastColor.RED, 2))
        val before = e.hands[1].size
        assertTrue(e.play(0, 0, null))
        assertEquals(before + 2, e.hands[1].size)
        assertEquals(2, e.turn)
        assertEquals(2, e.lastEvent?.amount)
    }

    @Test fun colourChangePlusTwoSquaredDrawsFourAndPicksAColour() {
        val e = engineOnTop(3, LastColor.BLUE, 7)
        setHand(e, 0, card(LastKind.COLOR_PLUS2_SQ, null), num(LastColor.RED, 1), num(LastColor.RED, 2))
        val before = e.hands[1].size
        assertFalse(e.play(0, 0, null))
        assertTrue(e.play(0, 0, LastColor.YELLOW))
        settleAttack(e)
        assertEquals(before + 4, e.hands[1].size)
        assertEquals(LastColor.YELLOW, e.currentColor)
        assertEquals(2, e.turn)
    }

    @Test fun goingToOneCardWithoutCallingOpensAWindowAndCostsNothingYet() {
        val e = engineOnTop(2, LastColor.BLUE, 7)
        setHand(e, 0, num(LastColor.BLUE, 1), num(LastColor.RED, 2))
        assertTrue(e.play(0, 0, null))
        assertEquals(1, e.hands[0].size)
        assertEquals(setOf(0), e.catchable)
    }

    @Test fun callingAheadWithTwoCardsMeansNoWindow() {
        val e = engineOnTop(2, LastColor.BLUE, 7)
        setHand(e, 0, num(LastColor.BLUE, 1), num(LastColor.RED, 2))
        assertTrue(e.call(0))
        assertTrue(e.armed(0))
        assertFalse(e.call(0))                 // nothing more to arm
        assertTrue(e.play(0, 0, null))
        assertTrue(e.catchable.isEmpty())
        assertFalse(e.armed(0))
    }

    @Test fun callingJustAfterClosesTheWindow() {
        val e = engineOnTop(3, LastColor.BLUE, 7)
        setHand(e, 0, num(LastColor.BLUE, 1), num(LastColor.RED, 2))
        assertTrue(e.play(0, 0, null))
        assertTrue(e.call(0))
        assertTrue(e.catchable.isEmpty())
        assertEquals(LastEvent.Type.CALLED, e.lastEvent?.type)
        assertEquals(1, e.hands[0].size)
    }

    @Test fun callingWithThreeCardsDoesNothing() {
        val e = engineOnTop(2, LastColor.BLUE, 7)
        setHand(e, 0, num(LastColor.BLUE, 1), num(LastColor.RED, 2), num(LastColor.RED, 3))
        assertFalse(e.call(0))
        assertFalse(e.armed(0))
    }

    @Test fun beingCaughtDrawsTwoAndGettingAwayDrawsNothing() {
        val caught = engineOnTop(3, LastColor.BLUE, 7)
        setHand(caught, 0, num(LastColor.BLUE, 1), num(LastColor.RED, 2))
        assertTrue(caught.play(0, 0, null))
        assertFalse(caught.catchResult(0, 0))          // cannot catch yourself
        assertTrue(caught.catchResult(0, 2))
        assertEquals(3, caught.hands[0].size)
        assertTrue(caught.catchable.isEmpty())
        assertEquals(LastEvent.Type.CAUGHT, caught.lastEvent?.type)
        assertEquals(2, caught.lastEvent?.seat)        // the catcher
        assertEquals(0, caught.lastEvent?.target)
        assertFalse(caught.catchResult(0, 2))          // already settled

        val away = engineOnTop(3, LastColor.BLUE, 7)
        setHand(away, 0, num(LastColor.BLUE, 1), num(LastColor.RED, 2))
        assertTrue(away.play(0, 0, null))
        assertTrue(away.catchResult(0, -1))
        assertEquals(1, away.hands[0].size)
        assertTrue(away.catchable.isEmpty())
    }

    @Test fun aCatchOnSomeoneWhoNoLongerHasOneCardIsIgnored() {
        val e = engineOnTop(2, LastColor.BLUE, 7)
        setHand(e, 0, num(LastColor.BLUE, 1), num(LastColor.RED, 2))
        setHand(e, 1, num(LastColor.GREEN, 4), num(LastColor.GREEN, 5), num(LastColor.GREEN, 6))
        assertTrue(e.play(0, 0, null))
        assertTrue(e.draw(1))                          // seat 1 is not the one at risk
        assertFalse(e.catchResult(1, 0))
        assertEquals(setOf(0), e.catchable)
    }

    @Test fun goingOutWithTheWindowOpenWins() {
        val e = engineOnTop(2, LastColor.BLUE, 7)
        setHand(e, 0, num(LastColor.BLUE, 1), num(LastColor.BLUE, 2))
        setHand(e, 1, num(LastColor.GREEN, 4), num(LastColor.GREEN, 5))
        assertTrue(e.play(0, 0, null))
        assertTrue(e.draw(1))                          // seat 1 draws, passes the turn back
        if (e.turn == 0) { assertTrue(e.play(0, 0, null)); assertEquals(0, e.winner); assertTrue(e.catchable.isEmpty()) }
    }

    @Test fun emptyingTheHandWinsAndEndsTheGame() {
        val e = engineOnTop(2, LastColor.BLUE, 7)
        setHand(e, 0, num(LastColor.BLUE, 1))
        assertTrue(e.play(0, 0, null))   // no penalty for going out
        assertEquals(0, e.winner)
        assertEquals(0, e.hands[0].size)
        assertFalse(e.draw(1))
        assertFalse(e.play(1, 0, null))
    }

    @Test fun drawingAnUnplayableCardPassesTheTurn() {
        val e = engineOnTop(2, LastColor.BLUE, 7)
        // keep drawing for seat 0 until the drawn card is unplayable (turn moves on) or playable (turn stays)
        assertTrue(e.draw(0))
        assertEquals(8, e.hands[0].size)
        if (e.drewPlayable) assertEquals(0, e.turn) else assertEquals(1, e.turn)
    }

    @Test fun afterDrawingAPlayableCardOnlyThatCardCanBePlayedOrIPass() {
        // find a seed where seat 0's first draw is playable
        var e: LastEngine? = null
        for (seed in 1L..5000L) {
            val t = LastEngine(2, seed)
            if (t.draw(0) && t.drewPlayable) { e = t; break }
        }
        val eng = e!!
        assertEquals(0, eng.turn)
        assertFalse(eng.draw(0))                        // cannot draw twice
        val last = eng.hands[0].lastIndex
        val other = (0 until last).firstOrNull { eng.canPlay(eng.hands[0][it]) }
        if (other != null) assertFalse(eng.play(0, other, LastColor.RED))   // only the drawn card
        assertTrue(eng.pass(0))
        assertEquals(1, eng.turn)
        assertFalse(eng.pass(1))                        // nothing to pass
    }

    @Test fun twoCopiesFedTheSameMovesNeverDrift() {
        for (seed in 1L..150L) for (n in 2..6) {
            val a = LastEngine(n, seed); val b = LastEngine(n, seed)
            var steps = 0
            while (a.winner < 0 && steps++ < 3000) {
                settleAttack(a); settleAttack(b)
                val s = a.turn
                val hand = a.hands[s]
                val idx = if (a.drewPlayable) hand.lastIndex else hand.indexOfFirst { a.canPlay(it) }
                val ok: Boolean
                if (idx >= 0) {
                    val c = hand[idx]
                    val col = if (c.kind.isWild) LastColor.entries[steps % 4] else null
                                        ok = a.play(s, idx, col) && b.play(s, idx, col)
                } else if (a.drewPlayable) ok = a.pass(s) && b.pass(s)
                else ok = a.draw(s) && b.draw(s)
                assertTrue("seed $seed players $n", ok)
                assertEquals(a.hands, b.hands)
                assertEquals(a.turn, b.turn)
                assertEquals(a.currentColor, b.currentColor)
                assertEquals(a.direction, b.direction)
            }
            assertTrue("game should finish: seed $seed players $n", a.winner >= 0)
        }
    }

    // ---- rules --------------------------------------------------------------------------------

    @Test fun startingCardsComeFromTheRules() {
        for (n in listOf(3, 5, 10, 15)) {
            val e = LastEngine(4, 7L, LastRules(handSize = n))
            assertTrue(e.hands.all { it.size == n })
            assertEquals(108 - n * 4 - 1, e.drawCount)
        }
    }

    @Test fun drawTwoTakesTwoThenYouMayPlayADrawnCardOrKeepThem() {
        var sawFit = false; var sawNoFit = false
        for (seed in 1L..300L) {
            val e = LastEngine(3, seed, LastRules(draw = DrawRule.TWO))
            val before = e.hands[0].size
            assertTrue(e.draw(0))
            assertEquals(before + 2, e.hands[0].size)
            assertEquals(2, e.lastEvent?.amount)
            val fits = (before until before + 2).filter { e.canPlay(e.hands[0][it]) }
            if (fits.isEmpty()) { sawNoFit = true; assertEquals(1, e.turn); assertFalse(e.drewPlayable); continue }
            sawFit = true
            assertEquals(0, e.turn)
            assertTrue(e.drewPlayable)
            assertFalse(e.draw(0))                                   // no third draw
            // only the drawn cards that fit may be played; the old hand may not
            for (i in 0 until before) assertFalse(e.playableAt(i, e.hands[0][i]))
            assertTrue(e.pass(0))                                    // keep both
            assertEquals(before + 2, e.hands[0].size)
            assertEquals(1, e.turn)
        }
        assertTrue(sawFit && sawNoFit)
    }

    @Test fun drawTwoLetsYouPlayEitherDrawnCard() {
        for (seed in 1L..500L) {
            val e = LastEngine(2, seed, LastRules(draw = DrawRule.TWO))
            val before = e.hands[0].size
            e.draw(0)
            val fits = (before until before + 2).filter { e.canPlay(e.hands[0][it]) && !e.hands[0][it].kind.isWild }
            if (fits.size < 2) continue
            val first = e.hands[0][before]
            assertTrue(e.play(0, before, null))                      // the FIRST drawn card, not just the last
            assertEquals(first, e.topCard)
            return
        }
        error("no seed where both drawn cards fit")
    }

    @Test fun drawOneKeepsAPlayableCardOrPlaysIt() {
        for (seed in 1L..300L) {
            val e = LastEngine(2, seed)
            val before = e.hands[0].size
            e.draw(0)
            if (!e.drewPlayable) { assertEquals(1, e.turn); continue }
            assertEquals(0, e.turn)
            assertTrue(e.pass(0))
            assertEquals(before + 1, e.hands[0].size)
            return
        }
    }

    @Test fun drawUntilMatchKeepsDrawingUntilTheCardFits() {
        for (seed in 1L..200L) {
            val e = LastEngine(2, seed, LastRules(draw = DrawRule.UNTIL_MATCH))
            val before = e.hands[0].size
            assertTrue(e.draw(0))
            val got = e.hands[0].size - before
            assertTrue(got >= 1)
            assertTrue(e.drewPlayable)
            assertEquals(got, e.lastEvent?.amount)
            assertTrue(e.canPlay(e.hands[0][e.hands[0].lastIndex]))
            // every card drawn before the last one did not fit
            for (i in before until e.hands[0].lastIndex) assertFalse(e.canPlay(e.hands[0][i]))
            assertEquals(0, e.turn)
        }
    }

    @Test fun aSixStartsASlapOnlyWhenTheRuleIsOn() {
        val on = LastEngine(3, 3L, LastRules(slapSix = true))
        val top = on.topCard
        // build a hand with a six of the colour in play
        on.hands[0].clear(); on.hands[0].addAll(listOf(num(on.currentColor, 6), num(LastColor.RED, 1), num(LastColor.RED, 2)))
        assertTrue(on.play(0, 0, null))
        assertTrue(on.slapOpen)
        assertEquals(1, on.slapRound)

        val off = LastEngine(3, 3L, LastRules(slapSix = false))
        off.hands[0].clear(); off.hands[0].addAll(listOf(num(off.currentColor, 6), num(LastColor.RED, 1), num(LastColor.RED, 2)))
        assertTrue(off.play(0, 0, null))
        assertFalse(off.slapOpen)
        assertEquals(top.kind, LastKind.NUMBER)
    }

    @Test fun theSlapLoserDrawsTwoAndTheRoundCloses() {
        val e = LastEngine(3, 3L, LastRules(slapSix = true))
        e.hands[0].clear(); e.hands[0].addAll(listOf(num(e.currentColor, 6), num(LastColor.RED, 1), num(LastColor.RED, 2)))
        assertTrue(e.play(0, 0, null))
        assertFalse(e.slapResult(5, 1 shl 2))              // wrong round
        val before = e.hands[2].size
        assertTrue(e.slapResult(1, 1 shl 2))
        assertEquals(before + 2, e.hands[2].size)
        assertFalse(e.slapOpen)
        assertEquals(LastEvent.Type.SLAPPED, e.lastEvent?.type)
        assertEquals(2, e.lastEvent?.seat)
        assertFalse(e.slapResult(1, 1 shl 2))              // already settled
    }

    @Test fun anOpenSlapStopsTheTurnUntilItIsSettled() {
        // a 6 pauses play: the next player cannot draw or play until the slap round is ruled on
        for (seed in 1L..500L) {
            val e = LastEngine(2, seed, LastRules(slapSix = true))
            e.hands[1].clear(); e.hands[1].addAll(listOf(num(e.currentColor, 6), num(LastColor.RED, 9), num(LastColor.RED, 8)))
            // seat 0 plays a plain number first to hand the turn on
            val i = e.hands[0].indexOfFirst {
                e.canPlay(it) && !it.kind.isWild && it.kind == LastKind.NUMBER && it.number != LastEngine.SLAP_NUMBER
            }
            if (i < 0) continue
            assertTrue(e.play(0, i, null))
            if (e.turn != 1) continue
            // seat 1 plays the six so that a slap round opens
            val six = e.hands[1].indexOfFirst { it.kind == LastKind.NUMBER && it.number == 6 && e.canPlay(it) }
            if (six < 0) continue
            assertTrue(e.play(1, six, null))
            assertTrue(e.slapOpen)
            if (e.turn != 0) continue
            assertFalse(e.draw(0))
            assertFalse(e.hands[0].indices.any { e.playableAt(it, e.hands[0][it]) })
            assertTrue(e.slapResult(e.slapRound, 1 shl 0))
            assertFalse(e.slapOpen)
            assertTrue(e.draw(0))                     // the turn resumes once the slap is settled
            return
        }
        error("no seed found")
    }

    @Test fun movesAppliedInTheSameOrderGiveIdenticalGames() {
        // the host's order is the truth: two copies fed the same moves (including catches and slaps) agree
        for (seed in 1L..60L) for (n in 2..5) {
            val rules = LastRules(handSize = 5, slapSix = true, draw = DrawRule.entries[(seed % 3).toInt()])
            val a = LastEngine(n, seed, rules); val b = LastEngine(n, seed, rules)
            var steps = 0
            fun both(m: LastMove): Boolean { val ok = a.apply(m); if (ok) assertTrue(b.apply(m)) else assertFalse(b.apply(m)); return ok }
            while (a.winner < 0 && steps++ < 4000) {
                if (a.defenseOpen) { val m = LastMove.DefenseTimeout(a.defenseRound); a.apply(m); b.apply(m) }
                val s = a.turn
                val hand = a.hands[s]
                // sprinkle in the timed moves
                if (steps % 7 == 0 && a.slapOpen) both(LastMove.Slap(a.slapRound, 1 shl (steps % n)))
                if (steps % 5 == 0) for (t in a.catchable.toList()) both(LastMove.Catch(t, (t + 1) % n))
                if (steps % 9 == 0 && hand.size == 2) both(LastMove.Call(s))
                val idx = hand.indices.firstOrNull { a.playableAt(it, hand[it]) } ?: -1
                if (idx >= 0) {
                    val c = hand[idx]
                    both(LastMove.Play(s, idx, if (c.kind.isWild) LastColor.entries[steps % 4] else null))
                } else if (a.drewPlayable) both(LastMove.Pass(s))
                else both(LastMove.Draw(s))
                assertEquals(a.hands, b.hands)
                assertEquals(a.turn, b.turn)
                assertEquals(a.moveNo, b.moveNo)
            }
            assertTrue("game should finish: seed $seed players $n", a.winner >= 0)
        }
    }

    @Test fun forcePlayMeansADrawnCardThatFitsMustBePlayed() {
        for (draw in DrawRule.entries) {
            var checked = false
            for (seed in 1L..300L) {
                val e = LastEngine(2, seed, LastRules(draw = draw, forcePlay = true))
                assertTrue(e.draw(0))                      // drawing is always allowed
                if (!e.drewPlayable) continue
                assertFalse(e.pass(0))                     // cannot keep it
                assertEquals(0, e.turn)
                val i = (e.drewFrom until e.drewTo).first { e.canPlay(e.hands[0][it]) }
                assertTrue(e.play(0, i, LastColor.RED))
                checked = true
                break
            }
            assertTrue("draw rule $draw", checked)
        }
    }

    @Test fun forcePlayStillLetsYouDrawWithAPlayableCardInHand() {
        val e = LastEngine(2, 5L, LastRules(forcePlay = true))
        e.hands[0].clear(); e.hands[0].addAll(listOf(num(e.currentColor, 1), num(e.currentColor, 2)))
        assertTrue(e.draw(0))
    }

    @Test fun passingStaysAllowedWithoutForcePlay() {
        for (seed in 1L..300L) {
            val e = LastEngine(2, seed)
            e.draw(0)
            if (e.drewPlayable) { assertTrue(e.pass(0)); return }
        }
    }

    // ---- play pile (visual stack) and draw pile ------------------------------------------------

    @Test fun playPileStartsWithTheStartingCard() {
        val e = LastEngine(2, 7L)
        assertEquals(listOf(e.topCard), e.playPileSnapshot)
    }

    @Test fun playPileEndsWithTheTopCardAndIsCappedAtTen() {
        val e = LastEngine(2, 11L)
        var guard = 0
        while (e.winner < 0 && guard++ < 400) {
            val me = e.turn
            val i = e.hands[me].indices.firstOrNull { e.playableAt(it, e.hands[me][it]) }
            if (i != null) e.play(me, i, LastColor.RED) else e.draw(me)
            assertTrue(e.playPileSnapshot.size in 1..10)
            assertEquals(e.topCard, e.playPileSnapshot.last())
        }
    }

    @Test fun reshuffleKeepsTheTopCardInThePlayPile() {
        // Play whenever possible and draw otherwise, over many seeds, so the draw pile runs dry
        // at least sometimes; the stack must always end in the real top card.
        for (seed in 1L..40L) {
            val e = LastEngine(2, seed, LastRules(handSize = 3, draw = DrawRule.UNTIL_MATCH))
            var guard = 0
            while (e.winner < 0 && guard++ < 500) {
                val me = e.turn
                val i = e.hands[me].indices.firstOrNull { e.playableAt(it, e.hands[me][it]) }
                if (i != null) e.play(me, i, LastColor.GREEN) else e.draw(me)
                if (e.drewPlayable) e.pass(me)
                assertEquals(e.topCard, e.playPileSnapshot.last())
            }
        }
    }

    @Test fun drawPileCountMatchesTheDeal() {
        for (n in 2..6) {
            val e = LastEngine(n, 5L)
            assertEquals(108 - n * 7 - 1, e.drawCount)
        }
    }

    @Test fun playPileIdsAreStableAndMatchTheCards() {
        val e = LastEngine(2, 11L)
        var guard = 0
        var prev = e.playPileIds
        while (e.winner < 0 && guard++ < 200) {
            val me = e.turn
            val i = e.hands[me].indices.firstOrNull { e.playableAt(it, e.hands[me][it]) }
            if (i != null) e.play(me, i, LastColor.RED) else e.draw(me)
            if (e.drewPlayable) e.pass(me)
            val ids = e.playPileIds
            assertEquals(e.playPileSnapshot.size, ids.size)
            assertEquals(ids.sorted(), ids)
            // a card that stays in the pile keeps its number
            assertTrue(ids.filter { it in prev }.all { it in prev })
            prev = ids
        }
    }

    // ---- doubling ----------------------------------------------------------------------------

    private fun engineWith(rules: LastRules, color: LastColor, n: Int): LastEngine {
        for (seed in 1L..5000L) {
            val e = LastEngine(2, seed, rules)
            if (e.topCard == num(color, n)) return e
        }
        error("no seed found")
    }

    @Test fun withoutDoublingANumberCardEndsTheTurn() {
        val e = engineWith(LastRules(), LastColor.RED, 5)
        setHand(e, 0, num(LastColor.RED, 3), num(LastColor.BLUE, 3), num(LastColor.GREEN, 9))
        assertTrue(e.play(0, 0, null))
        assertFalse(e.doubleOpen)
        assertEquals(1, e.turn)
    }

    @Test fun doublingKeepsTheTurnForAnotherCardOfTheSameNumber() {
        val e = engineWith(LastRules(doubling = true), LastColor.RED, 5)
        setHand(e, 0, num(LastColor.RED, 3), num(LastColor.BLUE, 3), num(LastColor.GREEN, 3), num(LastColor.RED, 7))
        assertTrue(e.play(0, 0, null))
        assertEquals(0, e.turn)
        assertTrue(e.doubleOpen)
        assertEquals(3, e.doubleNumber)
        // hand is now [B3, G3, R7]: only the other 3s are playable, whatever their colour; R7 matches
        // the colour in play but is not a 3
        assertTrue(e.playableAt(0, e.hands[0][0]))
        assertTrue(e.playableAt(1, e.hands[0][1]))
        assertFalse(e.playableAt(2, e.hands[0][2]))
        assertFalse(e.play(0, 2, null))
        assertFalse("no drawing in the middle of a double", e.draw(0))
        assertTrue(e.play(0, 0, null))
        assertEquals(LastColor.BLUE, e.currentColor)
        assertEquals(0, e.turn)
        assertTrue(e.play(0, 0, null))                 // the last 3
        assertFalse(e.doubleOpen)
        assertEquals("no 3 left: the turn ends at once", 1, e.turn)
    }

    @Test fun theRefereeEndsTheDoubleWhenTheWindowRunsOut() {
        val e = engineWith(LastRules(doubling = true), LastColor.RED, 5)
        setHand(e, 0, num(LastColor.RED, 3), num(LastColor.BLUE, 3), num(LastColor.GREEN, 9))
        assertTrue(e.play(0, 0, null))
        assertFalse("only the mover's turn can be ended", e.endDouble(1))
        assertTrue(e.endDouble(0))
        assertEquals(1, e.turn)
        assertEquals(LastEvent.Type.DOUBLED, e.lastEvent?.type)
        assertEquals(1, e.lastEvent?.amount)
        assertFalse("nothing left to end", e.endDouble(0))
    }

    @Test fun sixesNeverDoubleWhenSlapIsOn() {
        val e = engineWith(LastRules(slapSix = true, doubling = true), LastColor.RED, 5)
        setHand(e, 0, num(LastColor.RED, 6), num(LastColor.BLUE, 6), num(LastColor.GREEN, 9))
        assertTrue(e.play(0, 0, null))
        assertTrue(e.slapOpen)
        assertEquals(1, e.slapRound)
        assertFalse("a 6 never starts a double with slap on 6 on", e.doubleOpen)
        assertEquals("the turn moves on", 1, e.turn)
        assertFalse(e.play(0, 1, null))       // seat 0 cannot follow up on their own 6
    }

    @Test fun zeroRotatesEveryHandInTheDirectionOfPlay() {
        val e = engineWith(LastRules(zeroSeven = true), LastColor.RED, 5)
        setHand(e, 0, num(LastColor.RED, 0), num(LastColor.RED, 4))
        setHand(e, 1, num(LastColor.GREEN, 1), num(LastColor.BLUE, 2))
        assertTrue(e.play(0, 0, null))
        assertEquals("seat 1's hand wrapped back to me", listOf(num(LastColor.GREEN, 1), num(LastColor.BLUE, 2)), e.hands[0])
        assertEquals("my leftovers moved on to seat 1", listOf(num(LastColor.RED, 4)), e.hands[1])
        assertEquals("the turn is not held", 1, e.turn)
    }

    @Test fun sevenSwapsHandsWithTheChosenPlayer() {
        val e = engineWith(LastRules(zeroSeven = true), LastColor.RED, 5)
        setHand(e, 0, num(LastColor.RED, 7), num(LastColor.RED, 2))
        setHand(e, 1, num(LastColor.GREEN, 3), num(LastColor.GREEN, 4), num(LastColor.GREEN, 5))
        assertTrue(e.exchange(0, 0, 1))
        assertEquals(listOf(num(LastColor.GREEN, 3), num(LastColor.GREEN, 4), num(LastColor.GREEN, 5)), e.hands[0])
        assertEquals(listOf(num(LastColor.RED, 2)), e.hands[1])
        assertEquals(LastEvent.Type.EXCHANGED, e.lastEvent?.type)
        assertEquals(1, e.lastEvent?.target)
        assertEquals("the 7's turn behaviour is unchanged", 1, e.turn)
        assertFalse("the 7 is gone", e.exchange(0, 0, 2))
    }

    @Test fun exchangeNeedsTheRuleAndARealTarget() {
        val off = engineWith(LastRules(), LastColor.RED, 5)
        setHand(off, 0, num(LastColor.RED, 7))
        assertFalse("rule off: no exchange", off.exchange(0, 0, 1))
        val on = engineWith(LastRules(zeroSeven = true), LastColor.RED, 5)
        setHand(on, 0, num(LastColor.RED, 7))
        assertFalse("cannot swap with yourself", on.exchange(0, 0, 0))
        assertFalse("no such seat", on.exchange(0, 0, 5))
        assertFalse("no card there", on.exchange(0, 1, 1))
    }

    @Test fun aDoubleCanWinTheGame() {
        val e = engineWith(LastRules(doubling = true), LastColor.RED, 5)
        setHand(e, 0, num(LastColor.RED, 3), num(LastColor.BLUE, 3))
        assertTrue(e.play(0, 0, null))
        assertTrue(e.doubleOpen)
        assertTrue(e.play(0, 0, null))
        assertEquals(0, e.winner)
    }

    @Test fun defenseBlockAndReverseAnAttack() {
        val a = engineWith(LastRules(defense = true), LastColor.RED, 5)
        setHand(a, 0, card(LastKind.PLUS2, LastColor.RED), num(LastColor.BLUE, 1))
        setHand(a, 1, card(LastKind.BAN, LastColor.RED), num(LastColor.BLUE, 2))
        assertTrue(a.play(0, 0, null))
        assertTrue(a.defenseOpen)
        assertEquals(1, a.defenseTarget)
        assertEquals(2, a.defenseAmount)
        assertEquals(listOf(0), a.defenseCards(1))
        assertTrue(a.defend(1, 0))
        assertFalse(a.defenseOpen)
        assertEquals(1, a.hands[1].size)              // the Ban was spent, no punishment drawn
        assertEquals(LastEvent.Type.DEFENDED, a.lastEvent?.type)

        val b = engineWith(LastRules(defense = true), LastColor.RED, 5)
        setHand(b, 0, card(LastKind.PLUS2, LastColor.RED), num(LastColor.BLUE, 1))
        setHand(b, 1, card(LastKind.SPIN, LastColor.RED), num(LastColor.BLUE, 2))
        assertTrue(b.play(0, 0, null))
        assertTrue(b.defend(1, 0))
        assertEquals("the +2 goes back to the attacker", 3, b.hands[0].size)
        assertEquals("the spinner takes the turn", 0, b.turn)
    }

    @Test fun defenseTimeoutMakesTheTargetDraw() {
        val e = engineWith(LastRules(defense = true), LastColor.RED, 5)
        setHand(e, 0, card(LastKind.COLOR_PLUS2_SQ, null), num(LastColor.BLUE, 1))
        setHand(e, 1, card(LastKind.BAN, LastColor.RED), num(LastColor.GREEN, 3))
        assertTrue(e.play(0, 0, LastColor.RED))
        assertTrue(e.defenseOpen)
        assertEquals(4, e.defenseAmount)
        assertTrue(e.defenseTimeout(e.defenseRound))
        assertFalse(e.defenseOpen)
        assertEquals(6, e.hands[1].size)              // 2 original + 4 punishment
    }

    @Test fun fourOnFourStacksAndPassesThePunishment() {
        // 3 players so the next target is a different seat. A(0) attacks B(1); B stacks +4 on A.
        val e = engineWith(LastRules(), LastColor.RED, 5)
        // engineWith is 2 players; build a 3-player engine with the same top card by trying seeds.
        var three = LastEngine(3, 1L)
        for (seed in 1L..5000L) {
            val candidate = LastEngine(3, seed)
            if (candidate.topCard == num(LastColor.RED, 5)) { three = candidate; break }
        }
        setHand(three, 0, card(LastKind.COLOR_PLUS2_SQ, null), num(LastColor.BLUE, 1))
        setHand(three, 1, card(LastKind.COLOR_PLUS2_SQ, null), num(LastColor.GREEN, 2))
        setHand(three, 2, num(LastColor.YELLOW, 3), num(LastColor.BLUE, 4))
        assertTrue(three.play(0, 0, LastColor.RED))
        assertTrue(three.defenseOpen)
        assertEquals(1, three.defenseTarget)
        assertEquals(4, three.defenseAmount)
        assertEquals(listOf(0), three.defenseCards(1))   // the only legal answer is the +4

        assertTrue(three.defend(1, 0))
        assertEquals("seat 2 takes the stacked punishment", 10, three.hands[2].size)
        assertFalse("B did not draw", three.hands[1].any { it.kind == LastKind.COLOR_PLUS2_SQ })
    }

    @Test fun plusTwoStacksOnFourWhenTheColourMatches() {
        val e = engineWith(LastRules(stackLesser = true), LastColor.RED, 5)
        setHand(e, 0, card(LastKind.COLOR_PLUS2_SQ, null), num(LastColor.BLUE, 1))
        setHand(e, 1, card(LastKind.PLUS2, LastColor.RED), num(LastColor.GREEN, 2))
        assertTrue(e.play(0, 0, LastColor.RED))
        assertTrue(e.defenseOpen)
        assertEquals(listOf(0), e.defenseCards(1))
        assertTrue(e.defend(1, 0))
        assertEquals("the attacker takes the stacked punishment", 7, e.hands[0].size)
    }

    @Test fun ifYouDoNotStackYouTakeTheAttack() {
        val e = engineWith(LastRules(), LastColor.RED, 5)
        setHand(e, 0, card(LastKind.PLUS2, LastColor.RED), num(LastColor.BLUE, 1))
        setHand(e, 1, num(LastColor.GREEN, 2), num(LastColor.BLUE, 3))
        assertTrue(e.play(0, 0, null))
        assertFalse("no response available: the punishment lands at once", e.defenseOpen)
        assertEquals(4, e.hands[1].size)
    }

    @Test fun gamesWithDoublingAlwaysFinishAndCopiesAgree() {
        for (seed in 1L..60L) for (n in 2..5) {
            val rules = LastRules(handSize = 5, doubling = true, draw = DrawRule.entries[(seed % 3).toInt()])
            val a = LastEngine(n, seed, rules); val b = LastEngine(n, seed, rules)
            var steps = 0
            fun both(m: LastMove) { val ok = a.apply(m); if (ok) assertTrue(b.apply(m)) else assertFalse(b.apply(m)) }
            while (a.winner < 0 && steps++ < 4000) {
                if (a.defenseOpen) { val m = LastMove.DefenseTimeout(a.defenseRound); a.apply(m); b.apply(m) }
                val s = a.turn
                val hand = a.hands[s]
                val idx = hand.indices.firstOrNull { a.playableAt(it, hand[it]) } ?: -1
                when {
                    a.doubleOpen && steps % 3 == 0 -> both(LastMove.EndDouble(s))   // the 2 seconds ran out
                    idx >= 0 -> {
                        val c = hand[idx]
                        both(LastMove.Play(s, idx, if (c.kind.isWild) LastColor.entries[steps % 4] else null))
                    }
                    a.doubleOpen -> both(LastMove.EndDouble(s))
                    a.drewPlayable -> both(LastMove.Pass(s))
                    else -> both(LastMove.Draw(s))
                }
                assertEquals(a.hands, b.hands)
                assertEquals(a.turn, b.turn)
            }
            assertTrue("game should finish: seed $seed players $n", a.winner >= 0)
        }
    }
}
