# BT Games

Multiplayer games over BLE. No internet or Wi-Fi, no accounts.
Package: `us.crafties.btgames`. Games included: **Paddle**, **XO** (tic-tac-toe),
**Tetriminoes**, **Keymash** (typing race), **Block Tower**, **Rock Paper Scissors**,
**4 Chips**, **Checkers**, **Cup Shuffle**, **Chess**, **Shut The Box**, **Last!** (a card game,
2‑6 players) and **Picture Match** (a memory game, 2‑6 players).

## How it plays

1. The main menu lists the games. Tap one.
2. First time only: enter the name other players will see (Thai and other scripts work).
3. The app advertises you and scans for phones that picked **the same game**. A Paddle player never
   sees someone waiting in XO.
4. Tap a name to send a request. The other player gets Accept / Decline. The game starts only if
   they accept, and **the person who asked goes first** (Paddle: the first ball is served to them;
   XO: they are X).
   **Colours are random:** one player is red and the other blue, decided per match with no extra
   messages (see "Colours" below). You are not always red.
5. **If you both tap each other at the same time**, that counts as both accepting. Nobody is asked
   to accept; instead one of you gets a "Choose who goes first" prompt while the other waits.
   If that handshake has not connected after **10 seconds** you get "Couldn't connect. Try again, and
   make sure you don't both tap each other at the same moment." and go back to the lobby.

## Settings

The gear on the menu opens **Settings**: **Dark mode**, **Font**, **Vibration**, **Screen mode**,
**Language**, **Updates** and **Licenses**. All choices apply at once and are remembered. Dark mode
starts out following the phone's own setting the first time the app runs. **Updates** checks F-Droid's
public package API.
The Paddle court is dark in both modes on purpose. Vibration is a small buzz on button taps, a thump
when a mark lands (XO) or a goal is scored (Paddle), and a pattern for winning or losing; switching it
off silences all of it (`ui/Haptics.kt` is the only place the app vibrates). **Licenses** lists the
app, the Inter and Intern fonts and the bundled libraries with their licences and full notices.

## Look and feel

Modelled on a windowed game UI: a cyan title bar with the title centred in white over a light grey
page, white cards and buttons with pale blue outlines and a soft blue-grey drop shadow, cyan for
anything primary or selected. Dialogs are a cyan frame with a rounded grey body inset in it, on a soft drop shadow.

- `ui/Theme.kt` holds every colour (`Pal`), light and dark. Nothing else hard-codes one.
- `ui/TitleBar.kt` is `AppScreen`, the title-bar layout every non-game screen uses.
- `ui/Raised.kt` has the surfaces. `Tone.Light` is a white button, `Tone.Primary` is the cyan main
  action. Either can drop its outline with `bordered = false`, which is how the game tiles are drawn.
  A button label wraps and then shrinks (`FitText`) instead of being cut off.
- Font: **Intern** by default (a rounded variable variant of Inter) or **Inter**, both SIL Open
  Font License 1.1, in Settings. Thai and other scripts fall back to the phone's font.

## Codenames

Each release gets an element of the periodic table, one per letter, A to Z (this one is Erbium).
It is `codename` in `res/values/strings.xml` and shows in a small tag in the corner of the menu and
Settings. Changing the string is all a new release needs. It is separate from `versionCode` and from
`Proto.VERSION` (the Bluetooth wire version), which only changes when the wire format does.

## Build

**Android Studio:** File > Open this folder, let it sync, press Run on a real device.

**Command line:** with any installed Gradle (8.9 recommended):

    gradle assembleDebug                     # APK: app/build/outputs/apk/debug/app-debug.apk
    gradle assembleRelease                   # the optimised build F-Droid produces
    gradle test                              # JVM unit tests, no device needed

You also need the Android SDK (`local.properties` with `sdk.dir=...`, or `ANDROID_HOME`) and JDK 17+.
minSdk 26 (Android 8.0), compile/target SDK 34. On Termux, add
`-Pandroid.aapt2FromMavenOverride=$(which aapt2)` to every command: the Maven aapt2 is a Linux binary
that cannot run there. Release builds use R8 and resource shrinking; F-Droid builds this same
non-debuggable release variant, so ART AOT-compiles it at install (debug builds are JIT-heavy).

