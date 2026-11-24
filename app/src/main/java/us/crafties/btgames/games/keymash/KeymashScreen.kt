package us.crafties.btgames.games.keymash

import androidx.compose.animation.core.tween
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import us.crafties.btgames.R
import us.crafties.btgames.ui.Haptics
import us.crafties.btgames.ui.Pal
import us.crafties.btgames.ui.RaisedButton
import us.crafties.btgames.ui.RaisedCard
import us.crafties.btgames.ui.Tone
import us.crafties.btgames.ui.Wording

@Composable
fun KeymashScreen(
    state: KeymashUi,
    onChoose: (KeymashMode) -> Unit,
    onField: (String) -> Unit,
    onSubmitWord: () -> Unit,
    onRematch: () -> Unit,
    onLeave: () -> Unit,
) {
    // Feel the race: a tick per submitted word, a click for a wrong one, a thump at the
    // finish and a pattern for the result.
    val lastIndex = state.index
    LaunchedEffect(lastIndex, state.phase) {
        if (state.phase == KeymashPhase.PLAYING && lastIndex > 0) {
            if (state.wordOk.lastOrNull() == false) Haptics.click() else Haptics.tick()
        }
    }
    LaunchedEffect(state.myDone) {
        if (state.myDone) Haptics.thud()
    }
    LaunchedEffect(state.outcome) {
        when (state.outcome) {
            KeymashResult.WIN -> Haptics.win()
            KeymashResult.LOSS -> Haptics.lose()
            KeymashResult.DRAW -> Haptics.click()
            null -> Unit
        }
    }

    // The keyboard is the controller: summon it the moment the race starts, dismiss it after.
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(state.phase) {
        if (state.phase == KeymashPhase.PLAYING) focusRequester.requestFocus()
        else keyboard?.hide()
    }

    Column(
        Modifier.fillMaxSize().background(Pal.Paper).safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .imePadding(),
    ) {
        Scoreboard(state)
        Spacer(Modifier.height(8.dp))
        StatusLine(state)
        Spacer(Modifier.height(10.dp))

        when (state.phase) {
            KeymashPhase.SETUP -> SetupBody(state, onChoose)
            KeymashPhase.COUNTDOWN -> CountdownBody(state)
            KeymashPhase.PLAYING -> RaceBody(state, onField, onSubmitWord, focusRequester)
            KeymashPhase.OVER -> ResultBody(state)
        }

        Spacer(Modifier.height(10.dp))
        Footer(state, onRematch, onLeave)
    }
}

// ---- header -------------------------------------------------------------------------

@Composable
private fun Scoreboard(s: KeymashUi) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Tally(
            name = s.myNick, score = s.myScore, sub = statLine(s.liveWpm, s.liveAcc, s.myWpm, s.myAcc, s.myDone),
            color = Pal.paint(s.mySide), align = Alignment.Start,
        )
        Tally(
            name = s.theirNick, score = s.theirScore, sub = statLine(s.theirWpm, null, s.theirWpm, null, s.theyDone),
            color = Pal.paint(s.mySide.other()), align = Alignment.End,
        )
    }
}

@Composable
private fun statLine(liveWpm: Int, liveAcc: Int?, finalWpm: Int, finalAcc: Int?, done: Boolean): String {
    @Composable
    fun wpm(w: Int, a: Int?): String =
        stringResource(R.string.key_wpm, w) + (a?.let { stringResource(R.string.key_acc_suffix, it) } ?: "")
    return if (done && (finalWpm > 0 || (finalAcc ?: 0) > 0)) {
        wpm(finalWpm, finalAcc)
    } else if (liveWpm > 0 || (liveAcc ?: 0) > 0) {
        wpm(liveWpm, liveAcc)
    } else stringResource(R.string.key_typing)
}

@Composable
private fun Tally(name: String, score: Int, sub: String, color: androidx.compose.ui.graphics.Color, align: Alignment.Horizontal) {
    Column(horizontalAlignment = align, modifier = Modifier.padding(horizontal = 2.dp)) {
        Text(
            name.ifBlank { stringResource(R.string.common_player) }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            color = color, maxLines = 1,
        )
        Text("$score", fontSize = 34.sp, fontWeight = FontWeight.Black, color = Pal.Ink)
        Text(sub, fontSize = 12.sp, color = Pal.Muted)
    }
}

