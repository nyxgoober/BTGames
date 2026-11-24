# AGENTS.md — BT Games

Single-module Android app (`:app`, `us.crafties.btgames`): BLE games (Paddle, XO, Tetriminoes, Keymash, Block Tower, RPS, 4 Chips, Checkers, Chess, Cup Shuffle, Shut The Box, Last!). Kotlin 2.0.20, AGP 8.5.2, Compose, minSdk 26, compile/target 34, JDK 17+.

## Build / test

- There is no `gradlew` script; `gradle/wrapper/gradle-wrapper.jar` (Gradle 8.9) is committed. Use any installed Gradle, e.g. `gradle assembleDebug`, or open in Android Studio. F-Droid uses its own wrapper.
- `gradle assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`. Needs Android SDK (`local.properties` `sdk.dir=` or `ANDROID_HOME`).
- `gradle test` — JVM unit tests, no device needed. Single test: `gradle :app:testDebugUnitTest --tests "us.crafties.btgames.net.SidesTest"`.
- `gradle.properties` has **no** `aapt2FromMavenOverride` (F-Droid builds need the Maven aapt2). On Termux, pass `-Pandroid.aapt2FromMavenOverride=$(which aapt2)` to `gradle` — the Maven binary is Linux-only and cannot run there.
- Fonts are versioned in `app/src/main/res/font/`: `inter.ttf` and `intern.ttf` (both variable, SIL OFL, see `LICENSE-FONTS.txt`). No download task; offline builds work.
- No CI, no lint/typecheck scripts — Kotlin compile + `./gradlew test` is the full verification.

## Architecture

- `shell/GameApi.kt` is the plugin contract (`GameDef` + `GameSession`); `shell/GameRegistry.kt` lists games in menu order. Adding a game: new `GameIds` const (never reuse/renumber), `GameDef` + session, register it — nothing else in shell changes. New-game checklist lives in `README.md` "Adding a game".
- `net/`: `Protocol.kt` (wire format, `Proto.VERSION = 9`), `Transport.kt` (interface games code against), `GattTransport.kt` (BLE), `LoopbackTransport.kt` (radio-free test-mode transport), `Sides.kt` / `PeerTable.kt` / `ConnectDeadline.kt` (pure, unit-tested). `UpdateCheck.kt` queries F-Droid's public package API only — no custom update server.
- Per game (`games/<name>/`): engine/board rules + codec + session + screen. `ui/`: `Theme.kt` (`Pal`), `Board.kt` (shared 8×8 board + tap/drag gesture for checkers & chess), `Raised.kt`, `TitleBar.kt` (`AppScreen`), `Haptics.kt`, `Wording.kt`.
- BLE needs **two physical phones** (advertising/GATT don't work on emulators). One-phone testing: tap the release codename 5× → Debug menu → Test mode → `TestMode` bot in every lobby (`debug/Bots.kt` over `LoopbackTransport`).

## Conventions that differ from defaults

- Game sessions talk **only** through `Transport`. Reliable events → `Control.Game(payload)`; high-rate latest-wins state → `sendStream`. Never touch GATT from game code.
- Starter: use `playsFirst` from the handshake. Never infer it from who connected to whom.
- Colour: random per match. Never assume self = red — draw self with `Pal.paint(mySide)`, opponent with `Pal.paint(mySide.other())`.
- Colours: `ui/Theme.kt` `Pal` owns every colour; nothing else hard-codes one. Paddle court stays dark (`Pal.Court`) in both modes.
- Vibration: `ui/Haptics.kt` is the only place that vibrates; respect its `enabled` flag.
- Wire limits (bump `Proto.VERSION` only on wire-format change; release `codename` in `strings.xml` and `versionCode` are independent): advert is exactly 31 bytes, nick ≤ 24 bytes (grapheme-clamped via `Codec.sanitizeNick`), `MAX_GAME_PAYLOAD = 40`, MTU 64 requested with 20-byte fallback.
- `Proto.VERSION` vs peer mismatch → decline with "different version". `debug/DebugScreen` "Reported version" fakes the HELLO version for testing this.
