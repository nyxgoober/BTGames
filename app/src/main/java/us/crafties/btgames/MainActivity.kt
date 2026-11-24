package us.crafties.btgames

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import us.crafties.btgames.net.Link
import us.crafties.btgames.net.UpdateStatus
import us.crafties.btgames.shell.GameRegistry
import us.crafties.btgames.shell.Screen
import us.crafties.btgames.shell.ShellViewModel
import us.crafties.btgames.ui.AppLang
import us.crafties.btgames.ui.AppScreen
import us.crafties.btgames.ui.BtGamesTheme
import us.crafties.btgames.ui.ChooseFirstDialog
import us.crafties.btgames.ui.DebugScreen
import us.crafties.btgames.ui.IncomingDialog
import us.crafties.btgames.ui.LANDSCAPE
import us.crafties.btgames.ui.LobbyScreen
import us.crafties.btgames.ui.MenuScreen
import us.crafties.btgames.ui.NameScreen
import us.crafties.btgames.ui.OrientationEffect
import us.crafties.btgames.ui.PORTRAIT
import us.crafties.btgames.ui.Pal
import us.crafties.btgames.ui.RaisedButton
import us.crafties.btgames.ui.SettingsScreen
import us.crafties.btgames.ui.SystemBarsEffect
import us.crafties.btgames.ui.Tone
import us.crafties.btgames.ui.SelectModeScreen
import us.crafties.btgames.ui.RoomScreen

class MainActivity : ComponentActivity() {

    private val vm: ShellViewModel by viewModels()

    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("btgames", Context.MODE_PRIVATE)
        super.attachBaseContext(
            AppLang.wrap(newBase, prefs.getString(AppLang.KEY, AppLang.SYSTEM).orEmpty())
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The ViewModel's init applies the saved dark mode and font to Pal/AppType. Create it
        // before the first composition, or BtGamesTheme draws one frame with the defaults and
        // the text/screen flashes when they change.
        vm.applyAppearance()
        enableEdgeToEdge()
        setContent { BtGamesTheme { Root(vm) } }
    }
}

/** Permissions the current Android version needs before we may advertise, scan or connect. */
private fun requiredPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