**F-Droid:** the source is self-contained, all fonts are committed, there is no build-time download
and no proprietary dependency. The in-app updater uses F-Droid's public package API only. The app
licence is GPL-3.0 (`LICENSE`) and the bundled font licences are in `LICENSE-FONTS.txt`.

## Permissions

Android 12+ asks for the "Nearby devices" permission; Android 11 and below ask for Location (a
platform requirement for BLE scanning; the app does not use your location!!).

## Layout

    net/Protocol.kt        shared wire format: handshake + opaque game messages. Knows no game.
    net/Transport.kt       Bluetooth-agnostic interface that games talk to
    net/GattTransport.kt   BLE implementation; every phone is both GATT server and scanner
    net/ConnectDeadline.kt the 10 s limit on the machine-only part of a request (pure, unit tested)
    net/Sides.kt           red/blue assignment from the handshake tokens (pure, unit tested)
    shell/GameApi.kt       the plug-in contract: GameDef + GameSession
    shell/GameRegistry.kt  the list of games shown on the menu
    shell/ShellViewModel   menu -> name -> lobby -> playing
    games/paddle/            engine, codec, session, court screen
    games/xo/              board rules, codec, session, screen
    games/tetriminoes/     engine, codec, session, screen (score-attack: a random player
                           picks the time, both play at once, most points wins)
    games/keymash/         word/sentence banks, codec, session, screen (typing race: a random
                           player picks the mode, both type the same prompts from one seed)
    games/blocktower/      deterministic tower physics, codec, session, screen (turn-based:
                           one shared seed, both phones simulate the same tower, faller loses)
    games/rps/             rules, codec, session, screen (simultaneous throws, first to 5)
     games/fourchips/       board rules, codec, session, screen (drop discs, connect four;
                            starter alternates, local-only drop animation)
     games/checkers/        engine, codec, session, screen (Makhos Thai draughts: 8 men on
                            two rows, forward-only men, flying kings, starter alternates)
     games/cupshuffle/      codec, logic, session, screen (shell game: both phones see the
                            ball, then one side tap-shuffles for 5 s in animated swaps for
                            the other to track; x-ray vision for the shuffler, roles swap,
                            3-6 cups picked by the shuffler)
     games/chess/           engine, codec, session, screen (standard chess: castling,
                            en passant, promotion; White swaps every rematch)
    games/last/            rules engine, codec, session, screen, card art (Last!: every phone deals the
                           same deck from a shared seed, so only moves are sent; 2 players or a 2‑6 room)
    ui/                    menu, name, lobby, dialogs, theme
    ui/Board.kt            the shared 8x8 board and tap-or-drag gesture (checkers + chess)
    ui/Raised.kt           the raised / recessed surface components used by every screen
    ui/TitleBar.kt         AppScreen (cyan title bar + body) and the icon chip
    ui/VersionTag.kt       the codename tag from strings.xml
    ui/Wording.kt          "Sam wins" / "They win": verbs that agree with a name (unit tested)
    ui/Fields.kt           the text box and the on/off switch, in the same style
    ui/Dialogs.kt          RaisedDialog: cyan-framed dialog with an inset rounded body and a soft shadow
    ui/SettingsScreen.kt   dark mode, vibration, language, updates and licences
    ui/Haptics.kt          the one place the app vibrates
    net/PeerTable.kt       the lobby's list of players, keyed by advertised id (pure, unit tested)
    net/LoopbackTransport.kt radio-free Transport for test mode, plus the BotBrain contract
    debug/Bots.kt          a random-move bot per game (unit tested, no phone needed)
    ui/DebugScreen.kt      the secret debug menu: five taps on the codename, remembered

## Adding a game

