package us.crafties.btgames.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * How long a surface stays darkened after the finger lifts. A phone has no hover, so this is the
 * stand-in: the press is acknowledged, then eases back rather than snapping, which is what makes
 * the control feel weighty instead of electronic.
 */
private const val LINGER_MS = 160L
private const val DARKEN_IN_MS = 70
private const val DARKEN_OUT_MS = 260

/**
 * What a surface is for.
 *
 *  - [Light]   a white card or button with a pale blue outline. The ordinary choice.
 *  - [Primary] filled cyan with white text: the one main action on a screen, or something selected.
 *
 * Either one can drop its outline with `bordered = false` (the game tiles do): the shadow alone
 * then separates it from the page.
 */
enum class Tone { Light, Primary }

/**
 * Corner radii. Every rounded thing in the app takes one of these so buttons, fields, cards and
 * dialogs read as one family.
 */
object Radii {
    /** Buttons, text fields, list rows, notices. */
    val Control = 14.dp
    /** Cards, game tiles, dialogs. */
    val Card = 20.dp
}

/**
 * A raised control with a soft blue-grey drop shadow. It sinks and darkens while pressed and for a
 * moment after, and the shadow shrinks with it: that is the whole 3D illusion.
 *
 * Everything the look needs is here so screens do not each reinvent it.
 */
@Composable
fun RaisedSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Light,
    enabled: Boolean = true,
    bordered: Boolean = true,
    shape: Shape = RoundedCornerShape(Radii.Control),
    elevation: Dp = 6.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
    content: @Composable () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()

    // "Linger": stay in the darkened state for a beat after release.
    var held by remember { mutableStateOf(false) }
    LaunchedEffect(pressed) {
        if (pressed) held = true else { delay(LINGER_MS); held = false }
    }
    val down = enabled && (pressed || held)

    val top = when (tone) { Tone.Light -> Pal.Face; Tone.Primary -> Pal.Teal }
    val bottom = when (tone) { Tone.Light -> Pal.FaceLow; Tone.Primary -> Pal.Teal }
    val sunk = when (tone) { Tone.Light -> Pal.FacePressed; Tone.Primary -> Pal.TealPressed }

    val faceTop by animateColorAsState(
        if (down) sunk else top, tween(if (pressed) DARKEN_IN_MS else DARKEN_OUT_MS), label = "faceTop",
    )
    val faceBottom by animateColorAsState(
        if (down) sunk else bottom, tween(if (pressed) DARKEN_IN_MS else DARKEN_OUT_MS), label = "faceBottom",
    )
    val lift by animateDpAsState(if (down) elevation * 0.25f else elevation, tween(DARKEN_IN_MS), label = "lift")

    // A faint white sheen along the top edge of the coloured surfaces. Deliberately weak: on a
    // strong colour a strong sheen reads as a harsh white gradient. White cards get none.
    val sheen = when (tone) { Tone.Light -> 0f; Tone.Primary -> 0.14f }
    val outline = when {
        !bordered -> null
        tone == Tone.Light -> BorderStroke(1.5.dp, Pal.Line)
        else -> BorderStroke(1.dp, Pal.TealDark)
    }
    val alpha = if (enabled) 1f else 0.5f

    Box(
        modifier
            .shadow(lift, shape, ambientColor = Pal.Shadow, spotColor = Pal.Shadow)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(faceTop, faceBottom)))
            .drawWithContent {
                drawContent()
                if (sheen > 0f) {
                    drawRect(
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = if (down) sheen * 0.25f else sheen), Color.Transparent),
                            endY = 14.dp.toPx(),
                        ),
                        size = Size(size.width, 14.dp.toPx()),
                    )
                }
            }
            .then(if (outline != null) Modifier.border(outline, shape) else Modifier)
            .clickable(
                interactionSource = source, indication = null, enabled = enabled,
                onClick = { Haptics.tick(); onClick() },
            )
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        val ink = if (tone == Tone.Light) Pal.Ink else Pal.Chalk
        CompositionLocalProvider(LocalContentColor provides ink.copy(alpha = alpha)) { content() }
    }
}

