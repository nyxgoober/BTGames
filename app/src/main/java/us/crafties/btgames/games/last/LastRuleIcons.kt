package us.crafties.btgames.games.last

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import us.crafties.btgames.ui.Pal

/** Which rule an icon tile represents. */
enum class LastRuleIcon {
    CARDS, SLAP_SIX, DRAW_ONE, DRAW_MATCH, DRAW_TWO, FORCE, DOUBLE, ZERO_SEVEN, DEFENSE, STACK_LESSER,
}

/**
 * One rule tile: icon, label, and a check circle on the bottom-right when enabled. Disabled rules
 * are grey cards. No explanation text anywhere.
 */
@Composable
fun RuleTile(
    icon: LastRuleIcon,
    label: String,
    enabled: Boolean,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .size(width = 104.dp, height = 104.dp)
            .clip(shape)
            .background(if (enabled) Pal.Face else Pal.PaperDim)
            .border(1.dp, if (enabled) Pal.Line else Pal.EdgeShade, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(6.dp),
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            RuleIcon(icon, enabled)
            Text(
                label, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                color = if (enabled) Pal.Ink else Pal.Muted,
                maxLines = 2,
            )
        }
        if (enabled) {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(4.dp).size(18.dp)
                    .clip(CircleShape).background(Pal.Teal),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.foundation.Canvas(Modifier.size(11.dp)) {
                    val path = Path().apply {
                        moveTo(size.width * 0.15f, size.height * 0.52f)
                        lineTo(size.width * 0.42f, size.height * 0.78f)
                        lineTo(size.width * 0.85f, size.height * 0.22f)
                    }
                    drawPath(path, Pal.Chalk, style = Stroke(width = 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
                }
            }
        }
    }
}

@Composable
private fun RuleIcon(icon: LastRuleIcon, enabled: Boolean) {
    val dim = if (enabled) 1f else 0.4f
    Box(Modifier.size(52.dp).alpha(dim), contentAlignment = Alignment.Center) {
        when (icon) {
            LastRuleIcon.CARDS -> CardStack()
            LastRuleIcon.SLAP_SIX -> MiniCard(Pal.CardGreen, "6", Pal.Red)
            LastRuleIcon.DRAW_ONE -> DrawPile("1")
            LastRuleIcon.DRAW_MATCH -> DrawPile("∞")
            LastRuleIcon.DRAW_TWO -> DrawPile("2")
            LastRuleIcon.FORCE -> ForcePlay()
            LastRuleIcon.DOUBLE -> DoubleSplit()
            LastRuleIcon.ZERO_SEVEN -> ZeroSeven()
            LastRuleIcon.DEFENSE -> Defense()
            LastRuleIcon.STACK_LESSER -> StackLesser()
        }
    }
}

@Composable
private fun MiniCard(color: Color, text: String, border: Color? = null, modifier: Modifier = Modifier, fontSize: androidx.compose.ui.unit.TextUnit = 16.sp) {
    Box(
        modifier.size(width = 26.dp, height = 38.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(color)
            .then(if (border != null) Modifier.border(2.dp, border, RoundedCornerShape(4.dp)) else Modifier),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = fontSize, fontWeight = FontWeight.Black, color = Pal.Chalk) }
}

@Composable
private fun CardStack() {
    Box {
        MiniCard(Pal.Court, "", Pal.Line, Modifier.offset(x = 6.dp, y = 6.dp))
        MiniCard(Pal.Court, "", Pal.Line, Modifier.offset(x = 3.dp, y = 3.dp))
        MiniCard(Pal.Court, "", Pal.Teal)
    }
}

@Composable
private fun DrawPile(mark: String) {
    Box {
        MiniCard(Pal.Court, "", Pal.Line, Modifier.offset(x = 3.dp, y = 3.dp))
        MiniCard(Pal.Court, mark, Pal.Teal)
    }
}

@Composable
private fun ForcePlay() {
    Box {
        MiniCard(Pal.Court, "", Pal.Line, Modifier.offset(y = (-6).dp))
        MiniCard(Pal.CardRed, "", Pal.Teal, Modifier.offset(y = 8.dp))
    }
}

@Composable
private fun DoubleSplit() {
    Box(Modifier.size(34.dp, 46.dp).clip(RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            val red = Path().apply { moveTo(0f, h); lineTo(w, 0f); lineTo(0f, 0f); close() }
            val blue = Path().apply { moveTo(0f, h); lineTo(w, 0f); lineTo(w, h); close() }
            drawPath(red, Pal.CardRed)
            drawPath(blue, Pal.CardBlue)
            drawRoundRect(
                Pal.Chalk, Offset.Zero, androidx.compose.ui.geometry.Size(w, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
        Text("2", fontSize = 18.sp, fontWeight = FontWeight.Black, color = Pal.Chalk)
    }
}

@Composable
private fun ZeroSeven() {
    // Two real cards: the 0 fanned behind, the 0 in front on top of a 7.
    Box(contentAlignment = Alignment.Center) {
        MiniCard(Pal.CardBlue, "0", Pal.Line, Modifier.offset(x = (-9).dp, y = (-6).dp))
        MiniCard(Pal.CardRed, "7", Pal.Teal, Modifier.offset(x = 6.dp, y = 5.dp))
    }
}

@Composable
private fun Defense() {
    // A Spin card landing squarely on a +2 card, both aligned with the tile.
    Box(contentAlignment = Alignment.Center) {
        MiniCard(Pal.CardYellow, "+2", Pal.Line, Modifier.offset(y = 8.dp))
        Box(Modifier.offset(y = (-11).dp).size(width = 26.dp, height = 38.dp), contentAlignment = Alignment.Center) {
            MiniCard(Pal.Court, "", Pal.Teal)
            androidx.compose.foundation.Canvas(Modifier.matchParentSize().padding(4.dp)) { drawSpin(Pal.Teal) }
        }
    }
}

@Composable
private fun StackLesser() {
    // A +2 card fanned into a +2² card: the lesser card in front, slightly tilted.
    Box(contentAlignment = Alignment.Center) {
        MiniCard(Pal.Court, "+2²", Pal.Line, Modifier.offset(x = 5.dp, y = 8.dp), fontSize = 12.sp)
        MiniCard(Pal.CardRed, "+2", Pal.Teal, Modifier.offset(x = (-6).dp, y = (-8).dp).graphicsLayer { rotationZ = -12f })
    }
}