1. Pick an unused id in `GameIds` (never reuse or renumber an existing one).
2. Write a `GameSession` (talk only through `Transport`) and a `GameDef` object. Draw your own
   things with `Pal.paint(mySide)` and the opponent's with `Pal.paint(mySide.other())`; never assume
   "me = red". Titles/blurbs are string resources (`titleRes`/`blurbRes`); every user-visible
   string in the game must come from `res/values/strings.xml` (with `th`/`ja`/`ko`/`zh-rCN`
   translations) and go through `stringResource`, so the Settings language picker covers it.
3. Add the `GameDef` to the list in `GameRegistry`. It appears on the menu and gets its own lobby.

Use `Control.Game(payload)` for events that must arrive (turns, scores) and `sendStream` for
high-rate, latest-wins data (positions). Games decide who starts from `playsFirst`, **not** from who
connected to whom.

## The mutual-request handshake

Both phones tapping each other creates two GATT links (A to B and B to A) and would otherwise leave
each side refusing the other's connection. The transport handles it like this:

- Every phone has a random 4-byte token. HELLO carries it.
- A phone that is already asking someone and then receives a HELLO knows the request is mutual, but
  does not act on that alone. It sends `MUTUAL` and waits until it has **also** been told the other
  side saw its HELLO. One-sided inference is what lets two phones disagree.
- The phone with the **lower token** chooses who goes first. Both compute this identically.
- The link opened by the lower-token phone survives; the other phone drops its own.
- If the confirmation never arrives, the waiting phone sends `FALLBACK` after 6 s and both drop back
  to an ordinary Accept / Decline, so the worst case is the normal flow, never a stuck screen.
- Separately, a **10 second connect deadline** covers the part with no human in it. It starts at the
  tap and stops once the other phone acknowledges the request, because after that the wait is on a
  person pressing Accept and must not be cut off. If the other phone's request has also arrived (a
  mutual tap) it keeps running until both directions are proven. If it expires while still
  `Requesting`, the attempt fails with the "Couldn't connect" message.

Token collisions (about 1 in 4 billion) also fall back to Accept / Decline.

## Colours

Each phone holds a random token (re-rolled every time you search). One player is the *anchor*; the
anchor gets the colour its token hashes to and the other gets the opposite, so the two phones can
never agree on the same colour and no extra message is needed. Who the anchor is:

- ordinary request: the phone that asked (the acceptor reads its token from the HELLO)
- mutual request, or both people accepting after a fallback: the phone with the lower token

That last rule matters. If each phone anchored on "whoever asked" in the both-accept case they would
pick different anchors and collide about half the time. `SidesTest` covers every path.

## The lobby list

Players are identified by an **advert id** (2 bytes in the advertisement, random per app run), not by
Bluetooth address. Android gives a phone a new random address every time it restarts advertising,
which is exactly what **Search again** does, so keying by address listed the same player twice on the
other phone until the old entry timed out. `PeerTable` replaces the old sighting instead, and connects
to the newest address. The advertisement is now exactly 31 bytes with a 24-byte name, so it cannot
grow further; `Proto.VERSION` is 4 because of this change.

## License

BT Games is free software under the **GNU General Public License v3.0**, see [`LICENSE`](LICENSE).
You may use, study, share and improve it, provided you credit the authors and keep the copyleft:
derivative works are distributed under GPL-3.0 too.

Third-party components:

- **Inter** and **Intern** typefaces - SIL Open Font License 1.1 (full text in `LICENSE-FONTS.txt`).
- **AndroidX, Jetpack Compose, Kotlin, kotlinx.coroutines** - Apache License 2.0.
- **JUnit** (tests only) - MIT.

The same notices are shown in the app under **Settings → Licenses**.

## Known limits

- A two-player link allows one opponent; games with rooms support 2-6 players.
- If Bluetooth cannot negotiate a larger MTU, only 20 bytes fit per write and long nicknames are
  shortened on a whole-character boundary (a 5-letter Thai name can lose a letter). Normally the
  app requests an MTU of 64 and 24-byte names fit.
- Paddle sends state at 30 Hz. The guest sees the ball one connection interval late and dead-reckons
  it to hide that. Results vary by phone and manufacturer.
- The mutual-request handshake, the 10 s deadline and the colour assignment have been reasoned about,
  unit tested and simulated, but not tried on hardware.
