package us.crafties.btgames.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import us.crafties.btgames.net.Side

/**
 * Every colour in the app. The names describe the ROLE, not the value: [Ink] is "the text colour",
 * so it is charcoal on the light theme and near-white on the dark one.
 *
 * The look: a light grey page, white cards with pale blue outlines and a soft blue-grey shadow,
 * and one strong cyan ([Teal]) for title bars, primary buttons and anything selected.
 *
 * [dark] is Compose snapshot state, so a screen that reads any colour here redraws by itself when
 * it flips. That is why the colours are getters and not `val x = Color(...)`: nothing that draws
 * has to be told about dark mode.
 */
object Pal {
    /** Dark mode. Owned by the settings (see ShellViewModel); everything here just reads it. */
    var dark by mutableStateOf(false)

    private fun pick(light: Long, dark: Long) = Color(if (this.dark) dark else light)

    // ---- the accent ----
    /** Title bars, primary buttons, selected things, focus. */
    val Teal get() = pick(0xFF14B9D6, 0xFF109FBA)
    /** The border of a teal thing, and the pressed state of a teal button. */
    val TealDark get() = pick(0xFF0A9DB8, 0xFF0B8298)
    val TealPressed get() = pick(0xFF0E9FBA, 0xFF0C879C)

    // ---- page and text ----
    val Paper get() = pick(0xFFEEF1F3, 0xFF141A1F)
    val PaperDim get() = pick(0xFFE1E6E9, 0xFF1A2127)
    val Ink get() = pick(0xFF1E252B, 0xFFEAF0F3)
    val InkSoft get() = pick(0xFF39434B, 0xFFCFD8DD)
    val Muted get() = pick(0xFF66737D, 0xFF93A1AB)

    /** Off-white for anything drawn on a dark surface (game tiles, the court, title bars). Same in both modes. */
    val Chalk = Color(0xFFF6F8F9)

    /**
     * The court is the dark part of the app in BOTH modes. It must not follow [Ink], which turns
     * near-white in dark mode.
     */
    val Court = Color(0xFF15181D)

    /** The card-table felt on the table screen, with its darker edge and its wooden rim. Same in both modes, like [Court]. */
    val Felt = Color(0xFF1E6B4A)
    val FeltDark = Color(0xFF14503A)
    val FeltRim = Color(0xFF5B3A22)

    /** The two player colours. Which one is "you" is random per match: see [paint]. */
    val Red = Color(0xFFE8452C)
    val Blue get() = pick(0xFF2B6CD9, 0xFF4B86E8) // a touch lighter in dark mode so it stays readable

    // ---- Last! card colours (red and blue reuse the player paints) ----
    val CardRed get() = Red
    val CardYellow = Color(0xFFF2B01E)
    val CardGreen get() = pick(0xFF2FA36B, 0xFF3BB87D)
    val CardBlue get() = Blue

    // ---- raised-surface palette ----
    val Face get() = pick(0xFFFFFFFF, 0xFF222B32)          // a white card / button
    val FaceLow get() = pick(0xFFF6F8F9, 0xFF1D252B)       // the bottom of the same button (barely-there gradient)
    val FacePressed get() = pick(0xFFDDE4E8, 0xFF161C21)   // darkened while held or lingering
    /** The pale blue outline of cards and buttons. */
    val Line get() = pick(0xFFBBDCE6, 0xFF2F4E5A)
    /** Neutral hairline: dividers between rows. */
    val EdgeShade get() = pick(0xFFD5DDE1, 0xFF303C44)
    val Groove get() = pick(0xFFFFFFFF, 0xFF10161A)        // the inside of a text field
    /** Blue-grey, never black in light mode, so shadows stay soft. */
    val Shadow get() = pick(0xFF1C3B47, 0xFF000000)

    /** The paint for a player's [Side]. */
    fun paint(side: Side): Color = if (side == Side.RED) Red else Blue
}

@Composable
fun BtGamesTheme(content: @Composable () -> Unit) {
    val colors = if (Pal.dark) {
        darkColorScheme(
            primary = Pal.Teal,
            onPrimary = Pal.Chalk,
            secondary = Pal.Red,
            background = Pal.Paper,
            surface = Pal.Paper,
            onBackground = Pal.Ink,
            onSurface = Pal.Ink,
            error = Pal.Red,
        )
    } else {
        lightColorScheme(
            primary = Pal.Teal,
            onPrimary = Pal.Chalk,
            secondary = Pal.Red,
            background = Pal.Paper,
            surface = Pal.Paper,
            onBackground = Pal.Ink,
            onSurface = Pal.Ink,
            error = Pal.Red,
        )
    }
    val font = AppFont
    MaterialTheme(colorScheme = colors, typography = appTypography(font)) {
        ProvideTextStyle(TextStyle(fontFamily = font), content = content)
    }
}

/**
 * Material's own text styles, with the app's font put in. Without this, anything Material draws
 * with its own style (a TextButton's label, for one) ignores the app font and uses the system's.
 * Rebuilt per [remember]d font, so switching the font in Settings restyles everything.
 */
private fun appTypography(font: FontFamily): Typography = Typography().let { t ->
    Typography(
        displayLarge = t.displayLarge.copy(fontFamily = font),
        displayMedium = t.displayMedium.copy(fontFamily = font),
        displaySmall = t.displaySmall.copy(fontFamily = font),
        headlineLarge = t.headlineLarge.copy(fontFamily = font),
        headlineMedium = t.headlineMedium.copy(fontFamily = font),
        headlineSmall = t.headlineSmall.copy(fontFamily = font),
        titleLarge = t.titleLarge.copy(fontFamily = font),
        titleMedium = t.titleMedium.copy(fontFamily = font),
        titleSmall = t.titleSmall.copy(fontFamily = font),
        bodyLarge = t.bodyLarge.copy(fontFamily = font),
        bodyMedium = t.bodyMedium.copy(fontFamily = font),
        bodySmall = t.bodySmall.copy(fontFamily = font),
        labelLarge = t.labelLarge.copy(fontFamily = font),
        labelMedium = t.labelMedium.copy(fontFamily = font),
        labelSmall = t.labelSmall.copy(fontFamily = font),
    )
}
