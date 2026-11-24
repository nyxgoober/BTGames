package us.crafties.btgames.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import us.crafties.btgames.R

/**
 * The release codename, as plain text laid directly over the page (no pill or card behind it). The
 * text comes from `codename` in strings.xml, so a new release is a one-line change there.
 *
 * Five taps unlock the secret debug menu ([onTap] counts them); it looks and feels like nothing.
 */
@Composable
fun VersionTag(modifier: Modifier = Modifier, onTap: () -> Unit = {}) {
    Text(
        text = stringResource(R.string.codename),
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onTap,
        ),
        color = Pal.Muted,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
    )
}
