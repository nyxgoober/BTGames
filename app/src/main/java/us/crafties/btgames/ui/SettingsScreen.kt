package us.crafties.btgames.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import us.crafties.btgames.R
import us.crafties.btgames.net.UpdateStatus

/** Dark mode, vibration, language, release update checks and the licences screen. All apply immediately and are remembered. */
@Composable
fun SettingsScreen(
    dark: Boolean,
    vibration: Boolean,
    screenMode: Boolean = false,
    onScreenMode: (Boolean) -> Unit = {},
    onDark: (Boolean) -> Unit,
    onVibration: (Boolean) -> Unit,
    language: String,
    onLanguage: (String) -> Unit,
    font: String = AppType.FONT_INTERN,
    onFont: (String) -> Unit = {},
    updateChecks: Boolean,
    onUpdateChecks: (Boolean) -> Unit,
    updateStatus: UpdateStatus,
    onCheckUpdates: () -> Unit,
    onOpenDownload: (String) -> Unit,
    onBack: () -> Unit,
    onCodenameTap: () -> Unit = {},
) {
    var langDialog by remember { mutableStateOf(false) }
    var fontDialog by remember { mutableStateOf(false) }
    // The full licences list is its own screen, so Settings itself stays short and nothing overlaps.
    var showLicenses by remember { mutableStateOf(false) }
    val licenses = listOf(
        License(
            stringResource(R.string.license_app),
            stringResource(R.string.license_app_code),
            stringResource(R.string.license_app_hint),
            stringResource(R.string.license_app_body),
        ),
        License(
            stringResource(R.string.license_font),
            stringResource(R.string.license_font_code),
            stringResource(R.string.license_font_hint),
            stringResource(R.string.license_font_body),
        ),
        License(
            stringResource(R.string.license_libs),
            stringResource(R.string.license_libs_code),
            stringResource(R.string.license_libs_hint),
            stringResource(R.string.license_libs_body),
        ),
    )
    if (showLicenses) {
        BackHandler { showLicenses = false }
        LicensesScreen(licenses = licenses, onBack = { showLicenses = false })
    } else AppScreen(title = stringResource(R.string.settings_title), onBack = onBack) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 64.dp),
            ) {
                SectionTitle(stringResource(R.string.settings_appearance))
                RaisedCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 4.dp)) {
                    SettingRow(
                        title = stringResource(R.string.settings_dark),
                        hint = stringResource(R.string.settings_dark_hint),
                        checked = dark,
                        onChecked = onDark,
                    )
                }
                Spacer(Modifier.height(10.dp))
                FontRow(
                    font = font,
                    onClick = { fontDialog = true },
                )

                Spacer(Modifier.height(28.dp))

                SectionTitle(stringResource(R.string.settings_feedback))
                RaisedCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 4.dp)) {
                    SettingRow(
                        title = stringResource(R.string.settings_vibration),
                        hint = stringResource(R.string.settings_vibration_hint),
                        checked = vibration,
                        onChecked = onVibration,
                    )
                }

                Spacer(Modifier.height(28.dp))

                SectionTitle(stringResource(R.string.settings_screen))
                RaisedCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 4.dp)) {
                    SettingRow(
                        title = stringResource(R.string.settings_screen_mode),
                        hint = stringResource(R.string.settings_screen_mode_hint),
                        checked = screenMode,
                        onChecked = onScreenMode,
                    )
                }

                Spacer(Modifier.height(28.dp))

                SectionTitle(stringResource(R.string.settings_language))
                LanguageRow(
                    language = language,
                    onClick = { langDialog = true },
                )

                Spacer(Modifier.height(28.dp))

                SectionTitle(stringResource(R.string.settings_updates))
                RaisedCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 4.dp)) {
                    SettingRow(
                        title = stringResource(R.string.settings_auto_updates),
                        hint = stringResource(R.string.settings_auto_updates_hint),
                        checked = updateChecks,
                        onChecked = onUpdateChecks,
                    )
                }
                Spacer(Modifier.height(10.dp))
                UpdateStatusCard(
                    status = updateStatus,
                    onCheck = onCheckUpdates,
                    onDownload = onOpenDownload,
                )

                Spacer(Modifier.height(28.dp))

                SectionTitle(stringResource(R.string.settings_licenses))
                LicensesEntryRow(onClick = { showLicenses = true })
            }
            VersionTag(Modifier.align(Alignment.BottomEnd).padding(16.dp), onTap = onCodenameTap)
        }
    }
    if (langDialog) {
        LanguageDialog(
            current = language,
            onPick = { onLanguage(it); langDialog = false },
            onDismiss = { langDialog = false },
        )
    }
    if (fontDialog) {
        FontDialog(
            current = font,
            onPick = { onFont(it); fontDialog = false },
            onDismiss = { fontDialog = false },
        )
    }
}

/** One font choice: its prefs tag and the label shown in the picker. */
private data class FontChoice(val tag: String, val name: String)

@Composable
private fun fontChoices(): List<FontChoice> = listOf(
    FontChoice(AppType.FONT_INTERN, stringResource(R.string.font_intern)),
    FontChoice(AppType.FONT_INTER, stringResource(R.string.font_inter)),
)

@Composable
private fun fontName(font: String): String =
    fontChoices().firstOrNull { it.tag == font }?.name ?: font

