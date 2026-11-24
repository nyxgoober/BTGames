package us.crafties.btgames.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.alpha
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import us.crafties.btgames.R
import us.crafties.btgames.games.chess.ChessPiece
import us.crafties.btgames.games.chess.drawChessPiece
import us.crafties.btgames.games.rps.Throw
import us.crafties.btgames.games.last.drawLastGlyph
import us.crafties.btgames.games.shutthebox.drawShutTheBoxGlyph
import us.crafties.btgames.games.rps.drawThrowGlyph
import us.crafties.btgames.net.GameIds
import us.crafties.btgames.shell.GameDef

/**
 * First screen: pick a game. Every game in the registry gets a tile, in two categories you can swipe
 * between (or tap): games for two players, and games that take more. In Screen mode only the
 * multiplayer games that have a table screen work; the rest are dimmed and say why when tapped.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MenuScreen(
    games: List<GameDef>,
    onPick: (GameDef) -> Unit,
    onSettings: () -> Unit,
    onCodenameTap: () -> Unit = {},
    updateAvailable: Boolean = false,
    onUpdateTap: () -> Unit = {},
    screenMode: Boolean = false,
    /** 0 = two players, 1 = multiplayer. */
    page: Int = 0,
    onPage: (Int) -> Unit = {},
) {
    val categories = listOf(
        R.string.menu_cat_two to games.filter { it.maxPlayers <= 2 },
        R.string.menu_cat_multi to games.filter { it.maxPlayers > 2 },
    )
    val pager = rememberPagerState(initialPage = page.coerceIn(0, 1)) { categories.size }
    val scope = rememberCoroutineScope()
    var blocked by remember { mutableStateOf(false) }
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { onPage(it); blocked = false } }
    // Screen mode switched on in Settings: the menu comes back showing the multiplayer games.
    LaunchedEffect(page) { if (page in 0..1 && page != pager.currentPage) pager.scrollToPage(page) }

    AppScreen(
        title = stringResource(R.string.menu_title),
        action = { IconChip(onClick = onSettings, description = stringResource(R.string.menu_settings_desc)) { drawGear(it) } },
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
                Text(
                    stringResource(R.string.menu_subtitle),
                    fontSize = 17.sp, color = Pal.Muted, lineHeight = 24.sp,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                // The boot-time update check reports here and only here: a quiet banner on the
                // menu, never a popup over a game, so server maintenance never interrupts play.
                if (updateAvailable) {
                    Spacer(Modifier.height(16.dp))
                    RaisedCard(
                        Modifier.padding(horizontal = 24.dp).fillMaxWidth().clickable(onClick = onUpdateTap),
                    ) {
                        // RaisedCard lays its content in a Box: stack in a Column or the
                        // title and description land on top of each other.
                        Column(Modifier.fillMaxWidth()) {
                            Text(
                                stringResource(R.string.menu_update_ready),
                                fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Pal.Ink,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                stringResource(R.string.menu_update_open),
                                fontSize = 14.sp, color = Pal.Muted, lineHeight = 20.sp,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                // The two categories: tap a title or swipe the list.
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                    categories.forEachIndexed { i, (title, _) ->
                        val selected = pager.currentPage == i
                        Column(
                            Modifier
                                .weight(1f)
                                .clickable { scope.launch { pager.animateScrollToPage(i) } }
                                .padding(top = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                stringResource(title),
                                fontSize = 17.sp,
                                fontWeight = if (selected) FontWeight.Black else FontWeight.Bold,
                                color = if (selected) Pal.Teal else Pal.Muted,
                            )
                            Spacer(Modifier.height(6.dp))
                            Box(
                                Modifier.fillMaxWidth().height(3.dp)
                                    .background(if (selected) Pal.Teal else Pal.Line.copy(alpha = 0.5f)),
                            )
                        }
                    }
                }

                if (blocked) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.menu_screen_blocked),
                        fontSize = 14.sp, color = Pal.Red, lineHeight = 20.sp,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }

                HorizontalPager(
                    state = pager,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    // The 24dp side margin lives INSIDE each page's scroll area (below), so the
                    // tiles' shadows have room to fall off before the scroll container clips them.
                    pageSpacing = 0.dp,
                    verticalAlignment = Alignment.Top,
                ) { p ->
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            // The extra room at the bottom keeps the last tile clear of the version tag.
                            .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 64.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        categories[p].second.forEach { g ->
                            val works = !screenMode || g.supportsScreen
                            GameTile(
                                g,
                                onClick = { if (works) onPick(g) else blocked = true },
                                modifier = Modifier.alpha(if (works) 1f else 0.4f),
                            )
                        }
                    }
                }
            }
            VersionTag(Modifier.align(Alignment.BottomEnd).padding(16.dp), onTap = onCodenameTap)
        }
    }
}

