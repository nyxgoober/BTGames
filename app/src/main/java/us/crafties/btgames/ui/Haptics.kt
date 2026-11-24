package us.crafties.btgames.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * The app's only source of vibration, so the Vibration switch in Settings can turn all of it off
 * from one place ([enabled]).
 *
 * Uses the phone's built-in "tick / click" effects where the phone has them (Android 10+) and a
 * short pulse otherwise. Every call is safe on a phone with no vibrator: it does nothing.
 */
object Haptics {

    @Volatile var enabled: Boolean = true

    private var vibrator: Vibrator? = null

    fun init(context: Context) {
        if (vibrator != null) return
        val app = context.applicationContext
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            app.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    /** The lightest touch: any button press. */
    fun tick() = play(VibrationEffect.EFFECT_TICK, ms = 10, amplitude = 60)

    /** A clear but gentle click: confirming something, such as a drawn game. */
    fun click() = play(VibrationEffect.EFFECT_CLICK, ms = 20, amplitude = 120)

    /**
     * Flipping a switch on or off. On Android 14+ this is the system's own switch feedback
     * (TOGGLE_ON / TOGGLE_OFF, played through [view]), which the phone maker tunes to its motor.
     * Older versions get the fallback: a phone with a linear motor (LRA) gets a thump, played as
     * the motor's THUD primitive (full strength for on, softer for off), and anything else,
     * including a spinning motor (ERM), gets a plain short buzz.
     *
     * Still switched off by the app's own Vibration setting like everything else here. The system
     * feedback also follows the phone's "touch feedback" setting, which is outside the app's hands.
     *
     * Android has no direct "which motor is this" question. Whether the phone supports the THUD
     * primitive (Android 12+) is the closest answer: linear motors do, spinning ones do not.
     */
    fun toggle(on: Boolean, view: View) {
        val v = ready() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            view.performHapticFeedback(if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_THUD)
        ) {
            v.vibrate(
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, if (on) 1f else 0.5f)
                    .compose(),
            )
        } else {
            val amplitude = if (on || !v.hasAmplitudeControl()) VibrationEffect.DEFAULT_AMPLITUDE else 110
            v.vibrate(VibrationEffect.createOneShot(40, amplitude))
        }
    }

    /** A solid thump: a mark landing on the board, a goal. */
    fun thud() = play(VibrationEffect.EFFECT_HEAVY_CLICK, ms = 35, amplitude = 210)

    /**
     * A 6 was played with Slap on 6 on: a long, full-strength double buzz, much heavier than any
     * other cue, so you feel it even with your eyes on the cards and can slap in time.
     */
    fun slap() = pattern(longArrayOf(0, 180, 70, 360), intArrayOf(0, 255, 0, 255))

    /** Two quick pulses then a long one: you won. */
    fun win() = pattern(longArrayOf(0, 40, 70, 40, 70, 110), intArrayOf(0, 170, 0, 170, 0, 255))

    /** One long, low rumble: you lost. */
    fun lose() = pattern(longArrayOf(0, 260), intArrayOf(0, 110))

    private fun play(predefined: Int, ms: Long, amplitude: Int) {
        val v = ready() ?: return
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(predefined)
        } else {
            VibrationEffect.createOneShot(ms, if (v.hasAmplitudeControl()) amplitude else VibrationEffect.DEFAULT_AMPLITUDE)
        }
        v.vibrate(effect)
    }

    private fun pattern(timings: LongArray, amplitudes: IntArray) {
        val v = ready() ?: return
        v.vibrate(
            if (v.hasAmplitudeControl()) VibrationEffect.createWaveform(timings, amplitudes, -1)
            else VibrationEffect.createWaveform(timings, -1),
        )
    }

    private fun ready(): Vibrator? = vibrator?.takeIf { enabled && it.hasVibrator() }
}
