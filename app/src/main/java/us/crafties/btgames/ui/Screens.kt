package us.crafties.btgames.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import us.crafties.btgames.R
import us.crafties.btgames.net.Link
import us.crafties.btgames.net.Peer
import us.crafties.btgames.net.RoomMember
import us.crafties.btgames.net.RoomState
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.border

/** Pick the name other players will see. Shown before a game's lobby if no name is saved yet. */

/**
 * Shown only for games that also support a multiplayer room (maxPlayers > 2). Every other game
 * skips straight to the two-player lobby, so this screen is how a player chooses between the two.
 */
@Composable
fun SelectModeScreen(
    gameTitle: String,
    minPlayers: Int,
    maxPlayers: Int,
    onTwoPlayer: () -> Unit,
    onCreateRoom: () -> Unit,
    onJoinRoom: () -> Unit,
    onBack: () -> Unit,
    /** Non-null when the game has a table screen mode (a tablet or TV hosting in the middle). */
    onScreen: (() -> Unit)? = null,
) {
    AppScreen(title = gameTitle, onBack = onBack) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                stringResource(R.string.mode_heading, gameTitle),
                fontSize = 24.sp, fontWeight = FontWeight.Black, color = Pal.Ink, lineHeight = 30.sp,
            )
            Spacer(Modifier.height(20.dp))
            ModeCard(stringResource(R.string.mode_two), stringResource(R.string.mode_two_desc), onTwoPlayer)
            Spacer(Modifier.height(14.dp))
            ModeCard(
                stringResource(R.string.mode_create),
                stringResource(R.string.mode_create_desc, minPlayers, maxPlayers),
                onCreateRoom,
            )
            Spacer(Modifier.height(14.dp))
            ModeCard(stringResource(R.string.mode_join), stringResource(R.string.mode_join_desc), onJoinRoom)
            if (onScreen != null) {
                Spacer(Modifier.height(14.dp))
                ModeCard(
                    stringResource(R.string.mode_screen),
                    stringResource(R.string.mode_screen_desc, minPlayers, maxPlayers),
                    onScreen,
                )
            }
        }
    }
}

@Composable
private fun ModeCard(title: String, description: String, onClick: () -> Unit) {
    RaisedSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radii.Card),
        elevation = 8.dp,
        contentPadding = PaddingValues(18.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Black, color = Pal.Ink)
            Spacer(Modifier.height(4.dp))
            Text(description, fontSize = 15.sp, color = Pal.Muted, lineHeight = 21.sp)
        }
    }
}

/**
 * The room lobby. Hosting: the roster so far and a Start button. Joined: the roster and a wait
 * message. Neither (still looking): the rooms found nearby, tap one to join.
 */
