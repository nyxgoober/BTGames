package us.crafties.btgames.net

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BLE implementation of [Transport].
 *
 * Every phone runs BOTH a GATT server (so others can connect to it) and a scanner/GATT
 * client (so it can connect to others). Whoever taps the other player's name is the
 * "initiator": they connect as GATT client, send HELLO, and become the game host.
 *
 *   initiator (GATT client)  ---HELLO--->  advertiser (GATT server)
 *   initiator                <--ACCEPT---  advertiser        (or DECLINE)
 *   initiator                ---STATE---> (notify)  advertiser
 *   initiator                <--INPUT----  (write no response)
 *
 * All Bluetooth callbacks arrive on binder threads; everything is funnelled through a single
 * main-thread Handler so state is only touched from one thread and GATT operations can be
 * queued one at a time (Android drops concurrent GATT operations silently).
 */
@SuppressLint("MissingPermission") // The UI layer requests permissions before calling startDiscovery().
class GattTransport(
    private val context: Context,
    /**
     * Test-mode override for the wire version this phone claims in HELLO and accepts in return.
     * Debug menu only; defaults to the real [Proto.VERSION]. Read on the main thread, where all
     * handshake work happens, so a plain lambda is enough.
     */
    private val versionOverride: () -> Int = { Proto.VERSION },
) : Transport {

    private val tag = "GattTransport"
    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = manager.adapter

    private val _link = MutableStateFlow<Link>(Link.Idle)
    override val link: StateFlow<Link> = _link.asStateFlow()

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    override val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val _control = MutableSharedFlow<Control>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val control: Flow<Control> = _control

    // Stream is latest-wins: dropping old frames is the correct behaviour under congestion.
    private val _stream = MutableSharedFlow<ByteArray>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val stream: Flow<ByteArray> = _stream

    private var localNick = ""
    private var gameId = GameIds.NONE

    // ---- mutual-initiation handshake ----------------------------------------------------
    /**
     * Random id for this attempt. Compared with the peer's to break ties deterministically, and it
     * also decides which player gets which colour. It is re-rolled on every [startDiscovery] so the
     * same two people do not get the same colours every game.
     */
    private var myToken: Int = java.security.SecureRandom().nextInt()

    private fun broadcastStream(data: ByteArray, except: BluetoothDevice) {
        for (cl in roomClients.values) {
            if (cl.subscribedStream && cl.device != except) {
                serverSend(cl.device, streamChar ?: return, data, reliable = false)
            }
        }
    }
    /** The peer's HELLO when it reaches us while we are also asking them (a mutual request). */
    private var peerHello: Control.Hello? = null
    /** True once the peer has told us it saw OUR hello (the MUTUAL message). */
    private var peerConfirmedMutual = false
    /** Set once we have shown or are waiting on the first-player choice. */
    private var inMutual = false
    private var mutualTimeoutPosted = false
    /**
     * The 10 second limit on the machine-only part of a request. All of its decisions live in
     * [ConnectDeadline] so they can be unit tested; this class only reports events to it.
     */
    private val connectDeadline = ConnectDeadline(
        timer = object : ConnectDeadline.Timer {
            private var pending: Runnable? = null
            override fun postDelayed(delayMs: Long, block: () -> Unit) {
                val r = Runnable { pending = null; block() }
                pending = r
                main.postDelayed(r, delayMs)
            }
            override fun cancel() {
                // A plain call, not a `main::removeCallbacks` reference: that method is overloaded and
                // Kotlin can reject the reference as ambiguous.
                val r = pending
                if (r != null) main.removeCallbacks(r)
                pending = null
            }
        },
        onExpired = {
            // Only a phone still at the start of the handshake has failed. If we have moved on to a
            // dialog, a chooser, or a running game, a person or the game owns the clock now.
            if (_link.value is Link.Requesting) failConnection(ConnectDeadline.MESSAGE)
        },
    )
    /** The next acknowledged CONTROL write is our HELLO. */
    private var awaitingHelloAck = false
    // ---- server side (we are the advertiser / possible guest) ------------------------------
    private var server: BluetoothGattServer? = null
    private var controlChar: BluetoothGattCharacteristic? = null
    private var streamChar: BluetoothGattCharacteristic? = null
    private var serverPeer: BluetoothDevice? = null            // device currently connected to our server
    private var peerSubscribedControl = false
    private var peerSubscribedStream = false
    private var pendingHello: Control.Hello? = null
    private var advertising = false

    // ---- N-player room (the Gamemaster star) --------------------------------------------
    // One phone (the Gamemaster) runs the GATT server and accepts every client up to the
    // game's maxPlayers; clients connect only to it and talk only through it. The whole
    // 2-player handshake above is untouched: all of this is gated on [roomMode].
    private val _room = MutableStateFlow<RoomState>(RoomState.None)
    override val room: StateFlow<RoomState> = _room.asStateFlow()

    /** True once [createRoom]/[findRooms] put us in room mode; false in every 2-player lobby. */
    private var roomMode = false
    /** True on the phone that opened the room (the Gamemaster). */
    private var gamemaster = false
    /** The Gamemaster's roster; also built by a joiner from the messages the host sends. */
    private var roster: RoomRoster? = null
    /** Our own seat once the Gamemaster has seated us (0 on the Gamemaster). */
    private var mySeat = -1
    /** True from [startMatch] until the room goes back to being a lobby. */
    private var matchStarted = false

    /** Per-connection state on the Gamemaster's server (room mode only). */
    private class RoomClient(val device: BluetoothDevice) {
        var seat = -1                       // -1 until HELLO has seated this phone
        var subscribedControl = false
        var subscribedStream = false
        var payload = 20                    // negotiated ATT payload toward this device
    }

    /** Address -> connected client. Turn order comes from [roster] (HELLO order), not from here. */
    private val roomClients = LinkedHashMap<String, RoomClient>()

    // ---- client side (we are the scanner / possible host) ------------------------------
    private var gatt: BluetoothGatt? = null
    private var clientControl: BluetoothGattCharacteristic? = null
    private var clientStream: BluetoothGattCharacteristic? = null
    private var negotiatedPayload = 20        // bytes available per ATT write/notify (MTU - 3)
    private var scanning = false
    private var weInitiated = false
    private var connectingTo: String? = null
    private val seen = PeerTable(PEER_TTL_MS)
    /** Names this phone in its advertisement; see [Codec.encodeAdvert]. Fixed for the life of the app, unlike the address. */
    private val advertId = java.security.SecureRandom().nextInt(0x10000)

    // GATT operations must run strictly one at a time on the client.
    private val opQueue = ArrayDeque<() -> Unit>()
    private var opBusy = false

    // =====================================================================================
    // Discovery
    // =====================================================================================

    override fun startDiscovery(localNick: String, gameId: Int) = onMain {
        val ad = adapter
        if (ad == null || !ad.isEnabled) {
            _link.value = Link.Ended("Bluetooth is off")
            return@onMain
        }
        this.localNick = Codec.sanitizeNick(localNick)
        this.gameId = gameId
        // Restarts (after a game ends, or after an error) must not stack a new advertiser/scanner
        // on top of the previous session's.
        stopScanning()
        stopAdvertising()
        resetConnectionState()
        myToken = java.security.SecureRandom().nextInt()
        openServer()
        startAdvertising()
        startScanning()
        _link.value = Link.Discovering
    }

    override fun stopDiscovery() = onMain {
        stopScanning()
        stopAdvertising()
    }

    // =====================================================================================
    // N-player rooms (Gamemaster star)
    // =====================================================================================

    override fun createRoom(localNick: String, gameId: Int, min: Int, max: Int) = onMain {
        val ad = adapter
        if (ad == null || !ad.isEnabled) {
            _link.value = Link.Ended("Bluetooth is off")
            return@onMain
        }
        stopScanning(); stopAdvertising(); resetConnectionState()
        this.localNick = Codec.sanitizeNick(localNick)
        this.gameId = gameId
        roomMode = true
        gamemaster = true
        matchStarted = false
        mySeat = 0
        roster = RoomRoster(min, max).apply { addHost(this@GattTransport.localNick) }
        myToken = java.security.SecureRandom().nextInt()
        openServer()
        startAdvertising()
        _link.value = Link.Discovering
        publishHostRoom()
    }

    override fun findRooms(localNick: String, gameId: Int) = onMain {
        val ad = adapter
        if (ad == null || !ad.isEnabled) {
            _link.value = Link.Ended("Bluetooth is off")
            return@onMain
        }
        stopScanning(); stopAdvertising(); resetConnectionState()
        this.localNick = Codec.sanitizeNick(localNick)
        this.gameId = gameId
        roomMode = true
        gamemaster = false
        matchStarted = false
        mySeat = -1
        roster = null
        myToken = java.security.SecureRandom().nextInt()
        // Joiners never advertise and never accept an inbound connection: everything they
        // say goes out on the one client link they open to a Gamemaster.
        startScanning()
        _link.value = Link.Discovering
    }

    override fun startMatch() = onMain {
        if (!roomMode || !gamemaster) return@onMain
        val r = roster ?: return@onMain
        if (matchStarted || r.size < r.min) return@onMain
        matchStarted = true
        stopAdvertising() // the room is closed to newcomers while the match runs
        broadcastRoom(RoomMsg.Start)
        _link.value = Link.Ready(
            peerNick = r.members.getOrNull(1)?.nick.orEmpty(),
            weInitiated = false,
            playsFirst = true,  // the Gamemaster is turn index 0
            // Room matches: colours are the game's business; seats come from RoomMatch.
            mySide = Side.RED,
        )
    }

    private fun startAdvertising() {
        advertiseAttempt = 0
        main.removeCallbacks(advertiseRetry)
        beginAdvertising()
    }

    private fun beginAdvertising() {
        val advertiser = adapter?.bluetoothLeAdvertiser ?: run {
            _link.value = Link.Ended("This phone cannot advertise over Bluetooth LE")
            return
        }
        if (advertising) return
        // Always release the callback first. Asking for a second advertising slot while the
        // controller still holds the previous one is a classic cause of error 2/4 on midrange chips.
        // stopAdvertising() is a harmless no-op if this callback is not registered.
        advertiser.stopAdvertising(advertiseCallback)
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // The 128-bit service UUID takes 18 of the 31 advertising bytes, so the nickname
        // goes in the scan response instead (up to 31 more bytes) where it has room.
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(Proto.SERVICE_UUID))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(
                MANUFACTURER_ID,
                // An open room advertises the same game with the room flag set, so 2-player
                // lobbies (which match the plain id) never list it and joiners list only it.
                Codec.encodeAdvert(
                    if (roomMode && gamemaster) Codec.roomAdvertId(gameId) else gameId,
                    advertId,
                    localNick,
                ),
            )
            .build()
        advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    private fun stopAdvertising() {
        main.removeCallbacks(advertiseRetry)
        // Stop even if `advertising` is still false: a start request may be in flight or half-registered.
        adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        advertising = false
    }

    private var advertiseAttempt = 0
    private val advertiseRetry = Runnable {
        // Only keep retrying while we still want to be discoverable.
        if (_link.value is Link.Discovering) beginAdvertising()
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            advertising = true
            advertiseAttempt = 0
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
            Log.w(tag, "advertise failed: $errorCode (attempt ${advertiseAttempt + 1})")
            onMain {
                val transient = errorCode == ADVERTISE_FAILED_TOO_MANY_ADVERTISERS ||
                    errorCode == ADVERTISE_FAILED_INTERNAL_ERROR
                if (transient && advertiseAttempt < ADVERTISE_RETRY_DELAYS_MS.size) {
                    // The stack is busy, not broken. Retry quietly; the user sees nothing.
                    val delay = ADVERTISE_RETRY_DELAYS_MS[advertiseAttempt++]
                    main.removeCallbacks(advertiseRetry)
                    main.postDelayed(advertiseRetry, delay)
                } else {
                    _link.value = Link.Ended(
                        when (errorCode) {
                            ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "This phone cannot advertise over Bluetooth LE"
                            else -> "Could not make this phone visible to others (error $errorCode). Turn Bluetooth off and on, then try again."
                        }
                    )
                }
            }
        }
    }

    private fun startScanning() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        if (scanning) return
        seen.clear(); _peers.value = emptyList()
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(Proto.SERVICE_UUID)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(filters, settings, scanCallback)
        scanning = true
        pruneLoop()
    }

    private fun stopScanning() {
        if (!scanning) return
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        scanning = false
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val advert = Codec.decodeAdvert(result.scanRecord?.getManufacturerSpecificData(MANUFACTURER_ID))
            val address = result.device.address
            onMain {
                // A joiner matches only open rooms; a 2-player lobby matches only plain adverts
                // (the room flag makes them differ, so neither list ever shows the other's).
                val wanted = if (roomMode && !gamemaster) Codec.roomAdvertId(gameId) else gameId
                if (advert == null || advert.gameId != wanted) {
                    // Not our app, or waiting in a different game: make sure it is not listed.
                    if (seen.notForThisLobby(address, advert?.id)) _peers.value = seen.snapshot()
                    return@onMain
                }
                seen.sighted(Peer(advert.id, address, advert.nick, result.rssi), System.currentTimeMillis())
                _peers.value = seen.snapshot()
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            onMain { _link.value = Link.Ended("Scan failed (error $errorCode)") }
        }
    }

    // Drop peers we haven't heard from in a few seconds so the list doesn't show ghosts.
    private val pruneRunnable = object : Runnable {
        override fun run() {
            if (!scanning) return
            if (seen.prune(System.currentTimeMillis())) _peers.value = seen.snapshot()
            main.postDelayed(this, 1500)
        }
    }
    private fun pruneLoop() { main.removeCallbacks(pruneRunnable); main.postDelayed(pruneRunnable, 1500) }

    // =====================================================================================
    // Initiator (GATT client)
    // =====================================================================================

    override fun connect(peer: Peer) = onMain {
        if (_link.value !is Link.Discovering) return@onMain
        val device = adapter?.getRemoteDevice(peer.address) ?: return@onMain
        weInitiated = true
        connectingTo = peer.address
        _link.value = Link.Requesting(peer.nick)
        // Connect, discover, subscribe and deliver HELLO involve no human, so they get a hard limit.
        connectDeadline.onTapped()
        // Free the radio for the connection attempt.
        stopScanning()
        gatt = device.connectGatt(context, false, clientCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                onMain {
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    // Ask for a bigger MTU; we still fall back to 20 bytes if refused.
                    if (!g.requestMtu(64)) g.discoverServices()
                }
            } else {
                onMain { handleClientDisconnected(g, status) }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            onMain {
                if (status == BluetoothGatt.GATT_SUCCESS) negotiatedPayload = (mtu - 3).coerceAtLeast(20)
                g.discoverServices()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            onMain {
                val svc = g.getService(Proto.SERVICE_UUID)
                clientControl = svc?.getCharacteristic(Proto.CONTROL_UUID)
                clientStream = svc?.getCharacteristic(Proto.STREAM_UUID)
                if (status != BluetoothGatt.GATT_SUCCESS || clientControl == null || clientStream == null) {
                    failConnection("The other phone is not running BT Games")
                    return@onMain
                }
                // Subscribe to both characteristics, one CCCD write at a time, then send HELLO.
                enqueue { subscribe(g, clientControl!!) }
                enqueue { subscribe(g, clientStream!!) }
                enqueue {
                    awaitingHelloAck = true
                    writeControlAsClient(Codec.encode(helloFittingPayload()))
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            onMain { opDone() }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            // Stream writes are write-without-response and are not part of the op queue; letting
            // their callbacks advance the queue would run the next op while a real one is in flight.
            if (c.uuid != Proto.CONTROL_UUID) return
            onMain {
                if (awaitingHelloAck) {
                    awaitingHelloAck = false
                    if (status == BluetoothGatt.GATT_SUCCESS) connectDeadline.onRequestDelivered()
                }
                opDone()
            }
        }

        // Exactly one of these two overloads fires per packet: API 33+ calls only the 3-arg version
        // below (whose default body would forward here, but we override it without super), and
        // API <= 32 calls only this legacy one. Do not merge them or packets will be handled twice.
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            dispatchIncoming(c.uuid, c.value ?: return)
        }

        // API >= 33
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            dispatchIncoming(c.uuid, value)
        }
    }

    private fun subscribe(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(c, true)
        val cccd = c.getDescriptor(Proto.CCCD_UUID)
        if (cccd == null) { opDone(); return }
        // Control uses INDICATE so the stack retransmits until acked (reliable); stream uses NOTIFY.
        val v = if (c.uuid == Proto.CONTROL_UUID) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(cccd, v)
        } else {
            @Suppress("DEPRECATION") run { cccd.value = v; g.writeDescriptor(cccd) }
        }
    }

    private fun writeControlAsClient(bytes: ByteArray) {
        val c = clientControl ?: run { opDone(); return }
        val g = gatt ?: run { opDone(); return }
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION") run {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                c.value = bytes
                g.writeCharacteristic(c)
            }
        }
    }

    private fun writeStreamAsClient(bytes: ByteArray) {
        val c = clientStream ?: return
        val g = gatt ?: return
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            @Suppress("DEPRECATION") run {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                c.value = bytes
                g.writeCharacteristic(c)
            }
        }
    }

    // =====================================================================================
    // Advertiser (GATT server)
    // =====================================================================================

    private fun openServer() {
        if (server != null) return
        val srv = manager.openGattServer(context, serverCallback) ?: run {
            _link.value = Link.Ended("Could not open Bluetooth server")
            return
        }
        val control = BluetoothGattCharacteristic(
            Proto.CONTROL_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_INDICATE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        ).apply {
            addDescriptor(BluetoothGattDescriptor(Proto.CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        }
        val stream = BluetoothGattCharacteristic(
            Proto.STREAM_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        ).apply {
            addDescriptor(BluetoothGattDescriptor(Proto.CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        }
        val service = BluetoothGattService(Proto.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(control)
        service.addCharacteristic(stream)
        srv.addService(service)
        server = srv
        controlChar = control
        streamChar = stream
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            onMain {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    when {
                        // The Gamemaster: every client up to capacity gets a slot.
                        roomMode && gamemaster -> {
                            val max = roster?.max ?: 1
                            if (matchStarted || roomClients.size >= max - 1) {
                                server?.cancelConnection(device) // room full, or the match already ran
                            } else {
                                roomClients.getOrPut(device.address) { RoomClient(device) }
                                scheduleRoomHelloTimeout(device)
                            }
                        }
                        // A joiner never accepts inbound connections: everything goes out on
                        // the client link it opened to the Gamemaster.
                        roomMode -> server?.cancelConnection(device)
                        else -> {
                            // While we are asking someone, the person we asked may be asking us at the same
                            // moment. Their inbound connection must not be refused. We do NOT compare BLE
                            // addresses to decide whether it is "them": addresses can differ between the role
                            // they advertised in and the role they connect in. The HELLO token decides that.
                            val mayReceive = _link.value is Link.Requesting || (!weInitiated && _link.value is Link.Discovering)
                            if (serverPeer == null && mayReceive) {
                                serverPeer = device
                                scheduleHelloTimeout(device)
                            } else if (serverPeer?.address != device.address) {
                                // Already busy with someone else: refuse extra connections politely.
                                server?.cancelConnection(device)
                            }
                        }
                    }
                } else {
                    when {
                        roomMode && gamemaster -> roomMemberGone(device)
                        serverPeer?.address == device.address -> handleServerPeerGone()
                    }
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            onMain {
                val payload = (mtu - 3).coerceAtLeast(20)
                negotiatedPayload = payload
                roomClients[device.address]?.payload = payload
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            onMain {
                val on = value.isNotEmpty() && value[0].toInt() != 0
                if (roomMode && gamemaster) {
                    roomClients[device.address]?.let { cl ->
                        when (descriptor.characteristic.uuid) {
                            Proto.CONTROL_UUID -> cl.subscribedControl = on
                            Proto.STREAM_UUID -> cl.subscribedStream = on
                        }
                    }
                } else if (device.address == serverPeer?.address) {
                    when (descriptor.characteristic.uuid) {
                        Proto.CONTROL_UUID -> peerSubscribedControl = on
                        Proto.STREAM_UUID -> peerSubscribedStream = on
                    }
                }
                if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            onMain {
                if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                if (roomMode && gamemaster) {
                    if (roomClients.containsKey(device.address)) dispatchIncoming(characteristic.uuid, value, fromDevice = device)
                    return@onMain
                }
                if (device.address != serverPeer?.address) return@onMain
                dispatchIncoming(characteristic.uuid, value)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            onMain { serverSendBusy = false; drainServerQueue() }
        }
    }

    // Server-side sends (indicate/notify) must also be one-at-a-time, across every client.
    private val serverQueue = ArrayDeque<Triple<BluetoothDevice, BluetoothGattCharacteristic, ByteArray>>()
    private var serverSendBusy = false

    private fun serverSend(
        device: BluetoothDevice,
        c: BluetoothGattCharacteristic,
        bytes: ByteArray,
        reliable: Boolean,
    ) {
        if (server == null) return
        if (!reliable) {
            // Latest-wins per connection: if a stream frame for THIS device is already
            // waiting, replace it (frames for other clients are independent).
            serverQueue.removeAll {
                it.first.address == device.address && it.second.uuid == Proto.STREAM_UUID
            }
        }
        serverQueue.addLast(Triple(device, c, bytes))
        drainServerQueue()
    }

    private fun drainServerQueue() {
        if (serverSendBusy) return
        val srv = server ?: return
        val (dev, c, bytes) = serverQueue.removeFirstOrNull() ?: return
        val confirm = c.uuid == Proto.CONTROL_UUID // indicate for control, notify for stream
        serverSendBusy = true
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            srv.notifyCharacteristicChanged(dev, c, confirm, bytes) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION") run {
                c.value = bytes
                srv.notifyCharacteristicChanged(dev, c, confirm)
            }
        }
        if (!ok) { serverSendBusy = false }
    }

    /** One indicate to a single room client, using that client's control characteristic. */
    private fun sendRoomTo(cl: RoomClient, bytes: ByteArray) {
        controlChar?.let { serverSend(cl.device, it, bytes, reliable = true) }
    }

    /** One notify to every subscribed room client (one per device, so per-client latest-wins holds). */
    /** Gamemaster only: broadcast a room message to all clients. */
    private fun broadcastRoom(msg: RoomMsg) {
        val bytes = Codec.encodeRoom(msg)
        for (cl in roomClients.values) {
            if (cl.subscribedControl) {
                sendRoomTo(cl, bytes)
            }
        }
    }


    /** Gamemaster only: send [msg] to every client whenever it has it. */
    private fun relayGameFromTurn(turn: Int, payload: ByteArray) {
        val bytes = Codec.encodeGameFrom(payload, turn)
        for (cl in roomClients.values) {
            if (cl.subscribedControl) sendRoomTo(cl, bytes)
        }
    }
    /** Gamemaster only: forward one client's game packet to everyone else, stamped with the sender's turn. */
    private fun relayGame(from: RoomClient, payload: ByteArray) {
        val turn = roster?.turnIndexOf(from.seat) ?: return
        val bytes = Codec.encodeGameFrom(payload, turn)
        for (cl in roomClients.values) {
            if (cl.subscribedControl && cl.device.address != from.device.address) sendRoomTo(cl, bytes)
        }
    }

    // =====================================================================================
    /**
    * Client side: handle room messages (Info, Seat, Unseat, Start) sent by the Gamemaster.
    * Updates the local roster and publishes the RoomState for UI observers.
    */
    private fun handleRoomMsgFromHost(data: ByteArray) {
        val msg = Codec.decodeRoom(data) ?: return
        when (msg) {
            is RoomMsg.Info -> {
                roster = RoomRoster(msg.min, msg.max)
                mySeat = msg.yourSeat
            }
            is RoomMsg.Seat, is RoomMsg.Unseat -> {
                roster?.apply(msg)
            }
            is RoomMsg.Start -> {
                matchStarted = true
            }
        }
        // Publish client view if we have a host nickname and a valid roster.
        val hostNick = roster?.nickOf(0)
        if (hostNick != null) {
            publishClientRoomState(hostNick, roster?.min ?: 0, roster?.max ?: 0)
        }
    }


    private fun dispatchIncoming(uuid: java.util.UUID, data: ByteArray, fromDevice: BluetoothDevice? = null) {
        onMain {
            when (uuid) {
                Proto.CONTROL_UUID -> when {
                    // The Gamemaster: every control packet arrived from one of its clients.
                    roomMode && gamemaster && fromDevice != null -> handleRoomControl(fromDevice, data)
                    // A room client: the Gamemaster's relayed game message carries a one-byte
                    // turn-index stamp before the payload; strip it before the session sees it.
                    roomMode && !gamemaster && data.isNotEmpty() && data[0] == Proto.T_GAME -> {
                        if (data.size >= 2) _control.tryEmit(
                            Control.Game(
                                data.copyOfRange(2, data.size).copyOf(minOf(data.size - 2, Proto.MAX_GAME_PAYLOAD)),
                                data[1].toInt() and 0xFF,
                            ),
                        )
                    }
                    roomMode && !gamemaster && data.isNotEmpty() && data[0] in ROOM_TYPES ->
                        handleRoomMsgFromHost(data)
                    else -> Codec.decodeControl(data)?.let { handleControl(it) }
                }
                // Stream frames carry no sender stamp: a game that cares who sent one puts
                // that in its own payload. The Gamemaster relays them verbatim.
                Proto.STREAM_UUID -> {
                    if (roomMode && gamemaster && fromDevice != null) {
                        _stream.tryEmit(data)
                        if (matchStarted) broadcastStream(data, except = fromDevice)
                    } else {
                        _stream.tryEmit(data)
                    }
                }
            }
        }
    }

    private fun handleControl(msg: Control) {
        when (msg) {
            is Control.Hello -> onHello(msg)
            is Control.Mutual -> onMutual(msg)
            is Control.First -> onFirst(msg)
            Control.Fallback -> onFallback()
            is Control.Accept -> if (weInitiated && !inMutual && !roomMode && _link.value is Link.Requesting) {
                // If we also received THEIR hello, both phones asked (a mutual attempt that fell back
                // to Accept/Decline) and the other phone, having accepted, used the lower-token
                // rule. Using our own token here instead would collide with it about a quarter of
                // the time. With no hello from them this is an ordinary request and we anchor on
                // ourselves, which is what an ordinary acceptor expects.
                val theirs = peerHello
                _link.value = Link.Ready(
                    msg.nick, weInitiated = true, playsFirst = true,
                    mySide = if (theirs != null) Sides.forMutual(myToken, theirs.token) else Sides.forAsker(myToken),
                )
            }
            is Control.Decline -> if (weInitiated && !inMutual) {
                failConnection(
                    when (msg.reason) {
                        Proto.DECLINE_VERSION -> "The other player has a different version of the app"
                        Proto.DECLINE_GAME -> "The other phone is running a different game"
                        Proto.DECLINE_BUSY -> "The other player is already in a game"
                        else -> "The other player declined"
                    }
                )
            }
            is Control.Bye -> {
                if (roomMode && !gamemaster && matchStarted) {
                    // The Gamemaster's match ended (it left); the room itself would just be us.
                    _control.tryEmit(msg)
                    _link.value = Link.Ended("The Gamemaster left")
                    teardownConnections()
                    return
                }
                _control.tryEmit(msg)
                _link.value = Link.Ended("The other player left")
                teardownConnections()
            }
            else -> _control.tryEmit(msg)
        }
    }

    /**
     * Somebody sent us HELLO. Three situations:
     *  1. We are just waiting            -> ordinary incoming request; the user accepts or declines.
     *  2. We are Requesting a phone      -> the two of us asked at the same time (mutual).
     *  3. We are busy / wrong game/version -> decline.
     */
    private fun onHello(hello: Control.Hello) {
        val state = _link.value
        when {
            hello.gameId != gameId -> { replyControl(Control.Decline(Proto.DECLINE_GAME)); dropServerPeerSoon() }
            hello.version != effectiveVersion() -> { replyControl(Control.Decline(Proto.DECLINE_VERSION)); dropServerPeerSoon() }
            state is Link.Requesting -> {
                if (hello.token == myToken) {
                    // A one-in-four-billion tie. Neither phone can be "lower", so refuse to guess:
                    // treat it as an ordinary request and let the user accept it.
                    pendingHello = hello
                    _link.value = Link.Incoming(hello.nick)
                    return
                }
                peerHello = hello
                // Both phones are asking each other: the "we both tapped at once" case.
                connectDeadline.onPeerAskedUsToo()
                // Tell them we saw their request. Sent on OUR outbound link (we are its client).
                sendHandshake(Control.Mutual(myToken))
                maybeEnterMutual()
            }
            state is Link.Discovering -> {
                pendingHello = hello
                peerHello = hello
                _link.value = Link.Incoming(hello.nick)
            }
            else -> replyControl(Control.Decline(Proto.DECLINE_BUSY))
        }
    }

    /** The peer says it saw our HELLO. Together with their HELLO this proves both directions. */
    private fun onMutual(msg: Control.Mutual) {
        if (peerHello?.token != msg.token && peerHello != null) return // not the phone we are talking to
        peerConfirmedMutual = true
        maybeEnterMutual()
    }

    /**
     * Enter the mutual state only when BOTH directions are proven: we sent a HELLO, we received
     * theirs, and they confirmed receiving ours. One-sided inference is exactly what lets two phones
     * disagree, so we require the confirmation.
     */
    private fun maybeEnterMutual() {
        val theirs = peerHello ?: return
        val state = _link.value
        if (!peerConfirmedMutual || inMutual) return
        if (state !is Link.Requesting && state !is Link.Incoming) return
        inMutual = true
        pendingHello = null
        connectDeadline.onResolved() // both directions proven: the handshake connected
        cancelMutualTimeout()
        // The phone with the lower token makes the choice. Both sides compute this identically.
        _link.value = if (myToken < theirs.token) Link.Choosing(theirs.nick) else Link.WaitingForChoice(theirs.nick)
        scheduleMutualTimeout()
    }

    /** The chooser's decision arrived. Both phones now know who plays first. */
    private fun onFirst(msg: Control.First) {
        if (_link.value !is Link.WaitingForChoice) return
        val theirs = peerHello ?: return
        cancelMutualTimeout()
        val iPlayFirst = msg.firstToken == myToken
        resolveSurvivingLink(theirs, iPlayFirst)
    }

    /**
     * The peer could not confirm the mutual attempt. Fall back to an ordinary request from them,
     * which the user can accept or decline. This is what stops the two-generals gap from wedging.
     */
    private fun onFallback() {
        val theirs = peerHello ?: return
        if (_link.value is Link.Ready) return
        cancelMutualTimeout()
        inMutual = false
        peerConfirmedMutual = false
        pendingHello = theirs
        _link.value = Link.Incoming(theirs.nick)
    }

    /** After a wait, a phone stuck expecting the chooser gives up and pulls the other side back. */
    private fun scheduleMutualTimeout() {
        mutualTimeoutPosted = true
        main.postDelayed(mutualTimeout, MUTUAL_TIMEOUT_MS)
    }

    private fun cancelMutualTimeout() {
        mutualTimeoutPosted = false
        main.removeCallbacks(mutualTimeout)
    }

    private val mutualTimeout = Runnable {
        mutualTimeoutPosted = false
        val theirs = peerHello ?: return@Runnable
        when (_link.value) {
            is Link.WaitingForChoice -> {
                sendHandshake(Control.Fallback)
                inMutual = false; peerConfirmedMutual = false
                pendingHello = theirs
                _link.value = Link.Incoming(theirs.nick)
            }
            is Link.Requesting -> {
                // We saw their HELLO but never got their confirmation: let the user accept theirs.
                inMutual = false
                pendingHello = theirs
                _link.value = Link.Incoming(theirs.nick)
            }
            else -> Unit
        }
    }

    override fun accept() = onMain {
        val hello = pendingHello ?: return@onMain
        // Ordinary flow: they asked, we accept, they get the first turn.
        // If we were ALSO requesting them (a fallback from a mutual attempt), we must give up our own
        // outbound link and answer on the link they opened to us.
        val alsoAsked = weInitiated
        abandonOutboundLink()
        replyControl(Control.Accept(localNick))
        stopScanning(); stopAdvertising()
        pendingHello = null
        inMutual = false
        _link.value = Link.Ready(
            hello.nick, weInitiated = false, playsFirst = false,
            mySide = if (alsoAsked) Sides.forMutual(myToken, hello.token) else Sides.forAcceptor(hello.token),
        )
    }

    override fun decline(reason: Int) = onMain {
        if (pendingHello == null) return@onMain
        replyControl(Control.Decline(reason))
        pendingHello = null
        peerHello = null
        peerConfirmedMutual = false
        dropServerPeerSoon()
        abandonOutboundLink()
        _link.value = Link.Discovering
    }

    /**
     * The chooser (lower token) picks who plays first. We tell the other phone, then both collapse
     * to a single link so there is no ambiguity about which connection carries the game.
     */
    override fun chooseFirst(iPlayFirst: Boolean) = onMain {
        if (_link.value !is Link.Choosing) return@onMain
        val theirs = peerHello ?: return@onMain
        cancelMutualTimeout()
        val firstToken = if (iPlayFirst) myToken else theirs.token
        sendHandshake(Control.First(firstToken))
        resolveSurvivingLink(theirs, iPlayFirst)
    }

    /**
     * Two links can exist between the same two phones (A->B and B->A). Keep exactly one, chosen by a
     * rule both phones compute identically: the link opened by the LOWER-token phone survives.
     *   lower phone : keeps its client link  (weInitiated = true),  drops the server-side one
     *   higher phone: keeps its server link  (weInitiated = false), drops its own client link
     * After this, the existing send code (which branches on weInitiated) talks over the same link
     * on both ends.
     */
    private fun resolveSurvivingLink(theirs: Control.Hello, iPlayFirst: Boolean) {
        val iAmLower = myToken < theirs.token
        if (iAmLower) {
            weInitiated = true
            // Keep our client link. The inbound server connection from them is redundant.
            serverPeer?.let { server?.cancelConnection(it) }
            serverPeer = null
            peerSubscribedControl = false; peerSubscribedStream = false
            serverQueue.clear(); serverSendBusy = false
        } else {
            weInitiated = false
            // Keep the server link they opened. Drop the client link we opened.
            abandonOutboundLink()
        }
        stopScanning(); stopAdvertising()
        inMutual = false
        pendingHello = null
        _link.value = Link.Ready(
            theirs.nick, weInitiated = iAmLower, playsFirst = iPlayFirst,
            mySide = Sides.forMutual(myToken, theirs.token),
        )
    }

    /** Close the GATT client connection we opened, if any, without touching the server side. */
    private fun abandonOutboundLink() {
        opQueue.clear(); opBusy = false
        gatt?.let { it.disconnect(); it.close() }
        gatt = null; clientControl = null; clientStream = null
    }

    /**
     * The wire version used for this match's handshake, both claimed and accepted. Normally
     * [Proto.VERSION]; the debug menu can override it to test the mismatch decline.
     */
    private fun effectiveVersion(): Int = versionOverride().coerceIn(0, 255)

    /**
     * A HELLO is at most 31 bytes (7 fixed + 24 nickname). If the MTU negotiation failed and only
     * the default 20-byte payload is available, a long nickname would be silently truncated by the
     * stack and the peer could receive a cut-off or undecodable name. So shrink the nickname here,
     * on a whole-character boundary, until the whole packet fits what we actually negotiated.
     */
    private fun helloFittingPayload(): Control.Hello {
        var nick = localNick
        var hello = Control.Hello(effectiveVersion(), gameId, myToken, nick)
        while (nick.isNotEmpty() && Codec.encode(hello).size > negotiatedPayload) {
            nick = nick.dropLast(1)
            // dropLast(1) can leave half a surrogate pair; sanitizeNick re-clamps on cluster boundaries.
            nick = Codec.sanitizeNick(nick.dropLastWhile { Character.isHighSurrogate(it) })
            hello = Control.Hello(effectiveVersion(), gameId, myToken, nick)
        }
        return hello
    }

    /**
     * Handshake messages during a mutual request are always sent on our OUTBOUND link (we are its
     * client), because that is the one link that certainly exists on both phones at that point.
     */
    private fun sendHandshake(msg: Control) {
        val bytes = Codec.encode(msg)
        enqueue { writeControlAsClient(bytes) }
    }

    private fun replyControl(msg: Control) {
        val dev = serverPeer ?: return
        replyControlTo(dev, Codec.encode(msg))
    }

    /** Send raw control bytes to one device on our server side (the 2-player server peer). */
    private fun replyControlTo(device: BluetoothDevice, bytes: ByteArray) {
        val c = controlChar ?: return
        serverSend(device, c, bytes, reliable = true)
    }

    /**
     * A room client talks only over the one client link it opened to the Gamemaster. If that
     * link is gone (e.g. the 2-player fallback path abandoned it), we re-open it and send once
     * the services are back: because we are already a client of this Gamemaster, the host has
     * seated us before this could happen, so the "already seated" fast path in onRoomHello
     * simply sends [Control.Game] straight away.
     */
    private var clientRequeuePending = false

    private fun sendClientControlOnceServicesReady(bytes: ByteArray) {
        if (gatt != null) {
            enqueue { writeControlAsClient(bytes) }
            return
        }
        val g = adapter?.getRemoteDevice(connectingTo ?: return) ?: return
        clientRequeuePending = true
        g.connectGatt(context, false, clientCallback, BluetoothDevice.TRANSPORT_LE)
    }

    override fun sendControl(msg: Control) = onMain {
        if (roomMode && !gamemaster) {
            // Everything a joiner/outside room says rides the one link to the Gamemaster: the
            // HELLO that seats us, and game messages once the match runs.
            sendClientControlOnceServicesReady(Codec.encode(msg))
            return@onMain
        }
        if (gamemaster) {
when (msg) {
                    // Only [handleRoomControl] reaches in-flight HELLOs; a client's Game packet is
                    // already relayed in dispatch. So a Game at the top level is the Gamemaster's
                    // own, relayed to the whole room stamped with its own turn (0).
                    is Control.Game -> if (matchStarted) relayGameFromTurn(0, msg.payload)
                    else -> if (matchStarted) {
                        val bytes = Codec.encode(msg)
                        for (cl in roomClients.values) if (cl.subscribedControl) sendRoomTo(cl, bytes)
                    }
                }
            return@onMain
        }
        // 2-player match, the normal case, untouched: exactly one link, exactly one peer.
        val bytes = Codec.encode(msg)
        if (weInitiated) enqueue { writeControlAsClient(bytes) }
        else {
            val dev = serverPeer
            if (dev != null) replyControlTo(dev, bytes)
        }
    }

    override fun sendStream(bytes: ByteArray) = onMain {
        if (_link.value !is Link.Ready) return@onMain
        if (roomMode && !gamemaster) {
            // A room client's stream frames ride the same single link; nothing to re-open
            // because a stream frame is only written when the link is already up.
            if (gatt != null && !opBusy && opQueue.isEmpty()) writeStreamAsClient(bytes)
            return@onMain
        }
        if (gamemaster) {
            // Our own frame is consumed locally; the relay of a client's frame happened in dispatch.
            return@onMain
        }
        if (weInitiated) {
            // Only write when the queue is idle; otherwise this frame is already stale.
            if (!opBusy && opQueue.isEmpty()) writeStreamAsClient(bytes)
        } else {
            val dev = serverPeer
            val c = streamChar
            if (dev != null && c != null) serverSend(dev, c, bytes, reliable = false)
        }
    }

    // =====================================================================================
    // Teardown
    // =====================================================================================

    override fun leave() = onMain {
        if (gamemaster && roster != null) {
            // Leaving a room ends the whole match for everyone: the room is closed and every
            // client is disconnected, then this phone goes Idle like a 2-player leaver would.
            stopAdvertising()
            val bye = Codec.encode(Control.Bye)
            for (cl in roomClients.values) if (cl.subscribedControl) sendRoomTo(cl, bye)
            main.postDelayed({
                roomClients.values.forEach { server?.cancelConnection(it.device) }
                roomClients.clear()
                teardownConnections()
                _link.value = Link.Idle
            }, 150)
            return@onMain
        }
        if (roomMode && !gamemaster) {
            // A room client never broadcasts a BYE: its gatt disconnect is the departure.
            stopScanning()
            gatt?.let { it.disconnect(); it.close() }
            gatt = null; clientControl = null; clientStream = null
            teardownConnections()
            _link.value = Link.Idle
            return@onMain
        }
        if (_link.value is Link.Ready) {
            val bye = Codec.encode(Control.Bye)
            if (weInitiated) writeControlAsClient(bye)
            else {
                val dev = serverPeer
                if (dev != null) replyControlTo(dev, bye)
            }
        }
        // Stop being discoverable right away: a phone that has left must not keep advertising
        // "waiting in <game>" while its user is on the menu or in another game.
        stopScanning()
        stopAdvertising()
        // Give the BYE a moment to go out before tearing the link down.
        main.postDelayed({
            teardownConnections()
            _link.value = Link.Idle
        }, 150)
    }

    override fun release() = onMain {
        stopScanning(); stopAdvertising()
        teardownConnections()
        server?.close(); server = null
        main.removeCallbacksAndMessages(null)
        _link.value = Link.Idle
    }

    private fun failConnection(reason: String) {
        teardownConnections()
        _link.value = Link.Ended(reason)
    }

    private fun handleClientDisconnected(g: BluetoothGatt, status: Int) {
        if (g != gatt) return
        if (roomMode && !gamemaster) {
            // The link to the Gamemaster dropped. In a match nobody else plays on for us, so the
            // match is over; in the lobby the room is gone as far as we are concerned.
            val wasMatch = _link.value is Link.Ready
            gatt = null; clientControl = null; clientStream = null
            connectingTo = null
            opQueue.clear(); opBusy = false
            clientRequeuePending = false
            teardownConnections()
            if (wasMatch) {
                _link.value = Link.Ended("Lost connection to the Gamemaster")
            } else {
                _room.value = RoomState.None
                _link.value = Link.Ended("Lost connection to the Gamemaster")
            }
            return
        }
        val was = _link.value
        teardownConnections()
        if (was is Link.Ready || was is Link.Requesting) {
            _link.value = Link.Ended(
                if (was is Link.Ready) "Connection lost" else "Could not reach the other phone (status $status)"
            )
        }
    }

    private fun handleServerPeerGone() {
        val was = _link.value
        serverPeer = null
        pendingHello = null
        peerSubscribedControl = false; peerSubscribedStream = false
        serverQueue.clear(); serverSendBusy = false
        when (was) {
            is Link.Ready, is Link.Incoming -> _link.value = Link.Ended(
                if (was is Link.Ready) "Connection lost" else "The other player cancelled")
            else -> Unit
        }
    }

    /** A peer that connects but never sends HELLO (or isn't our app) must not block the slot. */
    private fun scheduleHelloTimeout(device: BluetoothDevice) {
        main.postDelayed({
            val stillSilent = pendingHello == null && peerHello == null
            val handshakeUnderway = _link.value is Link.Ready || _link.value is Link.Choosing ||
                _link.value is Link.WaitingForChoice || _link.value is Link.Incoming
            if (serverPeer?.address == device.address && stillSilent && !handshakeUnderway) {
                server?.cancelConnection(device)
                serverPeer = null
                peerSubscribedControl = false; peerSubscribedStream = false
                serverQueue.clear(); serverSendBusy = false
            }
        }, HELLO_TIMEOUT_MS)
    }

    private fun dropServerPeerSoon() {
        val dev = serverPeer ?: return
        // Give the DECLINE indication time to be delivered before disconnecting.
        main.postDelayed({ server?.cancelConnection(dev) }, 250)
    }

    private fun teardownConnections() {
        opQueue.clear(); opBusy = false
        gatt?.let { it.disconnect(); it.close() }
        gatt = null; clientControl = null; clientStream = null
        serverPeer?.let { server?.cancelConnection(it) }
        serverPeer = null
        pendingHello = null
        peerSubscribedControl = false; peerSubscribedStream = false
        serverQueue.clear(); serverSendBusy = false
        weInitiated = false; connectingTo = null
        negotiatedPayload = 20
        // Room state is torn down with the connections, not before; it is published on the
        // flows by the moments that end a match (leave / Gamemaster bye / last client gone).
        roomClients.clear()
        roster = null
        mySeat = -1
        roomMode = false
        gamemaster = false
        matchStarted = false
        clientRequeuePending = false
        if (_room.value != RoomState.None) _room.value = RoomState.None
        if (_link.value !is Link.Idle && _room.value == RoomState.None && !roomMode) {
            // nothing
        }
        // Mutual-handshake state belongs to a single attempt; never carry it into the next one.
        cancelMutualTimeout()
        connectDeadline.reset()
        awaitingHelloAck = false
        peerHello = null
        peerConfirmedMutual = false
        inMutual = false
    }

    // =====================================================================================
    // N-player room handling (both ends of the wire fold RoomMsgs through RoomRoster)
    // =====================================================================================

    private val ROOM_TYPES = byteArrayOf(
        Proto.T_ROOM_INFO, Proto.T_SEAT, Proto.T_UNSEAT, Proto.T_ROOM_START,
    )

    /** The Gamemaster's view of the room: everyone in turn order, us flagged as the Gamemaster. */
    private fun publishHostRoom() {
        val r = roster ?: return
        _room.value = RoomState.Hosting(
            r.members.map { RoomMember(it.seat, it.nick, it.seat == mySeat) },
            r.min,
            r.max,
        )
    }

    /** A room client's view: the host is always seat 0, and we are whoever carries [mySeat]. */
    private fun publishClientRoomState(hostNick: String?, min: Int, max: Int) {
        val r = roster
        if (r == null || hostNick == null || mySeat < 0) { _room.value = RoomState.None; return }
        _room.value = RoomState.Joined(
            hostNick,
            r.members.map { RoomMember(it.seat, it.nick, it.seat == mySeat) },
            min,
            max,
        )
    }

    /**
     * Gamemaster only: one control packet from one of our clients. Clients upload only HELLO
     * (joining) and Game payloads (a running match) — everything else is a client mid-handshake,
     * which the host drops; clients never accept, decline, or send room messages.
     */
    private fun handleRoomControl(dev: BluetoothDevice, data: ByteArray) {
        when {
            data.isEmpty() -> return
            data[0] == Proto.T_HELLO ->
                Codec.decodeControl(data)?.let { if (it is Control.Hello) onRoomHello(dev, it) }
            data[0] == Proto.T_GAME && data.size >= 2 -> {
                val cl = roomClients[dev.address] ?: return
                val payload = data.copyOfRange(2, data.size)
                    .copyOf(minOf(data.size - 2, Proto.MAX_GAME_PAYLOAD))
                if (matchStarted && cl.seat >= 0) {
                    val turnIdx = roster?.turnIndexOf(cl.seat) ?: 0
                    _control.tryEmit(Control.Game(payload, turnIdx))
                    // Forward raw packet (with stamp) to other clients.
                    relayRaw(dev, data)
                }
            }
            data[0] == Proto.T_BYE -> roomMemberGone(dev)
            else -> Unit // anything else is a handshake mid-flight on a dead attempt; ignore it
        }
    }

    /**
     * A client says HELLO to the Gamemaster. Version, game, capacity and whether the client has
     * already been seated (it reuses the link) decide the answer.
     */
    private fun onRoomHello(dev: BluetoothDevice, hello: Control.Hello) {
        when {
            hello.gameId != gameId ->
                replyControlTo(dev, Codec.encode(Control.Decline(Proto.DECLINE_GAME)))
            hello.version != effectiveVersion() ->
                replyControlTo(dev, Codec.encode(Control.Decline(Proto.DECLINE_VERSION)))
            else -> {
                val r = roster ?: return
                val existing = roomClients[dev.address]
                when {
                    // Re-hello on an established link (e.g. after a client reconnect): the seat
                    // is already ours. Reply with the current roster and sit up if needed.
                    existing != null && existing.seat >= 0 -> sendRoomRosterTo(dev, r, existing.seat)
                    matchStarted -> {
                        replyControlTo(dev, Codec.encode(Control.Decline(Proto.DECLINE_FULL)))
                        main.postDelayed({ server?.cancelConnection(dev) }, 450)
                        roomClients.remove(dev.address)
                    }
                    else -> {
                        val seat = r.join(hello.nick)
                        if (seat == null) {
                            replyControlTo(dev, Codec.encode(Control.Decline(Proto.DECLINE_FULL)))
                            main.postDelayed({ server?.cancelConnection(dev) }, 450)
                            return
                        }
                        val cl = roomClients.getOrPut(dev.address) { RoomClient(dev) }
                        cl.seat = seat
                        sendRoomRosterTo(dev, r, seat)
                        broadcastRoom(RoomMsg.Seat(seat, hello.nick))
                        publishHostRoom()
                    }
                }
            }
        }
    }

    /** Tell one client where it sits and replay the whole room to it. */
    private fun sendRoomRosterTo(dev: BluetoothDevice, r: RoomRoster, mySeat: Int) {
        val r2 = roomClients[dev.address] ?: return
        if (r2.seat >= 0 && r2.subscribedControl) sendRoomTo(r2, Codec.encodeRoom(RoomMsg.Info(r.min, r.max, r2.seat)))
        for (s in r.seatMessages()) sendRoomTo(r2, Codec.encodeRoom(s))
    }

    /**
     * One of the connected clients left between subscribe and its first HELLO, or never HELLO'd
     * at all: give it a moment, then open the slot it was holding.
     */
    private fun scheduleRoomHelloTimeout(device: BluetoothDevice) {
        main.postDelayed({
            val cl = roomClients[device.address] ?: return@postDelayed
            if (cl.seat < 0) {
                server?.cancelConnection(device)
                roomClients.remove(device.address)
            }
        }, HELLO_TIMEOUT_MS)
    }

    /**
     * A Gamemaster client left (or signed off politely with BYE). The match does not tear down —
     * the room keeps its seats minus this one; a match that needs [min] players is what the game
     * itself enforces, and in a 2-player room a leaver is everyone else by definition.
     */
    private fun roomMemberGone(dev: BluetoothDevice) {
        val cl = roomClients.remove(dev.address) ?: return
        val r = roster ?: return
        if (cl.seat >= 0) {
            r.drop(cl.seat)
            broadcastRoom(RoomMsg.Unseat(cl.seat))
        }
        publishHostRoom()
    }

    /**
     * Gamemaster: relay one client's raw game packet to everyone else. The sender's turn is
     * already stamped in [data] by [handleRoomControl] before this is called; the sender itself
     * is excluded.
     */
    private fun relayRaw(sender: BluetoothDevice, data: ByteArray) {
        for (cl in roomClients.values) {
            if (cl.subscribedControl && cl.device.address != sender.address) sendRoomTo(cl, data)
        }
    }

    private fun resetConnectionState() {
        teardownConnections()
        seen.clear(); _peers.value = emptyList()
    }

    // =====================================================================================
    // Client op queue + main-thread helper
    // =====================================================================================

    private fun enqueue(op: () -> Unit) {
        opQueue.addLast(op)
        if (!opBusy) runNextOp()
    }

    private fun runNextOp() {
        val op = opQueue.removeFirstOrNull() ?: run { opBusy = false; return }
        opBusy = true
        op()
    }

    private fun opDone() {
        opBusy = false
        runNextOp()
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }

    companion object {
        /** 0xFFFF is the Bluetooth SIG "reserved for testing" company id; fine for a hobby app. */
        private const val MANUFACTURER_ID = 0xFFFF
        private const val PEER_TTL_MS = 6000L
        private const val HELLO_TIMEOUT_MS = 5000L
        /** How long a phone waits for the other side of a mutual request before falling back to Accept/Decline. */
        private const val MUTUAL_TIMEOUT_MS = 6000L

        /** Waits before each quiet advertising retry: about 3.5 s in total before we show an error. */
        private val ADVERTISE_RETRY_DELAYS_MS = longArrayOf(400L, 900L, 2200L)
    }
}
