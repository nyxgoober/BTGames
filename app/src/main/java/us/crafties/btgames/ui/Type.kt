package us.crafties.btgames.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import us.crafties.btgames.R

/**
 * Inter (SIL Open Font License 1.1). One variable font file, res/font/inter.ttf, serves every
 * weight: each entry below picks its weight from the font's `wght` axis. That needs Android 8
 * (API 26), which is this app's minSdk.
 *
 * The files are kept in the repo. Inter has no Thai, so Thai and other scripts fall back to the
 * phone's own font, which is what nicknames in those scripts need anyway.
 */
@OptIn(ExperimentalTextApi::class)
private fun appFont(res: Int, weight: FontWeight) = Font(
    res,
    weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

/** Inter, the original. */
val InterFont = FontFamily(
    appFont(R.font.inter, FontWeight.Normal),
    appFont(R.font.inter, FontWeight.SemiBold),
    appFont(R.font.inter, FontWeight.Bold),
    appFont(R.font.inter, FontWeight.Black),
)

/**
 * Intern (github.com/bvvst/intern, SIL Open Font License 1.1): a fully rounded variant of Inter,
 * with the same variable axes (opsz 14-32, wght 100-900), bundled the same way.
 */
val InternFont = FontFamily(
    appFont(R.font.intern, FontWeight.Normal),
    appFont(R.font.intern, FontWeight.SemiBold),
    appFont(R.font.intern, FontWeight.Bold),
    appFont(R.font.intern, FontWeight.Black),
)

/** The typeface the app draws with. Snapshot state: switching it redraws everything. Intern by default. */
var AppFont by mutableStateOf<FontFamily>(InternFont)
    private set

/** The Settings font choice, as stored in prefs. */
object AppType {
    const val FONT_INTERN = "intern"
    const val FONT_INTER = "inter"

    fun apply(tag: String) { AppFont = if (tag == FONT_INTER) InterFont else InternFont }
}