@Composable
fun RoomScreen(
    gameTitle: String,
    room: RoomState,
    /** This phone opened the room as the table screen: it is in the roster but is not a player, so it is left out of the counts. */
    tableMode: Boolean = false,
    rooms: List<Peer>,
    onJoin: (Peer) -> Unit,
    onStart: () -> Unit,
    onRescan: () -> Unit,
    onBack: () -> Unit,
) {
    AppScreen(title = gameTitle, onBack = onBack) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
            when (room) {
                is RoomState.Hosting -> {
                    val skip = if (tableMode) 1 else 0
                    val players = room.members.size - skip
                    val minPlayers = room.min - skip
                    Text(
                        stringResource(if (tableMode) R.string.room_screen_title else R.string.room_host_title),
                        fontSize = 24.sp, fontWeight = FontWeight.Black, color = Pal.Ink,
                    )
                    Text(
                        stringResource(R.string.room_count, players, room.max - skip),
                        fontSize = 16.sp, color = Pal.Muted,
                    )
                    Spacer(Modifier.height(12.dp))
                    Roster(room.members.drop(skip), Modifier.weight(1f))
                    if (players < minPlayers) {
                        Text(stringResource(R.string.room_need_more, minPlayers), fontSize = 15.sp, color = Pal.Muted)
                        Spacer(Modifier.height(10.dp))
                    }
                    RaisedButton(
                        stringResource(R.string.room_start), onClick = onStart,
                        tone = Tone.Primary, enabled = players >= minPlayers,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                is RoomState.Joined -> {
                    Text(
                        stringResource(R.string.room_joined_wait, room.hostNick),
                        fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Pal.Ink, lineHeight = 28.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.room_count, room.members.size, room.max),
                        fontSize = 16.sp, color = Pal.Muted,
                    )
                    Spacer(Modifier.height(12.dp))
                    Roster(room.members, Modifier.weight(1f))
                }
                RoomState.None -> {
                    Text(stringResource(R.string.mode_join), fontSize = 24.sp, fontWeight = FontWeight.Black, color = Pal.Ink)
                    Spacer(Modifier.height(12.dp))
                    if (rooms.isEmpty()) {
                        Text(stringResource(R.string.room_looking), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Pal.Ink)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.room_none_hint, gameTitle),
                            fontSize = 15.sp, color = Pal.Muted, lineHeight = 22.sp,
                        )
                        Spacer(Modifier.weight(1f))
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                            modifier = Modifier.weight(1f).sideBleed(24.dp),
                        ) {
                            items(rooms, key = { it.id }) { p -> PeerRow(p, onClick = { onJoin(p) }) }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    RaisedButton(stringResource(R.string.lobby_search), onClick = onRescan, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun Roster(members: List<RoomMember>, modifier: Modifier = Modifier) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 6.dp),
        modifier = modifier.sideBleed(24.dp),
    ) {
        items(members, key = { it.seat }) { m ->
            RaisedSurface(
                onClick = {},
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Radii.Control),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(Pal.Teal))
                    Spacer(Modifier.width(14.dp))
                    Text(
                        m.nick.ifBlank { stringResource(R.string.lobby_unnamed) } +
                            if (m.you) "  (${stringResource(R.string.stb_you)})" else "",
                        color = Pal.Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun NameScreen(
    gameTitle: String,
    nick: String,
    onNick: (String) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    AppScreen(title = stringResource(R.string.name_title), onBack = onBack) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text(stringResource(R.string.name_heading), fontSize = 26.sp, fontWeight = FontWeight.Black, color = Pal.Ink)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.name_desc, gameTitle), fontSize = 16.sp, color = Pal.Muted, lineHeight = 23.sp)
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.name_label), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Pal.InkSoft)
            Spacer(Modifier.height(8.dp))
            GrooveTextField(
                value = nick,
                onValueChange = onNick,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (nick.isNotBlank()) onContinue() }),
            )
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.name_hint), fontSize = 14.sp, color = Pal.Muted, lineHeight = 20.sp)
            Spacer(Modifier.weight(1f))
            RaisedButton(
                stringResource(R.string.name_find, gameTitle),
                onClick = onContinue, tone = Tone.Primary, enabled = nick.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Who is nearby, waiting in the same game? Tap a name to send a request. */
@Composable
fun LobbyScreen(
    gameTitle: String,
    myNick: String,
    link: Link,
    peers: List<Peer>,
    notice: String?,
    onPeer: (Peer) -> Unit,
    onRescan: () -> Unit,
    onChangeName: () -> Unit,
    onBack: () -> Unit,
) {
    AppScreen(title = gameTitle, onBack = onBack) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.lobby_playing_as, myNick), fontSize = 16.sp, color = Pal.Muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onChangeName) { Text(stringResource(R.string.lobby_change_name), color = Pal.Ink, fontWeight = FontWeight.Bold) }
            }

            Spacer(Modifier.height(8.dp))

            if (notice != null) {
                Groove(Modifier.fillMaxWidth()) { Text(notice, color = Pal.Ink, fontSize = 15.sp, lineHeight = 21.sp) }
                Spacer(Modifier.height(16.dp))
            }

            val opponent = stringResource(R.string.common_opponent)
            when (link) {
                is Link.Requesting -> Waiting(
                    stringResource(R.string.lobby_asking, link.peerNick.ifBlank { opponent }),
                    stringResource(R.string.lobby_wait_accept),
                )
                // Both phones asked each other: no accept needed. The chooser sees a dialog; the other waits.
                is Link.Choosing -> Waiting(
                    stringResource(R.string.lobby_mutual, link.peerNick.ifBlank { opponent }),
                    stringResource(R.string.lobby_choose_first),
                )
                is Link.WaitingForChoice -> Waiting(
                    stringResource(R.string.lobby_mutual, link.peerNick.ifBlank { opponent }),
                    stringResource(R.string.lobby_wait_choice),
                )
                is Link.Ready -> Waiting(stringResource(R.string.lobby_starting), null)
                else -> {
                    Text(stringResource(R.string.lobby_nearby), fontSize = 20.sp, fontWeight = FontWeight.Black, color = Pal.Ink)
                    Spacer(Modifier.height(12.dp))
                    if (peers.isEmpty()) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.Start) {
                            Text(stringResource(R.string.lobby_empty), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Pal.Ink)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(R.string.lobby_empty_hint, gameTitle),
                                color = Pal.Muted, fontSize = 15.sp, lineHeight = 22.sp,
                            )
                        }
                    } else {
                        // Padding on the list itself, so the rows' shadows are not clipped at its edges.
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                            modifier = Modifier.weight(1f).sideBleed(24.dp),
                        ) {
                            items(peers, key = { it.id }) { p -> PeerRow(p, onClick = { onPeer(p) }) }
                        }
                    }
                    if (peers.isEmpty()) Spacer(Modifier.weight(1f))
                    Spacer(Modifier.height(12.dp))
                    RaisedButton(stringResource(R.string.lobby_search), onClick = onRescan, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun PeerRow(p: Peer, onClick: () -> Unit) {
    RaisedSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radii.Control),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(Pal.Teal))
            Spacer(Modifier.width(14.dp))
            Text(
                p.nick.ifBlank { stringResource(R.string.lobby_unnamed) },
                color = Pal.Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(signalWord(p.rssi), color = Pal.Muted, fontSize = 14.sp)
        }
    }
}