/**
 * A labelled button in the raised style.
 *
 * The label is never cut off. It wraps to a second line, and if it still does not fit (a long
 * nickname in it, say) the type shrinks a step at a time until it does.
 */
@Composable
fun RaisedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Light,
    enabled: Boolean = true,
) {
    RaisedSurface(onClick = onClick, modifier = modifier, tone = tone, enabled = enabled) {
        FitText(text, maxSize = 18.sp, weight = FontWeight.Bold, color = LocalContentColor.current)
    }
}

/**
 * Text that shrinks to fit instead of being cut off. Tries [maxSize] first, then steps down by
 * about 8% until it fits in [maxLines] lines or reaches [minSize]. Only at the floor does it fall
 * back to an ellipsis, which for a button label means a name of 24 characters in a very narrow
 * button.
 */
@Composable
fun FitText(
    text: String,
    modifier: Modifier = Modifier,
    maxSize: TextUnit = 18.sp,
    minSize: TextUnit = 12.sp,
    maxLines: Int = 2,
    weight: FontWeight = FontWeight.Bold,
    color: Color = LocalContentColor.current,
    textAlign: TextAlign = TextAlign.Center,
) {
    // Restart from the biggest size whenever the text or the ceiling changes.
    var size by remember(text, maxSize) { mutableStateOf(maxSize) }
    val atFloor = size.value <= minSize.value
    Text(
        text,
        modifier = modifier,
        fontSize = size,
        lineHeight = size * 1.15f,
        fontWeight = weight,
        color = color,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = if (atFloor) TextOverflow.Ellipsis else TextOverflow.Clip,
        onTextLayout = { layout ->
            if (layout.hasVisualOverflow && !atFloor) size = (size.value * 0.92f).coerceAtLeast(minSize.value).sp
        },
    )
}

/**
 * A non-interactive raised card: white, pale blue outline, soft shadow. It never darkens, because
 * nothing happens when you tap it.
 */
@Composable
fun RaisedCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Radii.Card),
    elevation: Dp = 8.dp,
    borderColor: Color = Pal.Line,
    borderWidth: Dp = 1.5.dp,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .shadow(elevation, shape, ambientColor = Pal.Shadow, spotColor = Pal.Shadow)
            .clip(shape)
            .background(Pal.Face)
            .border(BorderStroke(borderWidth, borderColor), shape)
            .padding(contentPadding),
    ) {
        CompositionLocalProvider(LocalContentColor provides Pal.Ink) { content() }
    }
}

/**
 * The inside of a text field, and the status notices: white with a pale blue outline and a faint
 * shade along the top so it reads as slightly sunken. [borderColor] lets a field show focus.
 */
@Composable
fun Groove(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Radii.Control),
    contentPadding: PaddingValues = PaddingValues(14.dp),
    borderColor: Color = Pal.Line,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .clip(shape)
            .background(Pal.Groove)
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.verticalGradient(
                        listOf(Pal.Shadow.copy(alpha = 0.07f), Color.Transparent),
                        endY = 7.dp.toPx(),
                    ),
                    size = Size(size.width, 7.dp.toPx()),
                )
            }
            .border(BorderStroke(1.5.dp, borderColor), shape)
            .padding(contentPadding),
    ) {
        CompositionLocalProvider(LocalContentColor provides Pal.Ink) { content() }
    }
}

/**
 * Lets a scroller (a LazyColumn, say) reach [by] past its parent's side padding on both sides, so
 * the shadows of the cards in it are not cut off at the list's edge. Scrollers clip to their own
 * bounds. Give the scroller the same amount back as horizontal `contentPadding` so the cards stay
 * where they were.
 */
fun Modifier.sideBleed(by: Dp): Modifier = layout { measurable, constraints ->
    val extra = by.roundToPx()
    val placeable = measurable.measure(constraints.offset(horizontal = extra * 2))
    layout(placeable.width - extra * 2, placeable.height) { placeable.place(-extra, 0) }
}