@Composable
private fun GameTile(game: GameDef, onClick: () -> Unit, modifier: Modifier = Modifier) {
    RaisedSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        tone = Tone.Light,
        bordered = false,
        shape = RoundedCornerShape(Radii.Card),
        elevation = 10.dp,
        contentPadding = PaddingValues(18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(84.dp)) { drawGlyph(game.id) }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(game.titleRes), fontSize = 28.sp, fontWeight = FontWeight.Black, color = Pal.Ink)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(game.blurbRes), fontSize = 15.sp, color = Pal.Muted, lineHeight = 21.sp)
            }
        }
    }
}

/**
 * A tiny picture of the game itself, in the colours the game uses. The tile is light, so the parts
 * the game draws in chalk (ball, centre line, grid) are drawn in [Pal.Ink] here.
 */
/** A little phone, drawn as a real piece for the Picture Match menu glyph. */
private fun DrawScope.drawPhonePiece(cx: Float, cy: Float, size: Float) {
    val w = size * 0.46f
    val h = size * 0.78f
    drawRoundRect(Pal.Teal, Offset(cx - w / 2f, cy - h / 2f), Size(w, h), CornerRadius(w * 0.18f))
    drawRoundRect(
        Pal.Face,
        Offset(cx - w * 0.38f, cy - h * 0.34f),
        Size(w * 0.76f, h * 0.62f),
        CornerRadius(w * 0.08f),
    )
    drawCircle(Pal.Teal, w * 0.08f, Offset(cx, cy + h * 0.36f))
}

/** An orange, drawn as a real piece for the Picture Match menu glyph. */
private fun DrawScope.drawOrangePiece(cx: Float, cy: Float, size: Float) {
    val r = size * 0.3f
    drawCircle(Color(0xFFF39C12), r, Offset(cx, cy))
    drawCircle(Color(0xFFFFD98A), r * 0.24f, Offset(cx - r * 0.32f, cy - r * 0.32f))
    drawArc(
        Pal.CardGreen, 190f, 140f, false,
        Offset(cx - r * 0.2f, cy - r * 1.15f), Size(r * 0.95f, r * 0.7f),
        style = Stroke(width = size * 0.055f),
    )
}

