package us.crafties.btgames.ui

import android.content.Context
import android.content.res.Configuration
import us.crafties.btgames.R
import java.util.Locale

/**
 * The app's own language choice, separate from the phone's locale. Remembered in prefs
 * ([KEY]) as a BCP 47 tag, or empty for "follow the system".
 *
 * Applied in `MainActivity.attachBaseContext` before any resources load, and re-applied by
 * recreating the activity when the choice changes. No extra dependencies: a wrapped context
 * with [Configuration.setLocale] is all Compose's `stringResource` needs.
 */
object AppLang {
    /** Prefs key. The value is a tag from [options], or "" for the system language. */
    const val KEY = "language"

    const val SYSTEM = ""
    const val EN = "en"
    const val TH = "th"
    const val JA = "ja"
    const val KO = "ko"
    const val ZH = "zh-CN"

    /** In picker order. */
    val options = listOf(SYSTEM, EN, TH, JA, KO, ZH)

    /** The name shown in the picker. Every language names itself, whatever the UI language is. */
    fun selfName(tag: String): String = when (tag) {
        EN -> "English"
        TH -> "ไทย"
        JA -> "日本語"
        KO -> "한국어"
        ZH -> "简体中文"
        else -> ""
    }

    /** Picker label: the self name, or the localized "System default" for [SYSTEM]. */
    fun label(context: Context, tag: String): String =
        if (tag == SYSTEM) context.getString(R.string.lang_system) else selfName(tag)

    /** The context the activity (and everything under it) should use for [tag]. */
    fun wrap(base: Context, tag: String): Context {
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
