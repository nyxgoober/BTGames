package us.crafties.btgames.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * A text box in the app's own style: a recessed well (see [Groove]) with the same corner radius as
 * the buttons, and a border that turns cyan while you type. Built on [BasicTextField] because
 * Material's outlined field cannot be given this shape, this fill and this inner shadow together.
 *
 * The whole well is tappable, not just the line of text inside it.
 */
@Composable
fun GrooveTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    textStyle: TextStyle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    // BasicTextField does not pick up the theme's text style, so the app font is applied here.
    val style = textStyle.copy(fontFamily = AppFont)
    var focused by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    val border by animateColorAsState(
        if (focused) Pal.Teal else Pal.Line,
        tween(140), label = "fieldBorder",
    )

    Groove(
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null,
        ) { requester.requestFocus() },
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        borderColor = border,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = style.copy(color = Pal.Ink),
            cursorBrush = SolidColor(Pal.Red),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(requester)
                .onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty() && placeholder != null) {
                        Text(placeholder, style = style.copy(color = Pal.Muted.copy(alpha = 0.6f)))
                    }
                    inner()
                }
            },
        )
    }
}

/**
 * An on/off switch: a pill that fills cyan when on, with a white knob that slides across and
 * carries the same soft shadow as the buttons.
 *
 * Tap it to flip it, or press and drag the knob (or anywhere on the pill): the knob follows the
 * finger and the pill changes colour as it goes. On release it settles on whichever side it is
 * nearer to, or the side a quick flick was heading towards. [onCheckedChange] is called once, when
 * the state actually changes.
 */
@Composable
fun RaisedSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pill = RoundedCornerShape(50)
    val travelPx = with(LocalDensity.current) { SwitchTravel.toPx() }

    // 0 = off, 1 = on, and anything between while the knob is moving.
    var pos by remember { mutableFloatStateOf(if (checked) 1f else 0f) }
    var dragging by remember { mutableStateOf(false) }

    // Slide to the new state when it changes from outside (a tap, or the row being tapped).
    LaunchedEffect(checked) {
        if (!dragging) {
            animate(pos, if (checked) 1f else 0f, animationSpec = tween(180)) { v, _ -> if (!dragging) pos = v }
        }
    }

    val dragState = rememberDraggableState { delta -> pos = (pos + delta / travelPx).coerceIn(0f, 1f) }

    Box(
        modifier
            .size(width = 58.dp, height = 34.dp)
            .clip(pill)
            .background(lerp(Pal.EdgeShade, Pal.Teal, pos))
            .border(BorderStroke(1.5.dp, lerp(Pal.Line, Pal.TealDark, pos)), pill)
            .toggleable(
                value = checked, onValueChange = onCheckedChange, role = Role.Switch,
                interactionSource = remember { MutableInteractionSource() }, indication = null,
            )
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                onDragStarted = { dragging = true },
                onDragStopped = { velocity ->
                    dragging = false
                    // A quick flick goes the way it was flicked; a slow drag goes to the nearer side.
                    val on = if (abs(velocity) > 700f) velocity > 0f else pos >= 0.5f
                    if (on != checked) {
                        onCheckedChange(on) // the effect above then slides it the rest of the way
                    } else {
                        animate(pos, if (on) 1f else 0f, animationSpec = tween(140)) { v, _ -> if (!dragging) pos = v }
                    }
                },
            ),
    ) {
        Box(
            Modifier
                .offset { IntOffset((4.dp + SwitchTravel * pos).roundToPx(), 4.dp.roundToPx()) }
                .size(26.dp)
                .shadow(4.dp, CircleShape, ambientColor = Pal.Shadow, spotColor = Pal.Shadow)
                .clip(CircleShape)
                .background(Pal.Chalk),
        )
    }
}

/** How far the knob travels between its two resting places. */
private val SwitchTravel = 24.dp