@Composable
private fun StatusLine(s: KeymashUi) {
    val themLow = stringResource(R.string.common_them_low)
    val opponent = stringResource(R.string.common_opponent)
    val (text, color) = when (s.phase) {
        KeymashPhase.SETUP -> if (s.iChoose) stringResource(R.string.key_pick_you) to Pal.Ink
        else stringResource(R.string.key_pick_wait, s.theirNick.ifBlank { opponent }) to Pal.Ink
        KeymashPhase.COUNTDOWN -> stringResource(R.string.key_ready) to Pal.Ink
        KeymashPhase.PLAYING -> when {
            s.myDone && !s.theyDone -> stringResource(R.string.key_finished, s.theirNick.ifBlank { themLow }) to Pal.Muted
            s.myDone -> stringResource(R.string.key_time) to Pal.Ink
            else -> when (s.mode) {
                KeymashMode.WORDS -> stringResource(R.string.key_words_mode) to Pal.Ink
                KeymashMode.SENTENCE -> stringResource(R.string.key_sentence, s.index + 1, s.targets.size) to Pal.Ink
                null -> "" to Pal.Ink
            }
        }
        KeymashPhase.OVER -> when (s.outcome) {
            KeymashResult.WIN -> stringResource(R.string.common_you_win) to Pal.paint(s.mySide)
            KeymashResult.LOSS -> Wording.subject(s.theirNick, R.string.subj_wins_named, R.string.subj_wins_anon) to Pal.paint(s.mySide.other())
            KeymashResult.DRAW -> stringResource(R.string.common_draw) to Pal.Ink
            null -> "" to Pal.Ink
        }
    }
    Crossfade(targetState = text to color, animationSpec = tween(220), label = "status") { (t, c) ->
        Text(t, fontSize = 22.sp, fontWeight = FontWeight.Black, color = c)
    }
}

// ---- setup + countdown ------------------------------------------------------------------

@Composable
private fun SetupBody(s: KeymashUi, onChoose: (KeymashMode) -> Unit) {
    if (s.iChoose) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.key_what), fontSize = 15.sp, color = Pal.Muted)
            RaisedButton(
                text = stringResource(R.string.key_mode_sent),
                onClick = { onChoose(KeymashMode.SENTENCE) },
                tone = Tone.Primary,
                modifier = Modifier.fillMaxWidth(),
            )
            RaisedButton(
                text = stringResource(R.string.key_mode_words),
                onClick = { onChoose(KeymashMode.WORDS) },
                tone = Tone.Primary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    } else {
        RaisedCard(Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.key_choosing, s.theirNick.ifBlank { stringResource(R.string.common_opponent_cap) }),
                fontSize = 16.sp, color = Pal.Muted,
            )
        }
    }
}

@Composable
private fun CountdownBody(s: KeymashUi) {
    RaisedCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(28.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (s.countdownLeft <= 0) stringResource(R.string.key_go) else "${s.countdownLeft}",
                fontSize = 72.sp, fontWeight = FontWeight.Black, color = Pal.Teal,
            )
            Text(
                if (s.mode == KeymashMode.WORDS) stringResource(R.string.key_words_sub) else stringResource(R.string.key_sent_sub),
                fontSize = 14.sp, color = Pal.Muted,
            )
        }
    }
}