/** One entry of the Settings Licenses section: what it is, which licence, and the full notice. */
private data class License(val name: String, val code: String, val hint: String, val body: String)

@Composable
private fun FontRow(font: String, onClick: () -> Unit) {
    RaisedCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 4.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() }
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_font), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Pal.Ink)
                Spacer(Modifier.height(3.dp))
                Text(stringResource(R.string.settings_font_hint), fontSize = 14.sp, color = Pal.Muted, lineHeight = 20.sp)
            }
            Spacer(Modifier.width(16.dp))
            Text(fontName(font), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Pal.Teal)
        }
    }
}

@Composable
private fun FontDialog(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    RaisedDialog(title = stringResource(R.string.settings_font_dialog), onDismissRequest = onDismiss) {
        fontChoices().forEach { choice ->
            RaisedButton(
                text = choice.name,
                onClick = { onPick(choice.tag) },
                tone = if (choice.tag == current) Tone.Primary else Tone.Light,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun LanguageRow(language: String, onClick: () -> Unit) {
    val context = LocalContext.current
    RaisedCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 4.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() }
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_language), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Pal.Ink)
                Spacer(Modifier.height(3.dp))
                Text(stringResource(R.string.settings_language_hint), fontSize = 14.sp, color = Pal.Muted, lineHeight = 20.sp)
            }
            Spacer(Modifier.width(16.dp))
            Text(
                AppLang.label(context, language).ifEmpty { AppLang.selfName(language) },
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Pal.Teal,
            )
        }
    }
}

/** The single Settings row that opens the licences screen. */
@Composable
private fun LicensesEntryRow(onClick: () -> Unit) {
    RaisedCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 4.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() }
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_licenses), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Pal.Ink)
                Spacer(Modifier.height(3.dp))
                Text(stringResource(R.string.settings_licenses_hint), fontSize = 14.sp, color = Pal.Muted, lineHeight = 20.sp)
            }
            Spacer(Modifier.width(16.dp))
            Text("›", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Pal.Teal)
        }
    }
}

/** Every licence in one scrolling list: what it covers, which licence, and the full notice. */
@Composable
private fun LicensesScreen(licenses: List<License>, onBack: () -> Unit) {
    AppScreen(title = stringResource(R.string.settings_licenses), onBack = onBack) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 32.dp),
        ) {
            licenses.forEach { lic ->
                RaisedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(lic.name, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Pal.Ink, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(12.dp))
                            Text(lic.code, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Pal.Teal)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(lic.hint, fontSize = 14.sp, color = Pal.Muted, lineHeight = 20.sp)
                        Spacer(Modifier.height(10.dp))
                        Text(lic.body, fontSize = 15.sp, color = Pal.Ink, lineHeight = 22.sp)
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
        }
    }
}

@Composable
private fun LanguageDialog(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    RaisedDialog(title = stringResource(R.string.settings_lang_dialog), onDismissRequest = onDismiss) {
        AppLang.options.forEach { tag ->
            val label = AppLang.label(context, tag).ifEmpty { AppLang.selfName(tag) }
            RaisedButton(
                text = label,
                onClick = { onPick(tag) },
                tone = if (tag == current) Tone.Primary else Tone.Light,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun UpdateStatusCard(
    status: UpdateStatus,
    onCheck: () -> Unit,
    onDownload: (String) -> Unit,
) {
    RaisedCard(Modifier.fillMaxWidth()) {
        Column {
            val message = when (status) {
                UpdateStatus.Unknown -> stringResource(R.string.upd_unknown)
                UpdateStatus.Checking -> stringResource(R.string.upd_checking)
                UpdateStatus.UpToDate -> stringResource(R.string.upd_uptodate)
                is UpdateStatus.Available -> stringResource(R.string.upd_available)
                UpdateStatus.Failed -> stringResource(R.string.upd_failed)
            }
            Text(message, fontSize = 16.sp, color = Pal.Ink, lineHeight = 22.sp)
            Spacer(Modifier.height(12.dp))
            when (status) {
                is UpdateStatus.Available -> RaisedButton(
                    text = stringResource(R.string.upd_download),
                    onClick = { onDownload(status.downloadUrl) },
                    tone = Tone.Primary,
                    modifier = Modifier.fillMaxWidth(),
                )
                UpdateStatus.Checking -> RaisedButton(
                    text = stringResource(R.string.upd_checking),
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth(),
                )
                else -> RaisedButton(
                    text = if (status == UpdateStatus.Failed) stringResource(R.string.upd_try_again) else stringResource(R.string.upd_check_now),
                    onClick = onCheck,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {    Text(
        text, fontSize = 20.sp, fontWeight = FontWeight.Black, color = Pal.Ink,
        modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
    )
}

@Composable
fun SettingRow(title: String, hint: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    // Tapping the switch or anywhere on the row flips it, and either way it should feel the same.
    // The change is applied first, so turning Vibration off is silent and turning it on is felt.
    val view = LocalView.current
    val flip = { on: Boolean -> onChecked(on); Haptics.toggle(on, view) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { flip(!checked) }
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Pal.Ink)
            Spacer(Modifier.height(3.dp))
            Text(hint, fontSize = 14.sp, color = Pal.Muted, lineHeight = 20.sp)
        }
        Spacer(Modifier.width(16.dp))
        RaisedSwitch(checked = checked, onCheckedChange = flip)
    }
}
