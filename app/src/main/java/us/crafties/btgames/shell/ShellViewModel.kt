package us.crafties.btgames.shell

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import us.crafties.btgames.BuildConfig
import us.crafties.btgames.debug.makeBot
import us.crafties.btgames.net.Codec
import us.crafties.btgames.net.GameIds
import us.crafties.btgames.net.GithubUpdateCheck
import us.crafties.btgames.net.Transport
import us.crafties.btgames.net.Link
import us.crafties.btgames.net.GattTransport
import us.crafties.btgames.net.LoopbackTransport
import us.crafties.btgames.net.Peer
import us.crafties.btgames.net.Proto
import us.crafties.btgames.net.RoomState
import us.crafties.btgames.net.UpdateCheck
import us.crafties.btgames.net.UpdateStatus
import us.crafties.btgames.ui.AppLang
import us.crafties.btgames.ui.AppType
import us.crafties.btgames.ui.Haptics
import us.crafties.btgames.ui.Pal

/**
 * Where the user is in the app.
 *
 *   MENU     - pick a game (first screen)
 *   SETTINGS - dark mode and vibration; opened from the menu
 *   NAME     - choose the name others see (only shown if no name is saved yet)
 *   SELECT_MODE - two players or a multiplayer room (only for games with maxPlayers > 2)
 *   LOBBY    - waiting in a game's lobby, looking for other players in that same game
 *   ROOM     - an N-player room lobby: hosting, or looking for / waiting in someone else's
 *   PLAYING  - a match is running
 *   DEBUG    - the secret debug menu (five taps on the codename)
 */
enum class Screen { MENU, SETTINGS, NAME, SELECT_MODE, LOBBY, ROOM, PLAYING, DEBUG }

class ShellViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("btgames", Context.MODE_PRIVATE)

    /** Debug override for the reported wire version (null = this build). Remembered. */
    private val _reportedVersion = MutableStateFlow(
        prefs.getInt(KEY_REPORTED_VERSION, -1).takeIf { it >= 0 },
    )
    val reportedVersion: StateFlow<Int?> = _reportedVersion.asStateFlow()

    val transport: Transport = GattTransport(app) { _reportedVersion.value ?: Proto.VERSION }

    /** Test mode's radio-free transport. Idle unless the TestMode peer was tapped. */
    private val loopback = LoopbackTransport(viewModelScope).also {
        it.botFactory = ::makeBot
    }

    private val _screen = MutableStateFlow(Screen.MENU)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _nick = MutableStateFlow(prefs.getString(KEY_NICK, "").orEmpty())
    val nick: StateFlow<String> = _nick.asStateFlow()

    /**
     * Dark mode. The first time the app runs it follows the phone's own setting; after that it is
     * whatever the user chose here. The colours themselves live in [Pal], which reads [Pal.dark].
     */
    private val _dark = MutableStateFlow(prefs.getBoolean(KEY_DARK, systemIsDark(app)))
    val dark: StateFlow<Boolean> = _dark.asStateFlow()

    /**
     * Screen mode (Settings): this device is the big screen in the middle of the table. Picking a
     * multiplayer game goes straight to hosting it as the table screen, with no mode choice;
     * games that are only for two players do not work in this mode.
     */
    private val _screenMode = MutableStateFlow(prefs.getBoolean(KEY_SCREEN_MODE, false))
    val screenMode: StateFlow<Boolean> = _screenMode.asStateFlow()

    /** Which menu category is showing: 0 = two players, 1 = multiplayer. Kept when a game is left. */
    private val _menuPage = MutableStateFlow(if (prefs.getBoolean(KEY_SCREEN_MODE, false)) 1 else 0)
    val menuPage: StateFlow<Int> = _menuPage.asStateFlow()
    fun setMenuPage(p: Int) { _menuPage.value = p }

    private val _vibration = MutableStateFlow(prefs.getBoolean(KEY_VIBRATION, true))
    val vibration: StateFlow<Boolean> = _vibration.asStateFlow()

    /** Automatic release update checks at startup. Remembered; on by default. */
    private val _updateChecks = MutableStateFlow(prefs.getBoolean(KEY_UPDATE_CHECKS, true))
    val updateChecks: StateFlow<Boolean> = _updateChecks.asStateFlow()

    /** The app's own language (BCP 47 tag, or "" for the system language). Remembered. */
    private val _language = MutableStateFlow(prefs.getString(AppLang.KEY, AppLang.SYSTEM).orEmpty())
    val language: StateFlow<String> = _language.asStateFlow()

    /** The typeface the app draws with ("intern" or "inter"). Remembered; Intern by default. */
    private val _font = MutableStateFlow(prefs.getString(KEY_FONT, AppType.FONT_INTERN).orEmpty())
    val font: StateFlow<String> = _font.asStateFlow()

    /** Latest release update-check outcome. */
    private val _update = MutableStateFlow<UpdateStatus>(UpdateStatus.Unknown)
    val update: StateFlow<UpdateStatus> = _update.asStateFlow()

    /** The secret debug menu, unlocked with five taps on the release codename. Remembered. */
    private val _debugUnlocked = MutableStateFlow(prefs.getBoolean(KEY_DEBUG_UNLOCKED, false))
    val debugUnlocked: StateFlow<Boolean> = _debugUnlocked.asStateFlow()

    /** Test mode: a practice bot appears in every lobby, playing random moves. Remembered. */
    private val _loopbackEnabled = MutableStateFlow(prefs.getBoolean(KEY_LOOPBACK, false))
    val loopbackEnabled: StateFlow<Boolean> = _loopbackEnabled.asStateFlow()

    private var codenameTaps = 0

    /** The game the user picked on the menu. Null while on the menu. */
    private val _game = MutableStateFlow<GameDef?>(null)
    val game: StateFlow<GameDef?> = _game.asStateFlow()

    private val _session = MutableStateFlow<GameSession?>(null)
    val session: StateFlow<GameSession?> = _session.asStateFlow()

    /** Why the last attempt ended ("The other player declined", ...). Cleared on the next search. */
    /** This phone opened its room as the table screen: it referees and shows the table but does not play. */
    private val _tableMode = MutableStateFlow(false)
    val tableMode: StateFlow<Boolean> = _tableMode

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /**
     * The loopback is only ever non-idle while a test match is being set up or played, so it
     * wins whenever it has anything to say and the radio wins the rest of the time.
     */
    val link: StateFlow<Link> = combine(transport.link, loopback.link) { real, loop ->
        if (loop is Link.Idle) real else loop
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Link.Idle)

    /**
     * Real neighbours plus the fake test-mode player, when test mode is on. Kept in sync eagerly:
     * `combine` updates on a dispatcher, which let the bot's name show one stale frame ("TestMode"
     * instead of the game's name) whenever the game or the screen changed during navigation.
     */
    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    /** Rebuild the peer list right away, so navigation never draws a stale bot nickname. */
    private fun refreshPeers() {
        val real = transport.peers.value
        _peers.value = if (_loopbackEnabled.value) {
            real + LoopbackTransport.fakePeer(_game.value?.id ?: GameIds.NONE)
        } else real
    }

    /** Change the picked game and its bot name in one step, before the next frame is drawn. */
    private fun setGame(def: GameDef?) {
        _game.value = def
        refreshPeers()
    }

    private var restartJob: kotlinx.coroutines.Job? = null

    init {
        Haptics.init(app)
        applyAppearance()
        refreshPeers()
        viewModelScope.launch { transport.peers.collect { refreshPeers() } }
        viewModelScope.launch { link.collect(::onLink) }
        if (_updateChecks.value) checkForUpdates()
    }

    // ---- secret debug menu --------------------------------------------------------------

    /**
     * One tap on the release codename. Five of them unlock the debug menu (and open it);
     * afterwards every tap just opens it. There is no timeout: five taps is five taps.
     */
    fun tapCodename() {
        if (_debugUnlocked.value) {
            _screen.value = Screen.DEBUG
            return
        }
        if (++codenameTaps >= 5) {
            codenameTaps = 0
            _debugUnlocked.value = true
            prefs.edit().putBoolean(KEY_DEBUG_UNLOCKED, true).apply()
            _screen.value = Screen.DEBUG
        }
    }

    fun closeDebug() {
        _screen.value = Screen.MENU
    }

    fun setLoopback(on: Boolean) {
        _loopbackEnabled.value = on
        refreshPeers()
        prefs.edit().putBoolean(KEY_LOOPBACK, on).apply()
        if (!on) loopback.leave()
    }

    /**
     * What wire version this phone claims to be ([Proto.VERSION] when null). The handshake
     * accepts the same version back, so setting this fully emulates an older or newer build:
     * mismatches decline with "different version", equal values still connect.
     */
    fun setReportedVersion(v: Int?) {
        require(v == null || v in 0..255)
        _reportedVersion.value = v
        prefs.edit().putInt(KEY_REPORTED_VERSION, v ?: -1).apply()
    }

    // ---- settings ---------------------------------------------------------------------

    /** Put the saved appearance settings into the globals the theme reads. Safe to call twice. */
    fun applyAppearance() {
        Pal.dark = _dark.value
        AppType.apply(_font.value)
        Haptics.enabled = _vibration.value
    }

    fun openSettings() { _screen.value = Screen.SETTINGS }
    fun closeSettings() { _screen.value = Screen.MENU }

    fun setDark(on: Boolean) {
        _dark.value = on
        Pal.dark = on
        prefs.edit().putBoolean(KEY_DARK, on).apply()
    }

    fun setScreenMode(on: Boolean) {
        _screenMode.value = on
        prefs.edit().putBoolean(KEY_SCREEN_MODE, on).apply()
        // A big screen can only host multiplayer games, so show those.
        if (on) _menuPage.value = 1
    }

    fun setVibration(on: Boolean) {
        _vibration.value = on
        Haptics.enabled = on
        prefs.edit().putBoolean(KEY_VIBRATION, on).apply()
        // The switch itself gives the feedback (Haptics.toggle), so turning it on is felt there.
    }

    fun setUpdateChecks(on: Boolean) {
        _updateChecks.value = on
        prefs.edit().putBoolean(KEY_UPDATE_CHECKS, on).apply()
        if (on) checkForUpdates()
    }

    /** The Activity recreates itself after this so the new locale applies everywhere. */
    fun setLanguage(tag: String) {
        _language.value = tag
        prefs.edit().putString(AppLang.KEY, tag).apply()
    }

    /** Switch the app typeface; it applies at once, no restart needed. */
    fun setFont(tag: String) {
        _font.value = tag
        AppType.apply(tag)
        prefs.edit().putString(KEY_FONT, tag).apply()
    }

    /** Ask this build's update source whether it is current. Safe to call any time. */
    fun checkForUpdates() {
        if (_update.value is UpdateStatus.Checking) return
        _update.value = UpdateStatus.Checking
        viewModelScope.launch(Dispatchers.IO) {
            _update.value = if (BuildConfig.UPDATE_FLAVOR == "github") {
                GithubUpdateCheck.check(BuildConfig.VERSION_NAME)
            } else {
                UpdateCheck.check(BuildConfig.VERSION_CODE)
            }
        }
    }

    // ---- mode / rooms -----------------------------------------------------------------

    /** The room's roster, once this phone has opened or joined one. */
    val room: StateFlow<RoomState> get() = transport.room

    /** Only games that allow more than two players get the choice of mode. */
    private fun supportsRooms(g: GameDef?) = g != null && g.maxPlayers > 2

    /** Host a room for this game. */
    fun startRoom() {
        val g = _game.value ?: return
        _tableMode.value = false
        transport.createRoom(_nick.value, g.id, g.minPlayers, g.maxPlayers)
        _screen.value = Screen.ROOM
    }

    /**
     * Host a room as the table screen (a tablet or TV in the middle of the table). The screen is the
     * Gamemaster but holds no seat, so the room takes one more member than the game has players.
     */
    fun startScreenRoom() {
        val g = _game.value ?: return
        if (!g.supportsScreen) return
        _tableMode.value = true
        transport.createRoom(_nick.value, g.id, g.minPlayers + 1, g.maxPlayers + 1)
        _screen.value = Screen.ROOM
    }

    /** Look for rooms other phones have opened. */
    fun joinRoom() {
        val g = _game.value ?: return
        _notice.value = null
        _tableMode.value = false
        transport.findRooms(_nick.value, g.id)
        _screen.value = Screen.ROOM
    }

    fun rescanRooms() {
        val g = _game.value ?: return
        transport.findRooms(_nick.value, g.id)
    }

    /** Room host only: everyone in the roster starts playing. */
    fun startMatch() = transport.startMatch()

    fun startTwoPlayer() {
        _tableMode.value = false
        search()
        _screen.value = Screen.LOBBY
    }

    /** Back from a room lobby: drop the room and return to the mode choice. */
    fun leaveRoom() {
        if (_screenMode.value && _tableMode.value) { backToMenu(); return }
        stopEverything()
        _screen.value = Screen.SELECT_MODE
    }

    /** Where leaving a match (or a failed attempt) should land: the mode choice, or the lobby. */
    private fun homeScreen() = when {
        _screenMode.value && _tableMode.value -> Screen.MENU   // Screen mode has no mode choice to go back to
        supportsRooms(_game.value) -> Screen.SELECT_MODE
        else -> Screen.LOBBY
    }

    // ---- menu / name ------------------------------------------------------------------

    /** Menu card tapped. If we have no name yet, ask for it first; otherwise go to the lobby. */
    /**
     * Menu card tapped. This only records the choice. The Activity then either asks for a name
     * ([Screen.NAME], if none is saved) or checks Bluetooth permission and calls [enterLobby].
     * Keeping the permission check out of here means a returning user still gets it.
     */
    fun pickGame(def: GameDef): Boolean {
        // In Screen mode only games with a table screen work; the menu says so.
        if (_screenMode.value && !def.supportsScreen) return false
        setGame(def)
        if (_nick.value.isBlank()) _screen.value = Screen.NAME
        return true
    }

    fun setNick(raw: String) { _nick.value = Codec.sanitizeNick(raw) }

    /** True once we have a usable name. The Activity checks Bluetooth permission before calling [enterLobby]. */
    val hasNick: Boolean get() = _nick.value.isNotBlank()

    fun saveNick() {
        val n = _nick.value.trim()
        if (n.isEmpty()) return
        prefs.edit().putString(KEY_NICK, n).apply()
        _nick.value = n
    }

    /** Called once Bluetooth permission is granted and Bluetooth is on. */
    fun enterLobby() {
        if (_game.value == null || !hasNick) return
        saveNick()
        if (_screenMode.value) {
            // Screen mode skips the mode choice: host this game as the table screen right away.
            if (_game.value?.supportsScreen == true) startScreenRoom() else backToMenu()
            return
        }
        if (supportsRooms(_game.value)) {
            // Games that can also be played in a room ask which way first.
            _screen.value = Screen.SELECT_MODE
        } else {
            _screen.value = Screen.LOBBY
            search()
        }
    }

    fun changeName() {
        _screen.value = Screen.NAME
        stopEverything()
    }

    /** Back from a two-player lobby: to the mode choice if the game has one, else the menu. */
    fun backFromLobby() {
        if (supportsRooms(_game.value)) {
            stopEverything()
            _screen.value = Screen.SELECT_MODE
        } else {
            backToMenu()
        }
    }

    fun backToMenu() {
        stopEverything()
        _tableMode.value = false
        setGame(null)
        _screen.value = Screen.MENU
    }

    // ---- lobby ------------------------------------------------------------------------

    fun search() {
        val g = _game.value ?: return
        _notice.value = null
        transport.startDiscovery(_nick.value, g.id)
    }

    fun connectTo(peer: Peer) {
        if (peer.id == LoopbackTransport.FAKE_ID) {
            // Test mode: skip the radio entirely; the bot is already waiting on the other end.
            val g = _game.value ?: return
            loopback.prepare(g.id)
            loopback.connect(peer)
        } else {
            transport.connect(peer)
        }
    }
    fun accept() = transport.accept()
    fun decline() = transport.decline()
    fun chooseFirst(iPlayFirst: Boolean) = transport.chooseFirst(iPlayFirst)

    // ---- in a match -------------------------------------------------------------------

    fun leaveGame() {
        endSession()
        loopback.leave()
        transport.leave()
        _screen.value = homeScreen()
        if (_screen.value == Screen.MENU) { _tableMode.value = false; setGame(null) }
        if (_screen.value == Screen.LOBBY) {
            // leave() reaches Idle after a short delay; look for players again once it has.
            restartJob?.cancel()
            restartJob = viewModelScope.launch {
                delay(400)
                search()
            }
        }
    }

    private fun onLink(l: Link) {
        when (l) {
            is Link.Ready -> startSessionIfNeeded(l)
            is Link.Ended -> {
                endSession()
                _notice.value = l.reason
                if (_screen.value == Screen.PLAYING || _screen.value == Screen.ROOM) {
                    _screen.value = homeScreen()
                    if (_screen.value == Screen.MENU) { _tableMode.value = false; setGame(null) }
                }
                // Resume looking for players after a failed or finished attempt, but only if the
                // user is still in a lobby (not on the menu).
                if (_screen.value == Screen.LOBBY) {
                    restartJob?.cancel()
                    restartJob = viewModelScope.launch {
                        delay(600)
                        if (transport.link.value is Link.Ended && _screen.value == Screen.LOBBY) {
                            _game.value?.let { transport.startDiscovery(_nick.value, it.id) }
                        }
                    }
                }
            }
            else -> Unit
        }
    }

    private fun startSessionIfNeeded(ready: Link.Ready) {
        if (_session.value != null) return
        val def = _game.value ?: return
        // Whoever reported Ready owns the match: the loopback for test games, the radio otherwise.
        val t = if (loopback.link.value is Link.Ready) loopback else transport
        // Decide whether this match is a room (N-player) or a plain 2‑player link.
        val roomState = transport.room.value
        if (roomState !is RoomState.None) {
            // N‑player: use the room‑specific session creator.
            val hostNick = when (roomState) {
                is RoomState.Hosting -> _nick.value // we are the host
                is RoomState.Joined -> roomState.hostNick
        else -> _nick.value // fallback
            }
            val s = if (_tableMode.value && roomState is RoomState.Hosting) {
                def.createScreenSession(scope = viewModelScope, transport = t, myNick = _nick.value)
            } else def.createRoomSession(
                scope = viewModelScope,
                transport = t,
                myNick = _nick.value,
                hostNick = hostNick,
            )
            _session.value = s
            _screen.value = Screen.PLAYING
            s.start()
            return
        }
        // 2‑player fallback – existing logic.
        val s = def.createSession(
            scope = viewModelScope,
            transport = t,
            playsFirst = ready.playsFirst,
            mySide = ready.mySide,
            myNick = _nick.value,
            theirNick = ready.peerNick,
        )
        _session.value = s
        _screen.value = Screen.PLAYING
        // Ordering guarantee: the transport delivers every incoming packet on the main thread, and
        // viewModelScope runs on Dispatchers.Main.immediate, so the collectors that start() launches
        // subscribe to the (replay-less) transport flows before any later packet can be dispatched.
        // If a session's start() ever moves off the main dispatcher, add replay to those flows.
        s.start()
        return

    }

    private fun endSession() {
        _session.value?.stop()
        _session.value = null
    }

    private fun stopEverything() {
        restartJob?.cancel()
        endSession()
        loopback.leave()
        transport.leave()
    }

    override fun onCleared() {
        endSession()
        transport.release()
    }

    private companion object {
        const val KEY_NICK = "nick"
        const val KEY_DARK = "dark"
        const val KEY_VIBRATION = "vibration"
        const val KEY_SCREEN_MODE = "screen_mode"
        const val KEY_UPDATE_CHECKS = "update_checks"
        const val KEY_DEBUG_UNLOCKED = "debug_unlocked"
        const val KEY_LOOPBACK = "test_mode"
        const val KEY_REPORTED_VERSION = "reported_version"
        const val KEY_FONT = "font"

        fun systemIsDark(app: Application): Boolean =
            app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }
}