// ---- the race -----------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RaceBody(
    s: KeymashUi,
    onField: (String) -> Unit,
    onSubmitWord: () -> Unit,
    focusRequester: FocusRequester,
) {
    val fieldCallback by rememberUpdatedState(onField)
    val submitCallback by rememberUpdatedState(onSubmitWord)

    if (s.mode == KeymashMode.WORDS) {
        RaisedCard(Modifier.fillMaxWidth()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                s.targets.forEachIndexed { i, word ->
                    val style = when {
                        i < s.index -> if (s.wordOk.getOrElse(i) { true }) {
                            FontWeight.Normal to Pal.Teal
                        } else {
                            FontWeight.Normal to Pal.Red
                        }
                        i == s.index -> FontWeight.Bold to Pal.Ink
                        else -> FontWeight.Normal to Pal.Muted
                    }
                    Text(
                        word, fontSize = 18.sp, fontWeight = style.first, color = style.second,
                        textDecoration = if (i == s.index) TextDecoration.Underline else null,
                        modifier = if (i == s.index) {
                            Modifier.clip(RoundedCornerShape(6.dp))
                                .background(Pal.Teal.copy(alpha = 0.12f))
                                .padding(horizontal = 4.dp)
                        } else Modifier,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    } else if (s.targets.isNotEmpty() && s.index < s.targets.size) {
        RaisedCard(Modifier.fillMaxWidth()) {
            Text(sentenceSpans(s.targets[s.index], s.field), fontSize = 18.sp, lineHeight = 26.sp)
        }
        Spacer(Modifier.height(10.dp))
    }

    // A typing race wants raw fingers, not help: `autoCorrectEnabled = false` alone is
    // not enough (this Compose version only withholds FLAG_AUTO_CORRECT, it never sends
    // FLAG_NO_SUGGESTIONS), so request the password layout. Every major keyboard answers
    // that with no autocorrect, no suggestion strip, no glide typing and no learning.
    // The text itself stays visible: masking comes from SecureTextField composables, not
    // from the input type. Side effect, same for both players: keyboards ignore the
    // sentence auto-capitalization request on this layout.
    val target = s.targets.getOrNull(s.index).orEmpty()
    val fieldOk = s.mode != KeymashMode.WORDS || target.startsWith(s.field)
    TextField(
        value = s.field,
        onValueChange = fieldCallback,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = if (s.mode == KeymashMode.SENTENCE) {
                KeyboardCapitalization.Sentences
            } else {
                KeyboardCapitalization.None
            },
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { submitCallback() }),
        textStyle = androidx.compose.ui.text.TextStyle(
            color = if (fieldOk) Pal.Ink else Pal.Red, fontSize = 18.sp,
        ),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Pal.Groove,
            unfocusedContainerColor = Pal.Groove,
            focusedIndicatorColor = Pal.Teal,
            unfocusedIndicatorColor = Pal.Line,
            cursorColor = Pal.Teal,
        ),
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
    )

    Spacer(Modifier.height(10.dp))
    // Live progress on both sides: dots for words, a bar for the rival.
    OpponentProgress(s)
}

@Composable
private fun OpponentProgress(s: KeymashUi) {
    val total = s.theirTotal.coerceAtLeast(1)
    RaisedCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    s.theirNick.ifBlank { stringResource(R.string.common_them) }, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, color = Pal.paint(s.mySide.other()),
                )
                Text(
                    stringResource(R.string.key_opp_progress, s.theirCompleted, s.theirTotal) +
                        if (s.theirWpm > 0) stringResource(R.string.key_opp_wpm, s.theirWpm) else "",
                    fontSize = 13.sp, color = Pal.Muted,
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (s.theirCompleted.toFloat() / total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = Pal.paint(s.mySide.other()),
                trackColor = Pal.EdgeShade,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.key_my_progress, s.index, s.targets.size) +
                    if (s.liveWpm > 0) stringResource(R.string.key_my_live, s.liveWpm, s.liveAcc) else "",
                fontSize = 13.sp, color = Pal.Muted,
            )
        }
    }
}

@Composable
private fun sentenceSpans(target: String, field: String): androidx.compose.ui.text.AnnotatedString =
    buildAnnotatedString {
        target.forEachIndexed { i, c ->
            val style = when {
                i >= field.length -> SpanStyle(color = Pal.Muted)
                field[i].equals(c, ignoreCase = true) -> SpanStyle(color = Pal.Ink)
                else -> SpanStyle(color = Pal.Red)
            }
            pushStyle(style)
            append(c)
            pop()
        }
        // Overflow past the end of the sentence is always wrong.
        if (field.length > target.length) {
            pushStyle(SpanStyle(color = Pal.Red))
            append(field.substring(target.length))
            pop()
        }
    }

// ---- result -----------------------------------------------------------------------------------

@Composable
private fun ResultBody(s: KeymashUi) {
    RaisedCard(Modifier.fillMaxWidth()) {
        Column(horizontalAlignment = Alignment.Start) {
            Text(
                stringResource(R.string.key_my_result, s.myScore, s.myWpm, s.myAcc),
                fontSize = 20.sp, fontWeight = FontWeight.Black, color = Pal.paint(s.mySide),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(
                    R.string.key_their_result,
                    s.theirNick.ifBlank { stringResource(R.string.common_them) },
                    s.theirScore, s.theirWpm,
                ),
                fontSize = 16.sp, color = Pal.paint(s.mySide.other()),
            )
        }
    }
}

// ---- footer --------------------------------------------------------------------------------------

@Composable
private fun Footer(s: KeymashUi, onRematch: () -> Unit, onLeave: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        if (s.phase == KeymashPhase.OVER) {
            Text(
                stringResource(
                    R.string.key_match,
                    s.myWins + s.theirWins + s.draws, s.myWins,
                    s.theirNick.ifBlank { stringResource(R.string.common_them_low) },
                    s.theirWins, s.draws,
                ),
                fontSize = 14.sp, color = Pal.Muted,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
            )
            if (s.theyWantRematch && !s.iWantRematch) {
                Text(
                    Wording.subject(s.theirNick, R.string.subj_wants_named, R.string.subj_wants_anon),
                    fontSize = 15.sp, color = Pal.Muted,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                )
            }
            RaisedButton(
                text = if (s.iWantRematch) {
                    stringResource(
                        R.string.common_waiting_rematch,
                        s.theirNick.ifBlank { stringResource(R.string.common_opponent) },
                    )
                } else stringResource(R.string.common_play_again),
                onClick = onRematch,
                enabled = !s.iWantRematch,
                tone = Tone.Primary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        TextButton(onClick = onLeave) {
            Text(stringResource(R.string.common_leave), color = Pal.Muted, fontWeight = FontWeight.SemiBold)
        }
    }
}