private fun DrawScope.drawGlyph(gameId: Int) {
    val s = size.width
    val stroke = s * 0.075f
    when (gameId) {
        GameIds.PADDLE -> {
            val padW = s * 0.09f
            val padH = s * 0.36f
            drawRoundRect(
                Pal.Red, Offset(s * 0.06f, s * 0.22f), Size(padW, padH), CornerRadius(padW / 2),
            )
            drawRoundRect(
                Pal.Blue, Offset(s * 0.94f - padW, s * 0.42f), Size(padW, padH), CornerRadius(padW / 2),
            )
            drawCircle(Pal.Ink, s * 0.06f, Offset(s * 0.56f, s * 0.36f))
            // dashed centre line
            var y = s * 0.04f
            while (y < s * 0.96f) {
                drawLine(Pal.Ink.copy(alpha = 0.22f), Offset(s / 2, y), Offset(s / 2, y + s * 0.06f), stroke * 0.5f)
                y += s * 0.13f
            }
        }
        GameIds.XO -> {
            val c = s / 3f
            val grid = Pal.Ink.copy(alpha = 0.35f)
            for (i in 1..2) {
                drawLine(grid, Offset(c * i, s * 0.05f), Offset(c * i, s * 0.95f), stroke * 0.6f, StrokeCap.Round)
                drawLine(grid, Offset(s * 0.05f, c * i), Offset(s * 0.95f, c * i), stroke * 0.6f, StrokeCap.Round)
            }
            // X in the top-left cell (red), O in the centre cell (blue)
            val r = c * 0.26f
            val x0 = c * 0.5f; val y0 = c * 0.5f
            drawLine(Pal.Red, Offset(x0 - r, y0 - r), Offset(x0 + r, y0 + r), stroke, StrokeCap.Round)
            drawLine(Pal.Red, Offset(x0 - r, y0 + r), Offset(x0 + r, y0 - r), stroke, StrokeCap.Round)
            drawCircle(Pal.Blue, r * 1.05f, Offset(c * 1.5f, c * 1.5f), style = Stroke(width = stroke))
        }
        GameIds.TETRIMINOES -> {
            // A falling T (red) landing on a stacked row (blue).
            val u = s / 5f
            fun block(x: Int, y: Int, color: androidx.compose.ui.graphics.Color) {
                drawRoundRect(
                    color, Offset(x * u, y * u), Size(u * 0.92f, u * 0.92f),
                    CornerRadius(u * 0.2f),
                )
            }
            block(0, 4, Pal.Blue); block(1, 4, Pal.Blue); block(2, 4, Pal.Blue); block(3, 4, Pal.Blue)
            block(1, 1, Pal.Red); block(0, 2, Pal.Red); block(1, 2, Pal.Red); block(2, 2, Pal.Red)
        }
        GameIds.KEYMASH -> {
            // A little keyboard: three rows of keys and a wide spacebar.
            val u = s / 8f
            fun key(x: Float, y: Float, w: Float, color: androidx.compose.ui.graphics.Color) {
                drawRoundRect(
                    color, Offset(x * u, y * u), Size(w * u, u * 0.82f),
                    CornerRadius(u * 0.18f),
                )
            }
            for (i in 0..5) key(0.5f + i * 1.18f, 2f, 1f, Pal.Muted.copy(alpha = 0.55f))
            for (i in 0..5) key(0.8f + i * 1.18f, 3.5f, 1f, if (i % 2 == 0) Pal.Red else Pal.Blue)
            key(1.6f, 5f, 4.8f, Pal.Ink)
        }
        GameIds.BLOCK_TOWER -> {
            // A Jenga stack: alternating long planks and triple blocks, one hovering
            // teal-outlined above the tower like a selected pull.
            val u = s / 8f
            val wood = Color(0.88f, 0.66f, 0.38f)
            val woodDark = Color(0.74f, 0.52f, 0.27f)
            val groove = Color(0.62f, 0.42f, 0.21f)
            fun plank(x: Float, y: Float, w: Float, color: Color) {
                drawRoundRect(
                    color, Offset(x * u, y * u), Size(w * u, 0.92f * u),
                    CornerRadius(0.16f * u),
                )
            }
            fun grooves(y: Float) {
                for (f in listOf(0.35f, 0.65f)) {
                    drawLine(
                        groove, Offset(1.3f * u, (y + f * 0.92f) * u),
                        Offset(6.7f * u, (y + f * 0.92f) * u), 0.09f * u, StrokeCap.Round,
                    )
                }
            }
            plank(1f, 6.35f, 6f, woodDark)
            for (i in 0..2) plank(1f + i * 2.03f, 5.3f, 1.94f, wood)
            plank(1f, 4.25f, 6f, wood)
            grooves(4.25f)
            plank(1f, 3.2f, 1.94f, wood)
            plank(5.06f, 3.2f, 1.94f, wood)
            // The pulled block, hovering above the gap it left, outlined like a selection.
            val fx = 3.03f * u; val fy = 1.35f * u; val fw = 1.94f * u; val fh = 0.92f * u
            drawRoundRect(wood, Offset(fx, fy), Size(fw, fh), CornerRadius(0.16f * u))
            drawRoundRect(
                Pal.Teal, Offset(fx, fy), Size(fw, fh), CornerRadius(0.16f * u),
                style = Stroke(width = 0.14f * u),
            )
        }
        GameIds.RPS -> {
            // All three throws side by side, each in a player colour.
            val third = s / 3f
            listOf(Throw.ROCK to Pal.Red, Throw.PAPER to Pal.Ink, Throw.SCISSORS to Pal.Blue)
                .forEachIndexed { i, (t, c) ->
                    withTransform({
                        translate(left = i * third, top = third)
                        scale(1f / 3f, 1f / 3f, pivot = Offset.Zero)
                    }) { drawThrowGlyph(t, c) }
                }
        }
        GameIds.FOUR_CHIPS -> {
            // Four connected diagonally: three red, the winner blue.
            val c = s / 5f
            val r = c * 0.46f
            for (i in 0..3) {
                val cx = c * (1.2f + i)
                val cy = s - c * (1.2f + i)
                drawCircle(if (i < 3) Pal.Red else Pal.Blue, r, Offset(cx, cy))
                drawCircle(Color.White.copy(alpha = 0.30f), r * 0.45f, Offset(cx, cy))
            }
        }
        GameIds.CHECKERS -> {
            // Two men on dark squares: red, and a crowned blue king.
            val q = s * 0.42f
            drawRect(Pal.Court, Offset(s * 0.06f, s * 0.06f), Size(q, q))
            drawRect(Pal.Court, Offset(s * 0.94f - q, s * 0.94f - q), Size(q, q))
            drawCircle(Pal.Red, s * 0.15f, Offset(s * 0.06f + q / 2, s * 0.06f + q / 2))
            val kc = Offset(s * 0.94f - q / 2, s * 0.94f - q / 2)
            drawCircle(Pal.Blue, s * 0.15f, kc)
            drawCircle(Pal.Chalk, s * 0.06f, kc)
        }
        GameIds.CUP_SHUFFLE -> {
            // Three cups, the middle one lifting to show the ball.
            val w = s / 3f
            for (i in 0..2) {
                val cx = w * (i + 0.5f)
                val lift = if (i == 1) s * 0.10f else 0f
                if (i == 1) drawCircle(Pal.Red, s * 0.07f, Offset(cx, s * 0.74f))
                drawRoundRect(
                    Pal.Ink, Offset(cx - w * 0.28f, s * 0.30f - lift), Size(w * 0.56f, s * 0.34f),
                    CornerRadius(w * 0.08f),
                )
            }
        }
        GameIds.CHESS -> {
            // A four-square board filling the tile, with a knight standing on it. The two are
            // centred as one picture: the knight art leans left (-0.44..0.33), so its centre
            // sits a touch right of the board's.
            val q = s * 0.42f
            val o = s * 0.08f
            drawRect(Pal.Chalk, Offset(o, o), Size(q, q))
            drawRect(Pal.Court, Offset(o + q, o), Size(q, q))
            drawRect(Pal.Court, Offset(o, o + q), Size(q, q))
            drawRect(Pal.Chalk, Offset(o + q, o + q), Size(q, q))
            drawChessPiece(ChessPiece.WN, Pal.Teal, Offset(s * 0.53f, s * 0.50f), s * 0.62f)
        }
        GameIds.SHUT_THE_BOX -> drawShutTheBoxGlyph()
        GameIds.LAST -> drawLastGlyph()
        GameIds.PICTURE_MATCH -> {
            // Four pieces in a 2×2 cube: phone, orange, orange, phone.
            val pad = s * 0.08f
            val cell = (s - pad * 3f) / 2f
            val kinds = listOf(0, 1, 1, 0)
            for (i in 0..3) {
                val x = pad + (i % 2) * (cell + pad)
                val y = pad + (i / 2) * (cell + pad)
                val shape = CornerRadius(cell * 0.16f)
                drawRoundRect(Pal.Face, Offset(x, y), Size(cell, cell), shape)
                drawRoundRect(Pal.Line, Offset(x, y), Size(cell, cell), shape, style = Stroke(width = s * 0.028f))
                if (kinds[i] == 0) drawPhonePiece(x + cell / 2f, y + cell / 2f, cell)
                else drawOrangePiece(x + cell / 2f, y + cell / 2f, cell)
            }
        }
        else -> Unit
    }
}

