package us.crafties.btgames.games.paddle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaddleEngineTest {

    private fun engine(win: Int = 7) = PaddleEngine(winScore = win, serveDelaySec = 0.5f, random = { 0.5f })

    private fun PaddleEngine.runUntilPlaying() {
        var guard = 0
        while (state.phase == Phase.SERVING && guard++ < 1000) step(0.05f)
    }

    @Test
    fun firstServeGoesTowardHost() {
        val e = engine(); e.newMatch(); e.runUntilPlaying()
        assertEquals(Phase.PLAYING, e.state.phase)
        assertTrue("ball must head toward host (x=0)", e.state.ballVx < 0f)
    }

    @Test
    fun ballDoesNotMoveDuringServeDelay() {
        val e = engine(); e.newMatch()
        e.step(0.1f)
        assertEquals(Phase.SERVING, e.state.phase)
        assertEquals(0.5f, e.state.ballX, 0f)
    }

    @Test
    fun hostMissingGivesGuestAPointAndServeGoesBackTowardHost() {
        val e = engine(); e.newMatch(); e.runUntilPlaying()
        e.setHostPaddle(0.95f) // host paddle nowhere near the ball
        var guard = 0
        while (e.state.phase == Phase.PLAYING && guard++ < 2000) e.step(0.01f)
        assertEquals(1, e.state.guestScore)
        assertEquals(0, e.state.hostScore)
        e.runUntilPlaying()
        assertTrue("conceder (host) receives the next serve", e.state.ballVx < 0f)
    }

    @Test
    fun guestMissingGivesHostAPointAndServeGoesTowardGuest() {
        val e = engine(); e.newMatch(); e.runUntilPlaying()
        // Host returns the first ball, so keep the host paddle on the ball's line.
        e.setHostPaddle(0.5f)
        e.setGuestPaddle(0.05f) // guest never gets there
        var guard = 0
        while (e.state.phase == Phase.PLAYING && guard++ < 4000) {
            e.setHostPaddle(e.state.ballY) // host tracks the ball perfectly
            e.step(0.01f)
        }
        assertEquals(1, e.state.hostScore)
        e.runUntilPlaying()
        assertTrue("conceder (guest) receives the next serve", e.state.ballVx > 0f)
    }

    @Test
    fun paddleHitReflectsBall() {
        val e = engine(); e.newMatch(); e.runUntilPlaying()
        e.setHostPaddle(0.5f)
        var guard = 0
        // Run until the ball has turned around at the host paddle.
        while (e.state.ballVx < 0f && guard++ < 2000) {
            e.setHostPaddle(e.state.ballY)
            e.step(0.005f)
        }
        assertTrue("ball reflected away from host", e.state.ballVx > 0f)
        assertEquals("nobody scored on a clean return", 0, e.state.guestScore)
    }

    @Test
    fun matchEndsAtWinScoreAndStaysOver() {
        val e = engine(win = 2); e.newMatch()
        var guard = 0
        while (e.state.phase != Phase.GAME_OVER && guard++ < 200_000) {
            e.setHostPaddle(0.95f); e.setGuestPaddle(0.05f) // both paddles useless
            e.step(0.01f)
        }
        assertEquals(Phase.GAME_OVER, e.state.phase)
        val before = e.state
        repeat(50) { e.step(0.1f) }
        assertEquals("state frozen after game over", before, e.state)
    }

    @Test
    fun paddleIsClampedInsideField() {
        val e = engine()
        e.setHostPaddle(-5f); assertTrue(e.state.hostPaddleY >= PaddleEngine.PADDLE_H / 2)
        e.setHostPaddle(5f); assertTrue(e.state.hostPaddleY <= 1f - PaddleEngine.PADDLE_H / 2)
    }

    @Test
    fun ballNeverEscapesVerticalBounds() {
        val e = engine(); e.newMatch(); e.runUntilPlaying()
        var guard = 0
        while (e.state.phase == Phase.PLAYING && guard++ < 5000) {
            e.step(0.008f)
            assertTrue(e.state.ballY in 0f..1f)
        }
    }
}
