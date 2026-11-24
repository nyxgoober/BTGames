package us.crafties.btgames.games.paddle

enum class Phase { SERVING, PLAYING, GAME_OVER }

/**
 * Everything both screens need to draw a frame. All positions are normalised 0..1 so the
 * two phones can have different resolutions/aspect ratios.
 *
 * Coordinate convention (host's point of view, which is also the wire format):
 *   x = 0 is the HOST's goal line (left side), x = 1 is the GUEST's goal line (right side).
 * The guest UI mirrors x so that each player always sees their own paddle on the left.
 */
data class PaddleState(
    val ballX: Float = 0.5f,
    val ballY: Float = 0.5f,
    val ballVx: Float = 0f,
    val ballVy: Float = 0f,
    val hostPaddleY: Float = 0.5f,
    val guestPaddleY: Float = 0.5f,
    val hostScore: Int = 0,
    val guestScore: Int = 0,
    val phase: Phase = Phase.SERVING,
)
