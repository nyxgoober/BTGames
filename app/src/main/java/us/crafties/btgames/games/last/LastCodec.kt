package us.crafties.btgames.games.last

/**
 * Last! messages ride inside Control.Game (reliable). Every phone deals the same deck from a
 * shared seed, so only moves cross the air. All messages start with a one-byte type and, apart
 * from the clock messages, the match number, so anything left over from a finished match is
 * ignored.
 *
 * The host is the referee: it decides the order of everything. A client never changes its own
 * game by itself; it sends a REQUEST and the host answers with a numbered MOVE that every phone
 * (the sender too) applies in order.
 *
 *   SEED  [1][match][seed:i32 LE][players][hand][flags][draw]   flags: bit0 slap on 6, bit1 force play, bit2 table screen (the host holds no seat: [players] counts only the phones that play), bit3 doubling, bit4 special 0 & 7, bit5 defense, bit6 stack lesser; host -> everyone, repeated until all READY
 *   READY [2][match]
 *   AGAIN [6][match]                      "deal another match": the host reopens its rules screen
 *
 *   Requests (client -> host; ts = host-time ms of the tap, NO_TS if the clock is not synced yet):
 *   PLAY  [3][match][hand][colour]        colour 255 = none
 *   DRAW  [4][match]
 *   PASS  [5][match]
 *   CALL  [9][match][ts:i32]              "Last!"
 *   CATCH [10][match][target][ts:i32]     the "!" on someone's cards
 *   SLAP  [11][match][round][ts:i32]
 *   SWAP  [13][match][hand][target]       the special 7: play the 7 and swap hands with target
 *   DEFEND [14][match][hand]              answer a +2/+4 with a same-colour Ban or Spin
 *
 *   MOVE  [12][match][seq][kind][a][b][c] host -> everyone; seq = engine move counter before it
 *
 *   Clock:
 *   PING  [7][t0:i32]                     client -> host; t0 = the client's own clock
 *   PONG  [8][seat][t0:i32][host:i32]     host -> everyone, only [seat] reads it
 *
 * Who sent a request is not in the payload: the transport stamps it (Control.Game.from).
 */
object LastCodec {
    private const val T_SEED: Byte = 1
    private const val T_READY: Byte = 2
    private const val T_PLAY: Byte = 3
    private const val T_DRAW: Byte = 4
    private const val T_PASS: Byte = 5
    private const val T_AGAIN: Byte = 6
    private const val T_PING: Byte = 7
    private const val T_PONG: Byte = 8
    private const val T_CALL: Byte = 9
    private const val T_CATCH: Byte = 10
    private const val T_SLAP: Byte = 11
    private const val T_MOVE: Byte = 12
    private const val T_DEFEND: Byte = 14
    private const val T_SWAP: Byte = 13

    const val NO_COLOUR = 255
    /** "My clock is not synced yet": the host uses the time the request arrived instead. */
    const val NO_TS = Int.MIN_VALUE
    /** MOVE: a Catch with nobody catching. */
    const val NO_CATCHER = 255

    private const val K_PLAY = 0
    private const val K_DRAW = 1
    private const val K_PASS = 2
    private const val K_CALL = 3
    private const val K_CATCH = 4
    private const val K_SLAP = 5
    private const val K_DOUBLE_END = 6
    private const val K_EXCHANGE = 7
    private const val K_DEFEND = 8
    private const val K_DEFENSE_TIMEOUT = 9

    sealed interface Msg {
        data class Seed(val match: Int, val seed: Int, val players: Int, val rules: LastRules, val screenHost: Boolean = false) : Msg
        data class Ready(val match: Int) : Msg
        data class Again(val match: Int) : Msg
        data class Play(val match: Int, val hand: Int, val colour: LastColor?) : Msg
        data class Draw(val match: Int) : Msg
        data class Pass(val match: Int) : Msg
        data class Call(val match: Int, val ts: Int) : Msg
        data class Catch(val match: Int, val target: Int, val ts: Int) : Msg
        data class Slap(val match: Int, val round: Int, val ts: Int) : Msg
        data class Swap(val match: Int, val hand: Int, val target: Int) : Msg
        data class Defend(val match: Int, val hand: Int) : Msg
        data class Move(val match: Int, val seq: Int, val move: LastMove) : Msg
        data class Ping(val t0: Int) : Msg
        data class Pong(val seat: Int, val t0: Int, val host: Int) : Msg
    }