@Composable
private fun signalWord(rssi: Int): String = when {
    rssi >= -60 -> stringResource(R.string.signal_close)
    rssi >= -75 -> stringResource(R.string.signal_near)
    else -> stringResource(R.string.signal_far)
}

@Composable
private fun Waiting(title: String, hint: String?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp)) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Pal.Ink, lineHeight = 28.sp)
        if (hint != null) {
            Spacer(Modifier.height(6.dp))
            Text(hint, fontSize = 16.sp, color = Pal.Muted)
        }
    }
}

/** Shown on top of the lobby when someone else has asked to play. */
@Composable
fun IncomingDialog(from: String, onAccept: () -> Unit, onDecline: () -> Unit) {
    val someone = stringResource(R.string.common_someone)
    RaisedDialog(title = stringResource(R.string.dlg_incoming, from.ifBlank { someone }), onDismissRequest = onDecline) {
        Text(stringResource(R.string.dlg_incoming_body), fontSize = 16.sp, color = Pal.Ink, lineHeight = 22.sp)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RaisedButton(stringResource(R.string.dlg_decline), onClick = onDecline, modifier = Modifier.weight(1f))
            RaisedButton(stringResource(R.string.dlg_accept), onClick = onAccept, tone = Tone.Primary, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * Both phones asked each other at the same moment, which counts as both accepting. This phone was
 * picked to decide who goes first. It cannot be dismissed by tapping outside: the other player is
 * waiting on this answer, so the choice has to be an explicit tap.
 */
@Composable
fun ChooseFirstDialog(peerNick: String, onChoose: (iPlayFirst: Boolean) -> Unit) {
    val them = peerNick.ifBlank { stringResource(R.string.common_opponent) }
    RaisedDialog(title = stringResource(R.string.dlg_first_title), onDismissRequest = {}, dismissible = false) {
        Text(
            stringResource(R.string.dlg_first_body, them),
            fontSize = 16.sp, color = Pal.Ink, lineHeight = 22.sp,
        )
        Spacer(Modifier.height(10.dp))
        RaisedButton(stringResource(R.string.dlg_i_first), onClick = { onChoose(true) }, tone = Tone.Primary, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(2.dp))
        RaisedButton(stringResource(R.string.dlg_they_first, them.replaceFirstChar { it.uppercase() }), onClick = { onChoose(false) }, modifier = Modifier.fillMaxWidth())
    }
}