@Composable
private fun Root(vm: ShellViewModel) {
    val context = LocalContext.current
    val screen by vm.screen.collectAsStateWithLifecycle()
    val game by vm.game.collectAsStateWithLifecycle()
    val nick by vm.nick.collectAsStateWithLifecycle()
    val link by vm.link.collectAsStateWithLifecycle()
    val peers by vm.peers.collectAsStateWithLifecycle()
    val room by vm.room.collectAsStateWithLifecycle()
    val tableMode by vm.tableMode.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val dark by vm.dark.collectAsStateWithLifecycle()
    val vibration by vm.vibration.collectAsStateWithLifecycle()
    val screenMode by vm.screenMode.collectAsStateWithLifecycle()
    val menuPage by vm.menuPage.collectAsStateWithLifecycle()
    val language by vm.language.collectAsStateWithLifecycle()
    val font by vm.font.collectAsStateWithLifecycle()
    val updateChecks by vm.updateChecks.collectAsStateWithLifecycle()
    val updateStatus by vm.update.collectAsStateWithLifecycle()
    val loopbackEnabled by vm.loopbackEnabled.collectAsStateWithLifecycle()
    val reportedVersion by vm.reportedVersion.collectAsStateWithLifecycle()

    // The ViewModel nulls the session the instant a game ends, but the game screen is still fading
    // out for a few hundred ms. Keep drawing the last one so leaving a game does not flash blank.
    var lastSession by remember { mutableStateOf(session) }
    if (session != null) lastSession = session
    var lastGame by remember { mutableStateOf(game) }
    if (game != null) lastGame = game

    var permissionDenied by remember { mutableStateOf(false) }
    var btOff by remember { mutableStateOf(false) }

    // A denial belongs to one attempt. Back on the menu, start clean so picking another game
    // asks again instead of dropping the user on a stale "One more step" screen.
    LaunchedEffect(screen) {
        if (screen == Screen.MENU) {
            permissionDenied = false
            btOff = false
        }
    }
    val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val on = BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
        btOff = !on
        if (on) vm.enterLobby()
    }

    fun bluetoothThenLobby() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter?.isEnabled == true) {
            btOff = false
            vm.enterLobby()
        } else {
            btOff = true
            enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }

    val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) {
            permissionDenied = false
            bluetoothThenLobby()
        } else {
            permissionDenied = true
        }
    }

    /**
     * The single way into a lobby, used by both the menu and the name screen:
     * permissions -> Bluetooth on -> enter lobby and start searching.
     */
    fun startLobbyFlow() {
        val missing = requiredPermissions().any {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing) askPermissions.launch(requiredPermissions()) else bluetoothThenLobby()
    }

    // Menus are portrait; a game asks for landscape only while it is being played.
    // The table screen is always landscape: it is a tablet or TV standing in the middle of the table.
    val landscape = screen == Screen.PLAYING && ((game ?: lastGame)?.landscape == true || tableMode)
    OrientationEffect(if (landscape) LANDSCAPE else PORTRAIT)

    // The clock and battery icons must be readable on whatever is behind them. Every screen except
    // a game has the cyan title bar at the top, which wants white icons; so does the dark theme and
    // the Paddle court (dark in both themes). Only a game on the light theme (XO) wants dark ones.
    SystemBarsEffect(lightIcons = dark || screen != Screen.PLAYING || landscape)

    // Back never silently kills a running game or a lobby; it walks one step up.
    BackHandler(enabled = screen != Screen.MENU) {
        when (screen) {
            Screen.PLAYING -> vm.leaveGame()
            Screen.SETTINGS -> vm.closeSettings()
            Screen.ROOM -> vm.leaveRoom()
            Screen.LOBBY -> vm.backFromLobby()
            else -> vm.backToMenu()
        }
    }

    AnimatedContent(
        targetState = screen,
        transitionSpec = { screenTransition(initialState, targetState) },
        label = "screen",
    ) { shown ->
    when (shown) {
        Screen.MENU -> MenuScreen(
            games = GameRegistry.all,
            onPick = { def ->
                // With a saved name, go straight in. Without one, pickGame() moves to the name screen.
                if (vm.pickGame(def) && vm.hasNick) startLobbyFlow()
            },
            screenMode = screenMode,
            page = menuPage,
            onPage = vm::setMenuPage,
            onSettings = vm::openSettings,
            onCodenameTap = vm::tapCodename,
            updateAvailable = updateStatus is UpdateStatus.Available,
            onUpdateTap = vm::openSettings,
        )

        Screen.SETTINGS -> SettingsScreen(
            dark = dark,
            vibration = vibration,
            screenMode = screenMode,
            onScreenMode = vm::setScreenMode,
            onDark = vm::setDark,
            onVibration = vm::setVibration,
            language = language,
            onLanguage = {
                vm.setLanguage(it)
                (context as? Activity)?.recreate()
            },
            font = font,
            onFont = vm::setFont,
            updateChecks = updateChecks,
            onUpdateChecks = vm::setUpdateChecks,
            updateStatus = updateStatus,
            onCheckUpdates = vm::checkForUpdates,
            onOpenDownload = { url ->
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: ActivityNotFoundException) {
                    // No browser installed: the download button simply does nothing.
                }
            },
            onBack = vm::closeSettings,
            onCodenameTap = vm::tapCodename,
        )

        Screen.DEBUG -> DebugScreen(
            testMode = loopbackEnabled,
            onTestMode = vm::setLoopback,
            reportedVersion = reportedVersion,
            onReportedVersion = vm::setReportedVersion,
            onBack = vm::closeDebug,
        )

        Screen.NAME -> {
            val gameTitle = game?.let { stringResource(it.titleRes) }.orEmpty()
            if (permissionDenied || btOff) {
                BlockedNotice(
                    message = if (permissionDenied) stringResource(R.string.blocked_bt_perm)
                    else stringResource(R.string.blocked_bt_off),
                    onRetry = { startLobbyFlow() },
                )
            } else {
                NameScreen(
                    gameTitle = gameTitle,
                    nick = nick,
                    onNick = vm::setNick,
                    onContinue = { startLobbyFlow() },
                    onBack = vm::backToMenu,
                )
            }
        }

        Screen.SELECT_MODE -> {
            val g = game ?: lastGame
            SelectModeScreen(
                gameTitle = g?.let { stringResource(it.titleRes) }.orEmpty(),
                minPlayers = g?.minPlayers ?: 2,
                maxPlayers = g?.maxPlayers ?: 2,
                onTwoPlayer = vm::startTwoPlayer,
                onCreateRoom = vm::startRoom,
                onScreen = if (g?.supportsScreen == true) vm::startScreenRoom else null,
                onJoinRoom = vm::joinRoom,
                onBack = vm::backToMenu,
            )
        }

        Screen.ROOM -> {
            val gameTitle = game?.let { stringResource(it.titleRes) }.orEmpty()
            RoomScreen(
                gameTitle = gameTitle,
                room = room,
                tableMode = tableMode,
                rooms = peers,
                onJoin = vm::connectTo,
                onStart = vm::startMatch,
                onRescan = vm::rescanRooms,
                onBack = vm::leaveRoom,
            )
        }

        Screen.LOBBY -> {
            val gameTitle = game?.let { stringResource(it.titleRes) }.orEmpty()
            if (permissionDenied || btOff) {
                BlockedNotice(
                    message = if (permissionDenied) stringResource(R.string.blocked_bt_perm)
                    else stringResource(R.string.blocked_bt_off),
                    onRetry = { startLobbyFlow() },
                )
            } else {
                LobbyScreen(
                    gameTitle = gameTitle,
                    myNick = nick,
                    link = link,
                    peers = peers,
                    notice = notice,
                    onPeer = vm::connectTo,
                    onRescan = vm::search,
                    onChangeName = vm::changeName,
                    onBack = vm::backFromLobby,
                )
                when (val l = link) {
                    // Someone asked us and we did not ask them: ordinary accept / decline.
                    is Link.Incoming -> IncomingDialog(from = l.peerNick, onAccept = vm::accept, onDecline = vm::decline)
                    // We asked each other at the same moment: no accept needed, just pick who starts.
                    is Link.Choosing -> ChooseFirstDialog(peerNick = l.peerNick, onChoose = vm::chooseFirst)
                    else -> Unit
                }
            }
        }

        Screen.PLAYING -> (session ?: lastSession)?.Content(onLeave = vm::leaveGame)
    }
    }
}