    private fun i32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    fun encode(m: Msg): ByteArray = when (m) {
        is Msg.Seed -> byteArrayOf(T_SEED, m.match.toByte()) + i32(m.seed) + byteArrayOf(
            m.players.toByte(), m.rules.handSize.toByte(),
            ((if (m.rules.slapSix) 1 else 0) or (if (m.rules.forcePlay) 2 else 0) or (if (m.screenHost) 4 else 0)
                or (if (m.rules.doubling) 8 else 0) or (if (m.rules.zeroSeven) 16 else 0)
                or (if (m.rules.defense) 32 else 0) or (if (m.rules.stackLesser) 64 else 0)).toByte(), m.rules.draw.code.toByte(),
        )
        is Msg.Ready -> byteArrayOf(T_READY, m.match.toByte())
        is Msg.Again -> byteArrayOf(T_AGAIN, m.match.toByte())
        is Msg.Play -> byteArrayOf(T_PLAY, m.match.toByte(), m.hand.toByte(), (m.colour?.code ?: NO_COLOUR).toByte())
        is Msg.Draw -> byteArrayOf(T_DRAW, m.match.toByte())
        is Msg.Pass -> byteArrayOf(T_PASS, m.match.toByte())
        is Msg.Call -> byteArrayOf(T_CALL, m.match.toByte()) + i32(m.ts)
        is Msg.Catch -> byteArrayOf(T_CATCH, m.match.toByte(), m.target.toByte()) + i32(m.ts)
        is Msg.Slap -> byteArrayOf(T_SLAP, m.match.toByte(), m.round.toByte()) + i32(m.ts)
        is Msg.Swap -> byteArrayOf(T_SWAP, m.match.toByte(), m.hand.toByte(), m.target.toByte())
        is Msg.Defend -> byteArrayOf(T_DEFEND, m.match.toByte(), m.hand.toByte())
        is Msg.Move -> {
            val head = byteArrayOf(T_MOVE, m.match.toByte(), m.seq.toByte())
            head + when (val mv = m.move) {
                is LastMove.Play -> byteArrayOf(K_PLAY.toByte(), mv.seat.toByte(), mv.hand.toByte(), (mv.colour?.code ?: NO_COLOUR).toByte())
                is LastMove.Draw -> byteArrayOf(K_DRAW.toByte(), mv.seat.toByte(), 0, 0)
                is LastMove.Pass -> byteArrayOf(K_PASS.toByte(), mv.seat.toByte(), 0, 0)
                is LastMove.EndDouble -> byteArrayOf(K_DOUBLE_END.toByte(), mv.seat.toByte(), 0, 0)
                is LastMove.Call -> byteArrayOf(K_CALL.toByte(), mv.seat.toByte(), 0, 0)
                is LastMove.Catch -> byteArrayOf(K_CATCH.toByte(), mv.target.toByte(), (if (mv.catcher < 0) NO_CATCHER else mv.catcher).toByte(), 0)
                is LastMove.Slap -> byteArrayOf(K_SLAP.toByte(), mv.round.toByte(), mv.losers.toByte(), 0)
                is LastMove.Exchange -> byteArrayOf(K_EXCHANGE.toByte(), mv.seat.toByte(), mv.hand.toByte(), mv.target.toByte())
                is LastMove.Defend -> byteArrayOf(K_DEFEND.toByte(), mv.seat.toByte(), mv.hand.toByte(), 0)
                is LastMove.DefenseTimeout -> byteArrayOf(K_DEFENSE_TIMEOUT.toByte(), mv.round.toByte(), 0, 0)
            }
        }
        is Msg.Ping -> byteArrayOf(T_PING) + i32(m.t0)
        is Msg.Pong -> byteArrayOf(T_PONG, m.seat.toByte()) + i32(m.t0) + i32(m.host)
    }

