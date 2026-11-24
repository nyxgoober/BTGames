package us.crafties.btgames.net

/**
 * Which of the two player colours a phone uses this match. It is only a label here (RED / BLUE);
 * the UI maps it to actual paint. Kept free of Android and Compose so it can be unit tested.
 */
enum class Side {
    RED, BLUE;

    fun other(): Side = if (this == RED) BLUE else RED
}

/**
 * Decides colours with no extra messages, and guarantees the two phones never pick the same one.
 *
 * Both phones can compute the same value independently from a token they both know. One player is
 * the "anchor": the anchor takes the colour that the anchor's token maps to, the other player takes
 * the opposite. The tokens are random, so the colour each player lands on is a fair coin flip.
 *
 * The rule for WHO the anchor is lives here, in one place, because getting it wrong on one phone
 * only is what makes both players see the same colour:
 *
 *  1. If this phone knows BOTH tokens, the anchor is whoever holds the LOWER token. Both phones see
 *     the same two tokens, so they always agree on it, however the match came about.
 *  2. If it only knows its own token (the phone that asked, when the other side accepted), the anchor
 *     is the phone that asked. The other side will only be in that situation if it saw the asker's
 *     HELLO, and it applies rule 1 with the same two tokens: see [asker] below.
 *
 * Rule 2 is only consistent with rule 1 when the asker really is the lower token, which is not
 * guaranteed. So the phone that answers ([forAcceptor]) never uses rule 1 either: it also anchors
 * on the asker. Rule 1 is reserved for the mutual case, where the tokens are proven on both sides.
 */
object Sides {

    /** The colour of the anchor phone. [anchorToken] is the anchor's own token. */
    fun anchorSide(anchorToken: Int): Side = if (mix(anchorToken) and 1 == 0) Side.RED else Side.BLUE

    /** Ordinary request, seen from the phone that ASKED. It anchors on its own token. */
    fun forAsker(myToken: Int): Side = anchorSide(myToken)

    /** Ordinary request, seen from the phone that ACCEPTED. It anchors on the asker's token. */
    fun forAcceptor(askerToken: Int): Side = anchorSide(askerToken).other()

    /**
     * Mutual request, seen from either phone. Both hold both tokens, so both anchor on the lower
     * one. The tokens are never equal here (an exact tie is turned into an ordinary request).
     */
    fun forMutual(myToken: Int, theirToken: Int): Side {
        val iAmAnchor = myToken < theirToken
        val anchor = anchorSide(if (iAmAnchor) myToken else theirToken)
        return if (iAmAnchor) anchor else anchor.other()
    }

    /**
     * Scrambles the bits so that tokens which differ only slightly (or in their low bits) still give
     * an even split. This is the MurmurHash3 32-bit finaliser. Using `token and 1` directly would be
     * fine for random tokens but skewed for any sequence like 1, 2, 3.
     */
    private fun mix(x: Int): Int {
        var h = x
        h = h xor (h ushr 16)
        h *= -0x7a143595
        h = h xor (h ushr 13)
        h *= -0x3d4d51cb
        return h xor (h ushr 16)
    }
}
