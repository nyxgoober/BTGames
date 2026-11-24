package us.crafties.btgames.ui

import android.os.Build
import android.view.RoundedCorner
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import us.crafties.btgames.R
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

/**
 * A screen laid out like the reference's windows: a cyan title bar with the title centred in white,
 * over a light grey body.
 *
 * The bar extends up behind the status bar, so the cyan reaches the top of the screen. [content]
 * gets the body, already kept clear of the navigation bar, display cutouts and the keyboard; each
 * screen adds its own padding inside it.
 *
 * [onBack] adds a back button at the left, [action] puts one thing (usually an [IconChip]) at the
 * right.
 */
@Composable
fun AppScreen(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().background(Pal.Paper)) {
        TitleBar(title, onBack, action)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
            content = content,
        )
    }
}

/** The bar's bottom corners: softer than a dialog's (which use [Radii.Card]). */
private val HeaderRadius = 16.dp

/**
 * The radius of the bar's bottom corners: [HeaderRadius], or the phone's own screen-corner radius
 * if that is smaller (Android 12+ reports it). A rounder corner than this would cut into the back
 * button, so the phone's radius is never used when it is bigger.
 */
@Composable
private fun headerRadius(): Dp {
    val view = LocalView.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current // the corners can change when the phone rotates
    return remember(view, density, configuration) {
        val px = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius
        } else null
        val device = px?.let { with(density) { it.toDp() } }
        if (device != null && device > 0.dp) minOf(device, HeaderRadius) else HeaderRadius
    }
}

@Composable
private fun TitleBar(title: String, onBack: (() -> Unit)?, action: (@Composable () -> Unit)?) {
    val radius = headerRadius()
    val shape = RoundedCornerShape(bottomStart = radius, bottomEnd = radius)
    Box(
        Modifier
            .zIndex(1f) // so the shadow falls over the body, which is drawn after it
            .fillMaxWidth()
            .shadow(8.dp, shape, ambientColor = Pal.Shadow, spotColor = Pal.Shadow)
            .background(Pal.Teal, shape)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        Box(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 8.dp)) {
            // Both sides reserve the same width, so the title is centred whichever buttons exist.
            Text(
                title,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp),
                color = Pal.Chalk, fontSize = 24.sp, fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (onBack != null) {
                IconChip(onClick = onBack, description = stringResource(R.string.common_back), modifier = Modifier.align(Alignment.CenterStart)) { drawBack(it) }
            }
            if (action != null) Box(Modifier.align(Alignment.CenterEnd)) { action() }
        }
    }
}

/**
 * A small square button that sits on the title bar: a lighter cyan chip with a white icon, like the
 * bin and copy buttons in the reference's window header. It brightens while pressed.
 */
@Composable
fun IconChip(
    onClick: () -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    icon: DrawScope.(Color) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .size(44.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = if (pressed) 0.34f else 0.20f))
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)), shape)
            .clickable(interactionSource = source, indication = null, onClick = { Haptics.tick(); onClick() })
            .semantics { this.contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp)) { this.icon(Pal.Chalk) }
    }
}
