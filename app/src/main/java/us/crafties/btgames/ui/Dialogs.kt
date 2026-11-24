package us.crafties.btgames.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** The cyan frame around a dialog's body: left, right and bottom. The title bar is the top of it. */
private val Frame = 2.dp

/** How far the shadow reaches, and how far it is pushed down. */
private val ShadowReach = 26.dp
private val ShadowDrop = 10.dp

/**
 * A dialog drawn like the reference's windows: a cyan frame with the title centred in white on
 * top, and the light grey body sitting inside it as its own rounded panel (the cyan shows around
 * it), all on a soft drop shadow.
 *
 * The shadow is drawn by hand ([Modifier.softShadow]) rather than with `Modifier.shadow`. The
 * system's elevation shadow is only about 20% dark at its strongest, and a dialog also dims the
 * page behind it, so that shadow all but disappears. This one stays visible on the dimmed page.
 *
 * The window sits inside a transparent margin. The margin is not decoration: a dialog is its own
 * window, and a shadow cannot draw outside the window, so the margin is what leaves room for it.
 *
 * [dismissible] false means neither back nor a tap outside closes it: for prompts another
 * person is waiting on.
 */
@Composable
fun RaisedDialog(
    title: String,
    onDismissRequest: () -> Unit,
    dismissible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = dismissible,
            usePlatformDefaultWidth = false,
        ),
    ) {
        val window = RoundedCornerShape(Radii.Card)
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = ShadowReach + ShadowDrop + 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .widthIn(max = 400.dp)
                    .fillMaxWidth()
                    .softShadow(Radii.Card)
                    .clip(window)
                    .background(Pal.Teal),
            ) {
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    FitText(title, maxSize = 22.sp, minSize = 16.sp, weight = FontWeight.Black, color = Pal.Chalk)
                }
                // The body is its own rounded panel, inset from the frame on three sides.
                // Its bottom corners sit inside the window's own rounded corners, so their radius
                // is derived from the window radius minus the frame width: the two curves stay
                // concentric (an even cyan frame) whatever values the tokens take.
                CompositionLocalProvider(LocalContentColor provides Pal.Ink) {
                    Column(
                        Modifier
                            .padding(start = Frame, end = Frame, bottom = Frame)
                            .fillMaxWidth()
                            .clip(
                                RoundedCornerShape(
                                    topStart = Radii.Control,
                                    topEnd = Radii.Control,
                                    bottomStart = Radii.Card - Frame,
                                    bottomEnd = Radii.Card - Frame,
                                ),
                            )
                            .background(Pal.Paper)
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        content = content,
                    )
                }
            }
        }
    }
}

/**
 * A soft, dark drop shadow behind a rounded rectangle, drawn as a stack of faint rounded
 * rectangles that grow outwards. Where they overlap (close to the edge) the shadow is darkest, and
 * it fades away at [reach]. It is pushed down by [drop] so it reads as light from above.
 *
 * Unlike `Modifier.shadow` this looks the same on every Android version and can be made as strong
 * as it needs to be. It draws outside the bounds of the thing it is on, so the parent has to leave
 * [reach] + [drop] of room.
 */
private fun Modifier.softShadow(
    corner: Dp,
    reach: Dp = ShadowReach,
    drop: Dp = ShadowDrop,
    layers: Int = 14,
    layerAlpha: Float = 0.04f,
): Modifier = drawBehind {
    val base = corner.toPx()
    for (i in layers downTo 1) {
        val grow = reach.toPx() * i / layers
        drawRoundRect(
            color = Pal.Shadow.copy(alpha = layerAlpha),
            topLeft = Offset(-grow, -grow + drop.toPx()),
            size = Size(size.width + grow * 2, size.height + grow * 2),
            cornerRadius = CornerRadius(base + grow),
        )
    }
}