    fun decode(b: ByteArray): Msg? {
        fun u(i: Int) = b[i].toInt() and 0xFF
        fun s32(i: Int) = u(i) or (u(i + 1) shl 8) or (u(i + 2) shl 16) or (u(i + 3) shl 24)
        fun colour(c: Int): LastColor? = if (c == NO_COLOUR) null else LastColor.fromCode(c)
        if (b.isEmpty()) return null
        return when (b[0]) {
            T_SEED -> if (b.size >= 10) {
                val players = u(6)
                val hand = u(7)
                val draw = DrawRule.fromCode(u(9)) ?: return null
                if (players !in LastEngine.MIN_PLAYERS..LastEngine.MAX_PLAYERS) return null
                if (hand !in LastRules.MIN_HAND..LastRules.MAX_HAND) return null
                Msg.Seed(u(1), s32(2), players, LastRules(hand, u(8) and 1 != 0, draw, u(8) and 2 != 0, doubling = u(8) and 8 != 0, zeroSeven = u(8) and 16 != 0, defense = u(8) and 32 != 0, stackLesser = u(8) and 64 != 0), screenHost = u(8) and 4 != 0)
            } else null
            T_READY -> if (b.size >= 2) Msg.Ready(u(1)) else null
            T_AGAIN -> if (b.size >= 2) Msg.Again(u(1)) else null
            T_PLAY -> if (b.size >= 4) {
                val c = u(3)
                if (c != NO_COLOUR && LastColor.fromCode(c) == null) return null
                Msg.Play(u(1), u(2), colour(c))
            } else null
            T_DRAW -> if (b.size >= 2) Msg.Draw(u(1)) else null
            T_PASS -> if (b.size >= 2) Msg.Pass(u(1)) else null
            T_CALL -> if (b.size >= 6) Msg.Call(u(1), s32(2)) else null
            T_CATCH -> if (b.size >= 7) Msg.Catch(u(1), u(2), s32(3)) else null
            T_SLAP -> if (b.size >= 7) Msg.Slap(u(1), u(2), s32(3)) else null
            T_SWAP -> if (b.size >= 4) Msg.Swap(u(1), u(2), u(3)) else null
            T_DEFEND -> if (b.size >= 3) Msg.Defend(u(1), u(2)) else null
            T_MOVE -> if (b.size >= 7) {
                val move: LastMove = when (u(3)) {
                    K_PLAY -> {
                        val c = u(6)
                        if (c != NO_COLOUR && LastColor.fromCode(c) == null) return null
                        LastMove.Play(u(4), u(5), colour(c))
                    }
                    K_DRAW -> LastMove.Draw(u(4))
                    K_PASS -> LastMove.Pass(u(4))
                    K_DOUBLE_END -> LastMove.EndDouble(u(4))
                    K_CALL -> LastMove.Call(u(4))
                    K_CATCH -> LastMove.Catch(u(4), if (u(5) == NO_CATCHER) -1 else u(5))
                    K_SLAP -> LastMove.Slap(u(4), u(5))
                    K_EXCHANGE -> LastMove.Exchange(u(4), u(5), u(6))
                    K_DEFEND -> LastMove.Defend(u(4), u(5))
                    K_DEFENSE_TIMEOUT -> LastMove.DefenseTimeout(u(4))
                    else -> return null
                }
                Msg.Move(u(1), u(2), move)
            } else null
            T_PING -> if (b.size >= 5) Msg.Ping(s32(1)) else null
            T_PONG -> if (b.size >= 10) Msg.Pong(u(1), s32(2), s32(6)) else null
            else -> null
        }
    }
}