@Composable
private fun BlockedNotice(message: String, onRetry: () -> Unit) {
    AppScreen(title = stringResource(R.string.blocked_title)) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text(message, fontSize = 18.sp, color = Pal.Ink, lineHeight = 26.sp, modifier = Modifier.padding(bottom = 20.dp))
            RaisedButton(stringResource(R.string.blocked_continue), onClick = onRetry, tone = Tone.Primary, modifier = Modifier.fillMaxWidth())
        }
    }
}


/**
 * Screens are a depth ladder: MENU < SETTINGS < NAME < LOBBY < PLAYING. Moving deeper slides the new page in
 * from the right while the old one drifts left and fades; moving back reverses. Entering a match is
 * a zoom instead of a slide, because the court is a different world, not the next page.
 */
private fun screenTransition(from: Screen, to: Screen): ContentTransform {
    val ease = FastOutSlowInEasing
    val enteringGame = to == Screen.PLAYING
    val leavingGame = from == Screen.PLAYING
    if (enteringGame || leavingGame) {
        return (fadeIn(tween(320, easing = ease)) + scaleIn(tween(320, easing = ease), initialScale = if (enteringGame) 0.92f else 1.06f)) togetherWith
            (fadeOut(tween(220, easing = ease)) + scaleOut(tween(220, easing = ease), targetScale = if (enteringGame) 1.06f else 0.92f))
    }
    val forward = to.ordinal > from.ordinal
    val dir = if (forward) 1 else -1
    return (slideInHorizontally(tween(340, easing = ease)) { (it * 0.28f).toInt() * dir } + fadeIn(tween(340, easing = ease))) togetherWith
        (slideOutHorizontally(tween(340, easing = ease)) { -(it * 0.18f).toInt() * dir } + fadeOut(tween(220, easing = ease)))
}
