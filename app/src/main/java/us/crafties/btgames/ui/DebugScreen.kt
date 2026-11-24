package us.crafties.btgames.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import us.crafties.btgames.net.Proto

/**
 * The secret debug menu. Nothing here affects real matches; it is for trying the games
 * without a second phone.
 */
@Composable
fun DebugScreen(
    testMode: Boolean,
    onTestMode: (Boolean) -> Unit,
    reportedVersion: Int?,
    onReportedVersion: (Int?) -> Unit,
    onBack: () -> Unit,
) {
    AppScreen(title = "Debug", onBack = onBack) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 64.dp),
            ) {
                Text(
                    "Options for engine testing and debugging",
                    fontSize = 17.sp, color = Pal.Muted, lineHeight = 24.sp,
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    "Test mode", fontSize = 20.sp, fontWeight = FontWeight.Black, color = Pal.Ink,
                    modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
                )
                RaisedCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 4.dp)) {
                    SettingRow(
                        title = "Peer bot",
                        hint = "A practice bot appears in every lobby under its own game name. " +
                            "Play them to test any game solo. They play random moves.",
                        checked = testMode,
                        onChecked = onTestMode,
                    )
                }

                Spacer(Modifier.height(28.dp))
                Text(
                    "Compatibility", fontSize = 20.sp, fontWeight = FontWeight.Black, color = Pal.Ink,
                    modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
                )
                RaisedCard(Modifier.fillMaxWidth()) {
                    Column {
                        Text(
                            "Reported version",
                            fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Pal.Ink,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "Change the protocol version reported to peers via the broadcast. " +
                                "This build is version ${Proto.VERSION}. Leave empty to use current version",
                            fontSize = 14.sp, color = Pal.Muted, lineHeight = 20.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        GrooveTextField(
                            value = reportedVersion?.toString().orEmpty(),
                            onValueChange = { raw ->
                                val digits = raw.filter { it.isDigit() }.take(3)
                                if (digits.isEmpty()) {
                                    onReportedVersion(null)
                                } else {
                                    // Out of range: ignore the keystroke, the field snaps back.
                                    digits.toIntOrNull()?.takeIf { it in 0..255 }?.let(onReportedVersion)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = Proto.VERSION.toString(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done,
                            ),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Reporting version ${reportedVersion ?: Proto.VERSION}",
                            fontSize = 14.sp, color = Pal.Muted, lineHeight = 20.sp,
                        )
                    }
                }
            }
            VersionTag(Modifier.align(Alignment.BottomEnd).padding(16.dp))
        }
    }
}
