package com.example.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
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
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Build
import android.os.ParcelUuid
import com.example.ai.GeminiDiagnosticWatcher
import com.example.data.DebugLogger
import com.example.data.MediaStorageHelper
import com.example.model.BleProximityState
import com.example.model.ContactCard
import com.example.model.WatcherHealthStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BluetoothLeManager(private val context: Context) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000FD9A-0000-1000-8000-00805F9B34FB")
        val CHAR_DATA_UUID: UUID = UUID.fromString("0000FD9B-0000-1000-8000-00805F9B34FB")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        private const val TRIGGER_COOLDOWN_MS = 8_000L
        private const val RSSI_WINDOW_MS = 2_500L
        private const val CANDIDATE_STALE_MS = 10_000L
        private const val CLASSIC_DISCOVERY_PULSE_MS = 8_000L
        private const val ACCEPTANCE_TIMEOUT_MS = 25_000L
        private const val TRANSFER_ACK_TIMEOUT_MS = 20_000L
        private const val GATT_CONNECT_TIMEOUT_MS = 10_000L

        private val PHONE_MANUFACTURER_IDS = setOf(
            0x004C, // Apple (iPhone / Continuity)
            0x00E0, // Google
            0x0075, // Samsung
            0x027D, // Huawei
            0x038F  // Xiaomi
        )

        private val PHONE_NAME_HINTS = listOf(
            "pixel", "galaxy", "iphone", "huawei", "mate", "honor",
            "oneplus", "xiaomi", "redmi", "poco", "nothing", "moto",
            "sony", "nokia", "oppo", "vivo", "realme", "phone"
        )

        fun isQualifiedSharePeer(
            isNameDropPeer: Boolean,
            smoothedRssi: Int,
            threshold: Int,
            ageMs: Long,
            maxAgeMs: Long = CANDIDATE_STALE_MS
        ): Boolean {
            return isNameDropPeer && ageMs in 0..maxAgeMs && smoothedRssi >= threshold
        }

        fun formatTargetDisplayName(rawName: String, address: String): String {
            val clean = rawName.trim().ifBlank { "Name Drop Phone" }
            val shortId = address.takeLast(5).uppercase()
            return if (shortId.isNotBlank() && !clean.contains(shortId, ignoreCase = true)) {
                "$clean ($shortId)"
            } else {
                clean
            }
        }
    }

    data class DiscoveredTarget(
        val address: String,
        val displayName: String,
        val rawName: String,
        val rssi: Int,
        val isNameDropPeer: Boolean,
        val lastSeenMs: Long
    )

    private data class CandidateSignal(
        val address: String,
        val rawName: String,
        val displayName: String,
        val device: BluetoothDevice,
        val rssi: Int,
        val smoothedRssi: Int,
        val isNameDropPeer: Boolean,
        val isBonded: Boolean,
        val lastSeenMs: Long
    ) {
        val priorityTier: Int
            get() = when {
                isNameDropPeer -> 3
                isBonded -> 2
                else -> 1
            }
    }

    private data class OutgoingTransferSession(
        val transferId: String,
        val targetAddress: String,
        val targetName: String,
        val responseDeferred: CompletableDeferred<Triple<Boolean, String, String?>> = CompletableDeferred(),
        val ackDeferred: CompletableDeferred<Pair<Boolean, String?>> = CompletableDeferred()
    )

    sealed interface BleEvent {
        data class ProximityMatched(
            val deviceAddress: String,
            val deviceName: String,
            val rssi: Int,
            val isNameDropPeer: Boolean
        ) : BleEvent

        data class ContactReceived(
            val deviceAddress: String,
            val deviceName: String,
            val contact: ContactCard,
            val photoBytes: ByteArray?,
            val backgroundPhotoBytes: ByteArray?,
            val rssi: Int
        ) : BleEvent

        data class MutualShareHandshakeReceived(
            val deviceAddress: String,
            val deviceName: String,
            val contact: ContactCard,
            val photoBytes: ByteArray?,
            val backgroundPhotoBytes: ByteArray?,
            val rssi: Int
        ) : BleEvent

        data class PhotoOfferRequested(
            val transferId: String,
            val deviceAddress: String,
            val senderName: String,
            val fileName: String,
            val mimeType: String,
            val isVideo: Boolean,
            val fileSizeBytes: Long,
            val isGroupShare: Boolean
        ) : BleEvent

        data class PhotoOfferReceived(
            val transferId: String?,
            val deviceAddress: String,
            val senderName: String,
            val fileName: String,
            val mimeType: String,
            val isVideo: Boolean,
            val photoBytes: ByteArray,
            val isGroupShare: Boolean,
            val verifiedSha256: Boolean
        ) : BleEvent

        data class TransferRequested(
            val transferId: String,
            val deviceAddress: String,
            val targetName: String,
            val rssi: Int
        ) : BleEvent

        data class TransferAccepted(
            val transferId: String,
            val peerName: String
        ) : BleEvent

        data class TransferRejectedOrTimedOut(
            val transferId: String,
            val peerName: String,
            val declined: Boolean,
            val reason: String
        ) : BleEvent

        data class TransferProgress(
            val transferId: String?,
            val progress: Float
        ) : BleEvent

        data class TransferCompleted(
            val transferId: String?,
            val success: Boolean,
            val peerName: String,
            val reason: String? = null
        ) : BleEvent

        data class RadioStateChanged(val bluetoothOn: Boolean, val locationOn: Boolean) : BleEvent
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private val _state = MutableStateFlow(BleProximityState())
    val state: StateFlow<BleProximityState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<BleEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<BleEvent> = _events.asSharedFlow()

    private var scanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var gattServerChar: BluetoothGattCharacteristic? = null
    private var activeClientGatt: BluetoothGatt? = null

    private val discoveredDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val nameDropPeerAddresses = ConcurrentHashMap.newKeySet<String>()
    private val bondedAddresses = ConcurrentHashMap.newKeySet<String>()
    private val candidateSignals = ConcurrentHashMap<String, CandidateSignal>()
    private val rssiHistory = ConcurrentHashMap<String, MutableList<Pair<Long, Int>>>()
    private val incomingChunkBuffers = ConcurrentHashMap<String, ByteArrayOutputStream>()

    private val connectedServerDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val subscribedNotifyAddresses = ConcurrentHashMap.newKeySet<String>()
    private val transferPeerDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val activeOutgoingTransfers = ConcurrentHashMap<String, OutgoingTransferSession>()

    @Volatile
    private var currentContactCard: ContactCard = ContactCard()

    @Volatile
    private var lastTriggerTimestampMs: Long = 0L

    @Volatile
    private var proximityTriggerLocked: Boolean = false

    @Volatile
    private var receiverRegistered: Boolean = false

    @Volatile
    private var lastLowPowerMode: Boolean? = null

    @Volatile
    private var advertiseAttemptedWithoutName: Boolean = false

    private var classicPulseJob: Job? = null

    private val systemStateReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val stateInt = intent.getIntExtra(
                        BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.ERROR
                    )
                    val btOn = stateInt == BluetoothAdapter.STATE_ON
                    DebugLogger.log("BLE", "Bluetooth adapter state changed -> ON=$btOn")
                    if (!btOn) {
                        stopAll()
                    } else {
                        refreshBondedDevices()
                    }
                    _events.tryEmit(
                        BleEvent.RadioStateChanged(
                            bluetoothOn = btOn,
                            locationOn = BlePermissions.isLocationServicesEnabled(context)
                        )
                    )
                }

                LocationManager.MODE_CHANGED_ACTION,
                LocationManager.PROVIDERS_CHANGED_ACTION -> {
                    val locOn = BlePermissions.isLocationServicesEnabled(context)
                    DebugLogger.log("BLE", "Location mode changed -> ON=$locOn")
                    _events.tryEmit(
                        BleEvent.RadioStateChanged(
                            bluetoothOn = bluetoothAdapter?.isEnabled == true,
                            locationOn = locOn
                        )
                    )
                }

                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    refreshBondedDevices()
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (action == BluetoothDevice.ACTION_ACL_CONNECTED && device != null) {
                        val name = safeDeviceName(device, null)
                        DebugLogger.log("BLE", "Bluetooth ACL link seen for $name")
                        if (_state.value.isNameDropEnabled && !_state.value.isScanning) {
                            startProximity(lowPowerBackground = false, forceRestart = true)
                        }
                    }
                }

                BluetoothDevice.ACTION_FOUND -> {
                    if (!_state.value.isNameDropEnabled &&
                        !_state.value.isShareEnabled &&
                        !_state.value.isReceiveListening
                    ) return
                    val device: BluetoothDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    } ?: return
                    val rssiShort = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                    if (rssiShort == Short.MIN_VALUE) return
                    processDiscoveredSignal(
                        device = device,
                        scanRecord = null,
                        rssi = rssiShort.toInt(),
                        fromClassicDiscovery = true
                    )
                }
            }
        }
    }

    fun setProximityTriggerLocked(locked: Boolean, resetCooldown: Boolean = false) {
        proximityTriggerLocked = locked
        if (!locked && resetCooldown) {
            lastTriggerTimestampMs = System.currentTimeMillis()
        }
        GeminiDiagnosticWatcher.onProximityLockChanged(locked)
        DebugLogger.log("BLE", "Proximity trigger lock=$locked")
    }

    fun clearCooldownForImmediateShare() {
        proximityTriggerLocked = false
        lastTriggerTimestampMs = 0L
        GeminiDiagnosticWatcher.onProximityLockChanged(false)
    }

    fun getQualifiedNameDropTargets(requireThreshold: Boolean = true): List<DiscoveredTarget> {
        val now = System.currentTimeMillis()
        val threshold = _state.value.rssiThreshold
        candidateSignals.entries.removeIf { now - it.value.lastSeenMs > CANDIDATE_STALE_MS }

        return candidateSignals.values
            .filter { sig ->
                if (requireThreshold) {
                    isQualifiedSharePeer(
                        isNameDropPeer = sig.isNameDropPeer,
                        smoothedRssi = sig.smoothedRssi,
                        threshold = threshold,
                        ageMs = now - sig.lastSeenMs,
                        maxAgeMs = CANDIDATE_STALE_MS
                    )
                } else {
                    sig.isNameDropPeer && (now - sig.lastSeenMs <= CANDIDATE_STALE_MS)
                }
            }
            .sortedByDescending { it.smoothedRssi }
            .map {
                DiscoveredTarget(
                    address = it.address,
                    displayName = it.displayName,
                    rawName = it.rawName,
                    rssi = it.smoothedRssi,
                    isNameDropPeer = it.isNameDropPeer,
                    lastSeenMs = it.lastSeenMs
                )
            }
    }

    fun getBestQualifiedNameDropPeer(requireThreshold: Boolean = true): DiscoveredTarget? {
        val qualified = getQualifiedNameDropTargets(requireThreshold = requireThreshold).firstOrNull()
        val bestOverall = candidateSignals.values.maxWithOrNull(
            compareBy<CandidateSignal> { it.priorityTier }.thenBy { it.smoothedRssi }
        )
        GeminiDiagnosticWatcher.onTargetDiscoveryEvaluated(
            targetName = qualified?.displayName ?: bestOverall?.displayName,
            address = qualified?.address ?: bestOverall?.address,
            rssi = qualified?.rssi ?: bestOverall?.smoothedRssi,
            threshold = _state.value.rssiThreshold,
            isNameDropPeer = qualified?.isNameDropPeer ?: bestOverall?.isNameDropPeer ?: false,
            qualified = qualified != null
        )
        return qualified
    }

    fun hasDiscoveredTarget(): Boolean =
        getQualifiedNameDropTargets(requireThreshold = true).isNotEmpty()

    fun getBestPeerSnapshot(): Pair<String, Int>? {
        val target = getBestQualifiedNameDropPeer(requireThreshold = true) ?: return null
        return target.displayName to target.rssi
    }

    fun registerRadioReceiver() {
        if (receiverRegistered) return
        runCatching {
            val filter = IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(LocationManager.MODE_CHANGED_ACTION)
                addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_FOUND)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(systemStateReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(systemStateReceiver, filter)
            }
            receiverRegistered = true
        }
        refreshBondedDevices()
    }

    fun unregisterRadioReceiver() {
        if (!receiverRegistered) return
        runCatching { context.unregisterReceiver(systemStateReceiver) }
        receiverRegistered = false
    }

    @SuppressLint("MissingPermission")
    fun refreshBondedDevices() {
        val adapter = bluetoothAdapter ?: return
        if (!BlePermissions.hasConnectPermission(context)) return
        runCatching {
            val bonded = adapter.bondedDevices.orEmpty()
            bondedAddresses.clear()
            val names = mutableListOf<String>()
            bonded.forEach { dev ->
                val major = runCatching { dev.bluetoothClass?.majorDeviceClass }.getOrNull()
                val isAudioOrWatch = major == BluetoothClass.Device.Major.AUDIO_VIDEO ||
                    major == BluetoothClass.Device.Major.WEARABLE ||
                    major == BluetoothClass.Device.Major.HEALTH
                if (!isAudioOrWatch) {
                    dev.address?.let { bondedAddresses.add(it) }
                }
                val label = runCatching { dev.name }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: dev.address
                names.add(label)
            }
            _state.value = _state.value.copy(
                bondedDeviceCount = bonded.size,
                pairedDeviceNames = names.take(6)
            )
        }
    }

    fun updateContactCard(card: ContactCard) {
        currentContactCard = card
    }

    fun updateRssiThreshold(threshold: Int) {
        _state.value = _state.value.copy(rssiThreshold = threshold.coerceIn(-70, -30))
    }

    fun setModes(
        nameDropEnabled: Boolean,
        shareEnabled: Boolean,
        receiveListening: Boolean = _state.value.isReceiveListening,
        awaitingMutualShare: Boolean = _state.value.isAwaitingMutualShare
    ) {
        _state.value = _state.value.copy(
            isNameDropEnabled = nameDropEnabled,
            isShareEnabled = shareEnabled,
            isReceiveListening = receiveListening,
            isAwaitingMutualShare = awaitingMutualShare
        )
    }

    fun startProximity(lowPowerBackground: Boolean, forceRestart: Boolean = false) {
        registerRadioReceiver()
        refreshBondedDevices()

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            _state.value = _state.value.copy(
                isScanning = false,
                isAdvertising = false,
                statusMessage = "Bluetooth is turned off",
                lastScanError = "Bluetooth adapter is disabled"
            )
            DebugLogger.log("BLE", "Cannot start proximity: Bluetooth adapter disabled", "WARN")
            return
        }

        if (!BlePermissions.hasScanPermission(context) ||
            !BlePermissions.hasConnectPermission(context) ||
            !BlePermissions.hasAdvertisePermission(context)
        ) {
            _state.value = _state.value.copy(
                isScanning = false,
                isAdvertising = false,
                statusMessage = "Missing Bluetooth runtime permissions",
                lastScanError = "Grant Nearby Devices / Bluetooth permissions"
            )
            DebugLogger.log("BLE", "Cannot start proximity: Bluetooth permissions missing", "WARN")
            return
        }

        if (!BlePermissions.isLocationServicesEnabled(context)) {
            _state.value = _state.value.copy(
                isScanning = false,
                statusMessage = "Enable Location toggle for BLE scanning",
                lastScanError = "System Location Services are OFF"
            )
            DebugLogger.log("BLE", "Location Services OFF; scan results may be blocked", "WARN")
            return
        }

        val alreadyRunningSameMode = _state.value.isScanning &&
            lastLowPowerMode == lowPowerBackground &&
            !forceRestart
        if (alreadyRunningSameMode) {
            return
        }
        lastLowPowerMode = lowPowerBackground

        startGattServerSafe()
        advertiseAttemptedWithoutName = false
        startAdvertisingSafe(lowPowerBackground, includeDeviceName = true)
        startScanningSafe(lowPowerBackground)

        if (!lowPowerBackground) {
            pulsePairedDeviceDiscovery()
        }
    }

    fun ensureLowLatencyScan() {
        val now = System.currentTimeMillis()
        candidateSignals.entries.removeIf { now - it.value.lastSeenMs > CANDIDATE_STALE_MS }
        startProximity(lowPowerBackground = false, forceRestart = true)
    }

    fun restartScanNow() {
        lastTriggerTimestampMs = 0L
        candidateSignals.clear()
        discoveredDevices.clear()
        rssiHistory.clear()
        _state.value = _state.value.copy(
            nearestDeviceName = null,
            nearestDeviceAddress = null,
            nearestRssi = null,
            smoothedRssi = null,
            nearbySignalCount = 0,
            matchedPeerIsNameDropApp = false,
            isMatchedPeerReady = false,
            lastScanError = null
        )
        startProximity(lowPowerBackground = false, forceRestart = true)
    }

    @SuppressLint("MissingPermission")
    fun pulsePairedDeviceDiscovery() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled || !BlePermissions.hasScanPermission(context)) return
        classicPulseJob?.cancel()
        classicPulseJob = scope.launch {
            runCatching {
                if (adapter.isDiscovering) adapter.cancelDiscovery()
                val started = adapter.startDiscovery()
                DebugLogger.log("BLE", "Paired/Classic RSSI discovery pulse started=$started")
                delay(CLASSIC_DISCOVERY_PULSE_MS)
                if (adapter.isDiscovering) adapter.cancelDiscovery()
            }
        }
    }

    fun stopAll() {
        classicPulseJob?.cancel()
        cancelClassicDiscoverySafe()
        lastLowPowerMode = null
        stopScanningSafe()
        stopAdvertisingSafe()
        closeGattServerSafe()
        closeClientGattSafe()
        candidateSignals.clear()
        discoveredDevices.clear()
        rssiHistory.clear()
        connectedServerDevices.clear()
        subscribedNotifyAddresses.clear()
        transferPeerDevices.clear()
        activeOutgoingTransfers.clear()
        _state.value = _state.value.copy(
            isScanning = false,
            isAdvertising = false,
            nearestDeviceName = null,
            nearestDeviceAddress = null,
            nearestRssi = null,
            smoothedRssi = null,
            nearbySignalCount = 0,
            matchedPeerIsNameDropApp = false,
            isMatchedPeerReady = false,
            statusMessage = "Standby"
        )
        DebugLogger.log("BLE", "Stopped scanning, advertising, and GATT connections cleanly")
    }

    @SuppressLint("MissingPermission")
    private fun cancelClassicDiscoverySafe() {
        if (!BlePermissions.hasScanPermission(context)) return
        runCatching {
            val adapter = bluetoothAdapter ?: return
            if (adapter.isDiscovering) adapter.cancelDiscovery()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScanningSafe(lowPowerBackground: Boolean) {
        if (!BlePermissions.hasScanPermission(context)) return
        try {
            val adapter = bluetoothAdapter ?: return
            val leScanner = adapter.bluetoothLeScanner ?: run {
                _state.value = _state.value.copy(
                    isScanning = false,
                    lastScanError = "BluetoothLeScanner unavailable"
                )
                return
            }
            scanner = leScanner
            runCatching { leScanner.stopScan(scanCallback) }

            val scanMode = if (lowPowerBackground) {
                ScanSettings.SCAN_MODE_LOW_POWER
            } else {
                ScanSettings.SCAN_MODE_LOW_LATENCY
            }

            val settings = ScanSettings.Builder()
                .setScanMode(scanMode)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                .setReportDelay(0L)
                .build()

            val filters: List<ScanFilter>? = if (lowPowerBackground) {
                listOf(
                    ScanFilter.Builder()
                        .setServiceUuid(ParcelUuid(SERVICE_UUID))
                        .build()
                )
            } else {
                null
            }

            leScanner.startScan(filters, settings, scanCallback)
            GeminiDiagnosticWatcher.onScanStarted()
            _state.value = _state.value.copy(
                isScanning = true,
                lastScanError = null,
                statusMessage = if (lowPowerBackground) "Background BLE active" else "Scanning for Name Drop phones"
            )
            DebugLogger.log(
                "BLE",
                "Started BLE scan (${if (lowPowerBackground) "LOW_POWER filtered" else "LOW_LATENCY open"}, threshold=${_state.value.rssiThreshold} dBm)"
            )
        } catch (se: SecurityException) {
            _state.value = _state.value.copy(isScanning = false, lastScanError = se.message)
            DebugLogger.log("BLE", "SecurityException starting scan: ${se.message}", "ERROR")
            GeminiDiagnosticWatcher.onScanFailed(context, -1, "SecurityException: ${se.message}", _state.value)
        } catch (e: Exception) {
            _state.value = _state.value.copy(isScanning = false, lastScanError = e.message)
            DebugLogger.log("BLE", "Failed starting scan: ${e.message}", "ERROR")
            GeminiDiagnosticWatcher.onScanFailed(context, -2, e.message ?: "Scan start error", _state.value)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanningSafe() {
        if (!BlePermissions.hasScanPermission(context)) return
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertisingSafe(lowPowerBackground: Boolean, includeDeviceName: Boolean) {
        if (!BlePermissions.hasAdvertisePermission(context)) return
        try {
            val adapter = bluetoothAdapter ?: return
            val leAdvertiser = adapter.bluetoothLeAdvertiser ?: run {
                DebugLogger.log("BLE", "Device does not support BLE peripheral advertising", "WARN")
                return
            }
            advertiser = leAdvertiser
            runCatching { leAdvertiser.stopAdvertising(advertiseCallback) }

            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(
                    if (lowPowerBackground) {
                        AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
                    } else {
                        AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
                    }
                )
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .build()

            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()

            if (includeDeviceName) {
                val scanResponse = AdvertiseData.Builder()
                    .setIncludeDeviceName(true)
                    .build()
                leAdvertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
            } else {
                leAdvertiser.startAdvertising(settings, data, advertiseCallback)
            }
        } catch (se: SecurityException) {
            DebugLogger.log("BLE", "SecurityException starting advertising: ${se.message}", "ERROR")
        } catch (e: Exception) {
            DebugLogger.log("BLE", "Failed starting advertising: ${e.message}", "ERROR")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertisingSafe() {
        if (!BlePermissions.hasAdvertisePermission(context)) return
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGattServerSafe() {
        if (!BlePermissions.hasConnectPermission(context)) return
        if (gattServer != null) return
        try {
            val server = bluetoothManager?.openGattServer(context, gattServerCallback) ?: return
            val service = BluetoothGattService(
                SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )
            val charData = BluetoothGattCharacteristic(
                CHAR_DATA_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or
                    BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ or
                    BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            val cccd = BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or
                    BluetoothGattDescriptor.PERMISSION_WRITE
            )
            charData.addDescriptor(cccd)
            service.addCharacteristic(charData)
            server.addService(service)
            gattServer = server
            gattServerChar = charData
            DebugLogger.log("GATT", "GATT Server started cleanly with CCCD notify support")
        } catch (se: SecurityException) {
            DebugLogger.log("GATT", "SecurityException starting GATT server: ${se.message}", "ERROR")
        } catch (e: Exception) {
            DebugLogger.log("GATT", "Failed starting GATT server: ${e.message}", "ERROR")
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGattServerSafe() {
        if (!BlePermissions.hasConnectPermission(context)) {
            gattServer = null
            gattServerChar = null
            return
        }
        runCatching { gattServer?.close() }
        gattServer = null
        gattServerChar = null
    }

    @SuppressLint("MissingPermission")
    private fun closeClientGattSafe() {
        if (!BlePermissions.hasConnectPermission(context)) {
            activeClientGatt = null
            return
        }
        runCatching {
            activeClientGatt?.disconnect()
            activeClientGatt?.close()
        }
        activeClientGatt = null
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            GeminiDiagnosticWatcher.onScanResultSeen()
            val device = result.device ?: return
            processDiscoveredSignal(
                device = device,
                scanRecord = result.scanRecord,
                rssi = result.rssi,
                fromClassicDiscovery = false
            )
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            if (results.isNotEmpty()) GeminiDiagnosticWatcher.onScanResultSeen()
            results.forEach { res ->
                res.device?.let { dev ->
                    processDiscoveredSignal(dev, res.scanRecord, res.rssi, fromClassicDiscovery = false)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            DebugLogger.log("BLE", "BLE scan failed with code=$errorCode", "ERROR")
            val nextState = _state.value.copy(
                isScanning = false,
                statusMessage = "Scan error ($errorCode)",
                lastScanError = "BLE scan error code $errorCode"
            )
            _state.value = nextState
            GeminiDiagnosticWatcher.onScanFailed(
                context = context,
                errorCode = errorCode,
                reason = "ScanCallback.onScanFailed($errorCode)",
                bleState = nextState
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun processDiscoveredSignal(
        device: BluetoothDevice,
        scanRecord: ScanRecord?,
        rssi: Int,
        fromClassicDiscovery: Boolean
    ) {
        val address = device.address ?: return
        val hasNameDropUuid = scanRecord?.serviceUuids?.any { it.uuid == SERVICE_UUID } == true
        if (hasNameDropUuid) {
            nameDropPeerAddresses.add(address)
        }
        val isNameDropPeer = hasNameDropUuid || nameDropPeerAddresses.contains(address)

        val bondState = if (BlePermissions.hasConnectPermission(context)) {
            runCatching { device.bondState }.getOrDefault(BluetoothDevice.BOND_NONE)
        } else {
            BluetoothDevice.BOND_NONE
        }
        val isBonded = bondState == BluetoothDevice.BOND_BONDED || bondedAddresses.contains(address)

        val majorClass = if (BlePermissions.hasConnectPermission(context)) {
            runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull()
        } else {
            null
        }
        val isAudioOrWearable = majorClass == BluetoothClass.Device.Major.AUDIO_VIDEO ||
            majorClass == BluetoothClass.Device.Major.WEARABLE ||
            majorClass == BluetoothClass.Device.Major.HEALTH

        if (!isNameDropPeer && isAudioOrWearable) return

        val rawName = safeDeviceName(device, scanRecord)
        val isPhoneCandidate = isNameDropPeer ||
            isBonded ||
            majorClass == BluetoothClass.Device.Major.PHONE ||
            hasPhoneManufacturerData(scanRecord) ||
            PHONE_NAME_HINTS.any { rawName.contains(it, ignoreCase = true) }

        if (!isPhoneCandidate) return
        if (fromClassicDiscovery && !isNameDropPeer && !isBonded) return

        discoveredDevices[address] = device
        val now = System.currentTimeMillis()
        val samples = rssiHistory.getOrPut(address) { mutableListOf() }
        synchronized(samples) {
            samples.add(now to rssi)
            samples.removeAll { now - it.first > RSSI_WINDOW_MS }
        }
        val smoothed = synchronized(samples) {
            if (samples.isEmpty()) rssi else samples.map { it.second }.average().toInt()
        }

        if (isNameDropPeer || isBonded || smoothed >= -68) {
            val displayName = formatTargetDisplayName(rawName, address)
            candidateSignals[address] = CandidateSignal(
                address = address,
                rawName = rawName,
                displayName = displayName,
                device = device,
                rssi = rssi,
                smoothedRssi = smoothed,
                isNameDropPeer = isNameDropPeer,
                isBonded = isBonded,
                lastSeenMs = now
            )
        }

        candidateSignals.entries.removeIf { now - it.value.lastSeenMs > CANDIDATE_STALE_MS }

        val threshold = _state.value.rssiThreshold
        val bestNameDrop = candidateSignals.values
            .filter { it.isNameDropPeer }
            .maxByOrNull { it.smoothedRssi }
        val bestDisplay = bestNameDrop ?: candidateSignals.values.maxWithOrNull(
            compareBy<CandidateSignal> { it.priorityTier }.thenBy { it.smoothedRssi }
        )

        if (bestDisplay != null) {
            val qualifiedReady = bestNameDrop != null && bestNameDrop.smoothedRssi >= threshold
            _state.value = _state.value.copy(
                nearestDeviceName = bestDisplay.displayName,
                nearestDeviceAddress = bestDisplay.address,
                nearestRssi = bestDisplay.rssi,
                smoothedRssi = bestDisplay.smoothedRssi,
                nearbySignalCount = candidateSignals.values.count { it.isNameDropPeer },
                matchedPeerIsNameDropApp = bestDisplay.isNameDropPeer,
                isMatchedPeerReady = qualifiedReady,
                statusMessage = when {
                    _state.value.isReceiveListening -> "Waiting for nearby device... (${bestDisplay.smoothedRssi} dBm)"
                    qualifiedReady -> "Ready: ${bestDisplay.displayName} (${bestDisplay.smoothedRssi} dBm)"
                    bestDisplay.isNameDropPeer -> "Move closer to ${bestDisplay.displayName} (${bestDisplay.smoothedRssi} dBm)"
                    else -> "Scanning for Name Drop phones..."
                }
            )
        } else {
            _state.value = _state.value.copy(
                nearestDeviceName = null,
                nearestDeviceAddress = null,
                nearestRssi = null,
                smoothedRssi = null,
                nearbySignalCount = 0,
                matchedPeerIsNameDropApp = false,
                isMatchedPeerReady = false
            )
        }

        // Strict active-card lock: do NOT re-trigger proximity animation/transfer while card is open
        if (proximityTriggerLocked) return

        // ONLY trigger proximity events for verified Name Drop peers within RSSI threshold
        val triggerTarget = bestNameDrop ?: return
        val shouldTrigger = isQualifiedSharePeer(
            isNameDropPeer = triggerTarget.isNameDropPeer,
            smoothedRssi = triggerTarget.smoothedRssi,
            threshold = threshold,
            ageMs = now - triggerTarget.lastSeenMs
        )

        if (shouldTrigger && now - lastTriggerTimestampMs >= TRIGGER_COOLDOWN_MS) {
            lastTriggerTimestampMs = now
            proximityTriggerLocked = true
            GeminiDiagnosticWatcher.onProximityLockChanged(true)
            GeminiDiagnosticWatcher.onProximityMatched()
            cancelClassicDiscoverySafe()
            DebugLogger.log(
                "BLE",
                "Verified Name Drop peer matched: ${triggerTarget.displayName} RSSI=${triggerTarget.rssi} (smoothed=${triggerTarget.smoothedRssi} >= $threshold)"
            )
            _events.tryEmit(
                BleEvent.ProximityMatched(
                    deviceAddress = triggerTarget.address,
                    deviceName = triggerTarget.displayName,
                    rssi = triggerTarget.smoothedRssi,
                    isNameDropPeer = true
                )
            )
            connectAndReadContact(
                device = triggerTarget.device,
                deviceName = triggerTarget.displayName,
                rssi = triggerTarget.smoothedRssi
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun safeDeviceName(device: BluetoothDevice, scanRecord: ScanRecord?): String {
        val btName = if (BlePermissions.hasConnectPermission(context)) {
            runCatching { device.name }.getOrNull()
        } else {
            null
        }
        return btName?.takeIf { it.isNotBlank() }
            ?: scanRecord?.deviceName?.takeIf { it.isNotBlank() }
            ?: "Name Drop Phone"
    }

    private fun hasPhoneManufacturerData(scanRecord: ScanRecord?): Boolean {
        val map = scanRecord?.manufacturerSpecificData ?: return false
        for (i in 0 until map.size()) {
            if (PHONE_MANUFACTURER_IDS.contains(map.keyAt(i))) {
                return true
            }
        }
        return false
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            _state.value = _state.value.copy(isAdvertising = true)
            DebugLogger.log("BLE", "Advertising started (TX HIGH, SERVICE_UUID)")
        }

        override fun onStartFailure(errorCode: Int) {
            DebugLogger.log("BLE", "Advertising failed code=$errorCode", "WARN")
            if (!advertiseAttemptedWithoutName) {
                advertiseAttemptedWithoutName = true
                startAdvertisingSafe(
                    lowPowerBackground = lastLowPowerMode ?: false,
                    includeDeviceName = false
                )
            } else {
                val nextState = _state.value.copy(isAdvertising = false)
                _state.value = nextState
                GeminiDiagnosticWatcher.onAdvertisingFailed(context, errorCode, nextState)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectAndReadContact(device: BluetoothDevice, deviceName: String, rssi: Int) {
        if (!BlePermissions.hasConnectPermission(context)) return
        cancelClassicDiscoverySafe()
        closeClientGattSafe()
        try {
            activeClientGatt = device.connectGatt(
                context,
                false,
                object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(
                        gatt: BluetoothGatt,
                        status: Int,
                        newState: Int
                    ) {
                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            DebugLogger.log("GATT", "Connected to $deviceName, negotiating MTU")
                            runCatching { gatt.requestMtu(512) }
                            runCatching { gatt.discoverServices() }
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            DebugLogger.log("GATT", "Disconnected from $deviceName")
                            runCatching { gatt.close() }
                            if (activeClientGatt === gatt) activeClientGatt = null
                        }
                    }

                    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            val ch = gatt.getService(SERVICE_UUID)
                                ?.getCharacteristic(CHAR_DATA_UUID)
                            if (ch != null) {
                                runCatching { gatt.readCharacteristic(ch) }
                            } else {
                                runCatching { gatt.disconnect() }
                            }
                        } else {
                            runCatching { gatt.disconnect() }
                        }
                    }

                    @Deprecated("Deprecated in API 33")
                    override fun onCharacteristicRead(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        status: Int
                    ) {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            @Suppress("DEPRECATION")
                            val data = characteristic.value ?: ByteArray(0)
                            handleDecodedGattBytes(device.address, deviceName, data, rssi)
                        }
                        runCatching { gatt.disconnect() }
                    }

                    override fun onCharacteristicRead(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        value: ByteArray,
                        status: Int
                    ) {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            handleDecodedGattBytes(device.address, deviceName, value, rssi)
                        }
                        runCatching { gatt.disconnect() }
                    }
                },
                BluetoothDevice.TRANSPORT_LE
            )
        } catch (e: Exception) {
            DebugLogger.log("GATT", "GATT client error: ${e.message}", "ERROR")
        }
    }

    private fun handleDecodedGattBytes(
        address: String,
        deviceName: String,
        bytes: ByteArray,
        rssi: Int
    ) {
        val decoded = BleChunkProtocol.decodePayload(bytes) ?: return
        val friendlyName = candidateSignals[address]?.displayName ?: formatTargetDisplayName(deviceName, address)
        if (!decoded.verifiedSha256) {
            DebugLogger.log("GATT", "Payload SHA-256 verification mismatch from $friendlyName", "WARN")
        }
        when (decoded.type) {
            BleChunkProtocol.TYPE_CONTACT_CARD -> {
                decoded.contact?.let { card ->
                    _events.tryEmit(
                        BleEvent.ContactReceived(
                            deviceAddress = address,
                            deviceName = friendlyName,
                            contact = card,
                            photoBytes = decoded.photoBytes,
                            backgroundPhotoBytes = decoded.backgroundPhotoBytes,
                            rssi = rssi
                        )
                    )
                }
            }

            BleChunkProtocol.TYPE_SHARE_HANDSHAKE_READY -> {
                decoded.contact?.let { card ->
                    _events.tryEmit(
                        BleEvent.MutualShareHandshakeReceived(
                            deviceAddress = address,
                            deviceName = friendlyName,
                            contact = card,
                            photoBytes = decoded.photoBytes,
                            backgroundPhotoBytes = decoded.backgroundPhotoBytes,
                            rssi = rssi
                        )
                    )
                }
            }

            BleChunkProtocol.TYPE_TRANSFER_REQUEST -> {
                val tid = decoded.transferId ?: return
                GeminiDiagnosticWatcher.onTransferHandshakeEvent(
                    context = context,
                    stage = "REQUEST_RECEIVED",
                    transferId = tid,
                    targetName = decoded.senderName ?: friendlyName,
                    detail = "Incoming ${if (decoded.isVideo) "video" else "photo"} (${decoded.byteCount} bytes) waiting for user Accept",
                    bleState = _state.value
                )
                _events.tryEmit(
                    BleEvent.PhotoOfferRequested(
                        transferId = tid,
                        deviceAddress = address,
                        senderName = decoded.senderName ?: friendlyName,
                        fileName = decoded.fileName ?: "NameDrop_Media",
                        mimeType = decoded.mimeType,
                        isVideo = decoded.isVideo,
                        fileSizeBytes = decoded.byteCount,
                        isGroupShare = decoded.isGroupShare
                    )
                )
            }

            BleChunkProtocol.TYPE_TRANSFER_RESPONSE -> {
                val tid = decoded.transferId ?: return
                val session = activeOutgoingTransfers[tid] ?: return
                val responder = decoded.senderName ?: friendlyName
                session.responseDeferred.complete(
                    Triple(decoded.accepted, responder, decoded.responseReason)
                )
            }

            BleChunkProtocol.TYPE_TRANSFER_ACK -> {
                val tid = decoded.transferId ?: return
                val session = activeOutgoingTransfers[tid] ?: return
                session.ackDeferred.complete(decoded.ackSuccess to decoded.responseReason)
            }

            BleChunkProtocol.TYPE_PHOTO_SHARE,
            BleChunkProtocol.TYPE_GROUP_PHOTO_SHARE,
            BleChunkProtocol.TYPE_VIDEO_SHARE,
            BleChunkProtocol.TYPE_GROUP_VIDEO_SHARE -> {
                val mediaBytes = decoded.photoBytes ?: return
                val tid = decoded.transferId
                if (!tid.isNullOrBlank()) {
                    sendTransferAck(
                        transferId = tid,
                        peerAddress = address,
                        success = decoded.verifiedSha256,
                        responderName = currentContactCard.displayName.ifBlank { friendlyName },
                        reason = if (decoded.verifiedSha256) null else "SHA-256 verification failed"
                    )
                }
                _events.tryEmit(
                    BleEvent.PhotoOfferReceived(
                        transferId = tid,
                        deviceAddress = address,
                        senderName = decoded.senderName ?: friendlyName,
                        fileName = decoded.fileName ?: if (decoded.isVideo) "NameDrop_Video.mp4" else "NameDrop_Photo.jpg",
                        mimeType = decoded.mimeType,
                        isVideo = decoded.isVideo,
                        photoBytes = mediaBytes,
                        isGroupShare = decoded.isGroupShare,
                        verifiedSha256 = decoded.verifiedSha256
                    )
                )
            }
        }
    }

    private fun pickBestQualifiedDevice(): CandidateSignal? {
        val now = System.currentTimeMillis()
        val threshold = _state.value.rssiThreshold
        return candidateSignals.values
            .filter {
                isQualifiedSharePeer(
                    isNameDropPeer = it.isNameDropPeer,
                    smoothedRssi = it.smoothedRssi,
                    threshold = threshold,
                    ageMs = now - it.lastSeenMs
                )
            }
            .maxByOrNull { it.smoothedRssi }
    }

    fun sendContactToNearestPeer(card: ContactCard, asMutualHandshake: Boolean = false): Pair<Boolean, String?> {
        val target = pickBestQualifiedDevice()
        if (target == null) {
            GeminiDiagnosticWatcher.onTargetDiscoveryEvaluated(
                targetName = _state.value.nearestDeviceName,
                address = _state.value.nearestDeviceAddress,
                rssi = _state.value.smoothedRssi,
                threshold = _state.value.rssiThreshold,
                isNameDropPeer = _state.value.matchedPeerIsNameDropApp,
                qualified = false
            )
            DebugLogger.log("GATT", "No qualified Name Drop peer in range for contact push", "WARN")
            return false to null
        }
        scope.launch {
            val photoBytes = MediaStorageHelper.readContactPhotoBytesForBle(card.photoPath)
            val bgBytes = if (card.fullScreenBackgroundEnabled) {
                MediaStorageHelper.readContactPhotoBytesForBle(card.backgroundPhotoPath, maxBytes = 18_000)
            } else {
                null
            }
            val payload = if (asMutualHandshake) {
                BleChunkProtocol.encodeMutualShareHandshake(card, photoBytes)
            } else {
                BleChunkProtocol.encodeContactPayload(card, photoBytes, bgBytes)
            }
            writeOneShotPayloadToDevice(target.device, payload, target.displayName)
        }
        return true to target.displayName
    }

    /**
     * Initiates a verified 2-way media transfer to a real discovered Name Drop target:
     * 1. Requires a live, in-range Name Drop peer (`isNameDropPeer == true` and `smoothedRssi >= threshold`).
     * 2. Sends TYPE_TRANSFER_REQUEST and enters "Waiting for [Device Name] to accept...".
     * 3. Streams media chunks ONLY after receiving TYPE_TRANSFER_RESPONSE(accepted = true).
     * 4. Emits TransferCompleted(success = true) ONLY after receiving TYPE_TRANSFER_ACK(success = true).
     */
    fun requestMediaTransferToTarget(
        targetAddress: String?,
        senderName: String,
        fileName: String,
        mediaBytes: ByteArray,
        mimeType: String,
        isVideo: Boolean,
        isGroupShare: Boolean
    ): DiscoveredTarget? {
        val now = System.currentTimeMillis()
        val threshold = _state.value.rssiThreshold
        val chosen = if (!targetAddress.isNullOrBlank()) {
            candidateSignals[targetAddress]?.takeIf {
                isQualifiedSharePeer(
                    isNameDropPeer = it.isNameDropPeer,
                    smoothedRssi = it.smoothedRssi,
                    threshold = threshold,
                    ageMs = now - it.lastSeenMs
                )
            }
        } else {
            null
        } ?: pickBestQualifiedDevice()

        if (chosen == null) {
            GeminiDiagnosticWatcher.onTargetDiscoveryEvaluated(
                targetName = _state.value.nearestDeviceName,
                address = _state.value.nearestDeviceAddress,
                rssi = _state.value.smoothedRssi,
                threshold = threshold,
                isNameDropPeer = _state.value.matchedPeerIsNameDropApp,
                qualified = false
            )
            return null
        }

        val targetInfo = DiscoveredTarget(
            address = chosen.address,
            displayName = chosen.displayName,
            rawName = chosen.rawName,
            rssi = chosen.smoothedRssi,
            isNameDropPeer = chosen.isNameDropPeer,
            lastSeenMs = chosen.lastSeenMs
        )

        val transferId = UUID.randomUUID().toString().replace("-", "").take(12)
        val session = OutgoingTransferSession(
            transferId = transferId,
            targetAddress = targetInfo.address,
            targetName = targetInfo.displayName
        )
        activeOutgoingTransfers[transferId] = session

        GeminiDiagnosticWatcher.onTransferHandshakeEvent(
            context = context,
            stage = "TARGET_LOCKED",
            transferId = transferId,
            targetName = targetInfo.displayName,
            detail = "Discovered verified Name Drop peer ${targetInfo.displayName} at ${targetInfo.rssi} dBm; requesting receiver Accept",
            bleState = _state.value
        )
        _events.tryEmit(
            BleEvent.TransferRequested(
                transferId = transferId,
                deviceAddress = targetInfo.address,
                targetName = targetInfo.displayName,
                rssi = targetInfo.rssi
            )
        )

        scope.launch {
            runTwoPhaseMediaTransfer(
                session = session,
                device = chosen.device,
                senderName = senderName,
                fileName = fileName,
                mediaBytes = mediaBytes,
                mimeType = mimeType,
                isVideo = isVideo,
                isGroupShare = isGroupShare
            )
        }

        return targetInfo
    }

    @SuppressLint("MissingPermission")
    private suspend fun runTwoPhaseMediaTransfer(
        session: OutgoingTransferSession,
        device: BluetoothDevice,
        senderName: String,
        fileName: String,
        mediaBytes: ByteArray,
        mimeType: String,
        isVideo: Boolean,
        isGroupShare: Boolean
    ) {
        val tid = session.transferId
        if (!BlePermissions.hasConnectPermission(context)) {
            val reason = "Missing BLUETOOTH_CONNECT permission"
            GeminiDiagnosticWatcher.onTransferHandshakeEvent(
                context, "PERM_ERROR", tid, session.targetName, reason, _state.value, captureSnapshot = true
            )
            activeOutgoingTransfers.remove(tid)
            _events.tryEmit(BleEvent.TransferCompleted(tid, false, session.targetName, reason))
            return
        }

        GeminiDiagnosticWatcher.onHandshakeStarted()
        cancelClassicDiscoverySafe()

        val readyChannel = CompletableDeferred<Pair<BluetoothGatt, BluetoothGattCharacteristic>?>()
        var currentChunkAck: CompletableDeferred<Boolean>? = null
        var activeGatt: BluetoothGatt? = null

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    runCatching { gatt.requestMtu(512) }
                    runCatching { gatt.discoverServices() }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (!readyChannel.isCompleted) {
                        readyChannel.complete(null)
                    }
                    currentChunkAck?.complete(false)
                    runCatching { gatt.close() }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val ch = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHAR_DATA_UUID)
                if (status != BluetoothGatt.GATT_SUCCESS || ch == null) {
                    if (!readyChannel.isCompleted) readyChannel.complete(null)
                    return
                }
                runCatching { gatt.setCharacteristicNotification(ch, true) }
                val cccd = ch.getDescriptor(CCCD_UUID)
                if (cccd != null) {
                    val wroteDesc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                            android.bluetooth.BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(cccd)
                    }
                    if (!wroteDesc && !readyChannel.isCompleted) {
                        readyChannel.complete(gatt to ch)
                    }
                } else if (!readyChannel.isCompleted) {
                    readyChannel.complete(gatt to ch)
                }
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                val ch = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHAR_DATA_UUID)
                if (!readyChannel.isCompleted) {
                    readyChannel.complete(if (ch != null) gatt to ch else null)
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                currentChunkAck?.complete(status == BluetoothGatt.GATT_SUCCESS)
            }

            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                @Suppress("DEPRECATION")
                val value = characteristic.value ?: return
                handleDecodedGattBytes(device.address, session.targetName, value, _state.value.smoothedRssi ?: -48)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                handleDecodedGattBytes(device.address, session.targetName, value, _state.value.smoothedRssi ?: -48)
            }
        }

        try {
            activeGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            val pair = withTimeoutOrNull(GATT_CONNECT_TIMEOUT_MS) { readyChannel.await() }
            if (pair == null) {
                val reason = "Could not connect to ${session.targetName} over Bluetooth GATT"
                GeminiDiagnosticWatcher.onTransferHandshakeEvent(
                    context, "GATT_CONNECT_FAILED", tid, session.targetName, reason, _state.value,
                    captureSnapshot = true, status = WatcherHealthStatus.STALL_DETECTED
                )
                GeminiDiagnosticWatcher.onHandshakeCompleted(false, reason)
                activeOutgoingTransfers.remove(tid)
                runCatching { activeGatt?.close() }
                _events.tryEmit(BleEvent.TransferRejectedOrTimedOut(tid, session.targetName, false, reason))
                return
            }

            val (gatt, ch) = pair

            // Step 1: Write lightweight Transfer Request and wait for Receiver Accept/Decline
            val requestFrame = BleChunkProtocol.encodeTransferRequest(
                transferId = tid,
                senderName = senderName,
                fileName = fileName,
                mimeType = mimeType,
                isVideo = isVideo,
                byteCount = mediaBytes.size.toLong(),
                isGroupShare = isGroupShare
            )
            val reqChunks = BleChunkProtocol.chunkBytes(requestFrame)
            for (chunk in reqChunks) {
                val ack = CompletableDeferred<Boolean>()
                currentChunkAck = ack
                val initiated = writeCharacteristicCompat(gatt, ch, chunk)
                val ok = if (initiated) withTimeoutOrNull(5_000L) { ack.await() } == true else false
                if (!ok) {
                    val reason = "Failed delivering share request to ${session.targetName}"
                    GeminiDiagnosticWatcher.onTransferHandshakeEvent(
                        context, "REQUEST_WRITE_FAILED", tid, session.targetName, reason, _state.value,
                        captureSnapshot = true
                    )
                    GeminiDiagnosticWatcher.onHandshakeCompleted(false, reason)
                    activeOutgoingTransfers.remove(tid)
                    runCatching { gatt.disconnect(); gatt.close() }
                    _events.tryEmit(BleEvent.TransferRejectedOrTimedOut(tid, session.targetName, false, reason))
                    return
                }
            }

            GeminiDiagnosticWatcher.onTransferHandshakeEvent(
                context, "AWAITING_ACCEPT", tid, session.targetName,
                "Waiting for ${session.targetName} to tap Accept", _state.value
            )

            val response = withTimeoutOrNull(ACCEPTANCE_TIMEOUT_MS) { session.responseDeferred.await() }
            if (response == null) {
                val reason = "No response from ${session.targetName} (timed out)"
                GeminiDiagnosticWatcher.onTransferHandshakeEvent(
                    context, "ACCEPT_TIMEOUT", tid, session.targetName, reason, _state.value,
                    captureSnapshot = true, status = WatcherHealthStatus.STALL_DETECTED
                )
                GeminiDiagnosticWatcher.onHandshakeCompleted(false, reason)
                activeOutgoingTransfers.remove(tid)
                runCatching { gatt.disconnect(); gatt.close() }
                _events.tryEmit(BleEvent.TransferRejectedOrTimedOut(tid, session.targetName, false, reason))
                return
            }

            val (accepted, responderName, declineReason) = response
            if (!accepted) {
                val reason = declineReason ?: "$responderName declined"
                GeminiDiagnosticWatcher.onTransferHandshakeEvent(
                    context, "DECLINED", tid, responderName, reason, _state.value, captureSnapshot = false
                )
                GeminiDiagnosticWatcher.onHandshakeCompleted(false, reason)
                activeOutgoingTransfers.remove(tid)
                runCatching { gatt.disconnect(); gatt.close() }
                _events.tryEmit(BleEvent.TransferRejectedOrTimedOut(tid, responderName, true, reason))
                return
            }

            // Step 2: Receiver Accepted -> Transition to Sharing & stream media bytes
            GeminiDiagnosticWatcher.onTransferHandshakeEvent(
                context, "ACCEPTED", tid, responderName,
                "Accepted by $responderName; streaming ${mediaBytes.size} bytes", _state.value
            )
            _events.tryEmit(BleEvent.TransferAccepted(tid, responderName))

            val payload = BleChunkProtocol.encodeMediaPayload(
                senderName = senderName,
                fileName = fileName,
                mediaBytes = mediaBytes,
                mimeType = mimeType,
                isVideo = isVideo,
                isGroupShare = isGroupShare,
                transferId = tid
            )
            val dataChunks = BleChunkProtocol.chunkBytes(payload)
            for (idx in dataChunks.indices) {
                val ack = CompletableDeferred<Boolean>()
                currentChunkAck = ack
                val initiated = writeCharacteristicCompat(gatt, ch, dataChunks[idx])
                val ok = if (initiated) withTimeoutOrNull(6_000L) { ack.await() } == true else false
                if (!ok) {
                    val reason = "Transfer interrupted at chunk ${idx + 1}/${dataChunks.size} with $responderName"
                    GeminiDiagnosticWatcher.onTransferHandshakeEvent(
                        context, "CHUNK_WRITE_FAILED", tid, responderName, reason, _state.value, captureSnapshot = true
                    )
                    GeminiDiagnosticWatcher.onHandshakeCompleted(false, reason)
                    activeOutgoingTransfers.remove(tid)
                    runCatching { gatt.disconnect(); gatt.close() }
                    _events.tryEmit(BleEvent.TransferCompleted(tid, false, responderName, reason))
                    return
                }
                val progress = ((idx + 1).toFloat() / dataChunks.size.toFloat()).coerceIn(0.08f, 0.96f)
                _events.tryEmit(BleEvent.TransferProgress(tid, progress))
            }

            // Step 3: Wait for verified Receiver ACK before ever marking Shared
            val ackResult = withTimeoutOrNull(TRANSFER_ACK_TIMEOUT_MS) { session.ackDeferred.await() }
            activeOutgoingTransfers.remove(tid)
            runCatching { gatt.disconnect(); gatt.close() }

            if (ackResult == null || !ackResult.first) {
                val reason = ackResult?.second ?: "Transfer not acknowledged by $responderName"
                GeminiDiagnosticWatcher.onTransferHandshakeEvent(
                    context, "ACK_MISSING", tid, responderName, reason, _state.value, captureSnapshot = true
                )
                GeminiDiagnosticWatcher.onHandshakeCompleted(false, reason)
                _events.tryEmit(BleEvent.TransferCompleted(tid, false, responderName, reason))
            } else {
                GeminiDiagnosticWatcher.onTransferHandshakeEvent(
                    context, "ACK_VERIFIED", tid, responderName,
                    "Transfer completed & verified by $responderName", _state.value
                )
                GeminiDiagnosticWatcher.onHandshakeCompleted(true, null)
                _events.tryEmit(BleEvent.TransferCompleted(tid, true, responderName, null))
            }
        } catch (e: Exception) {
            val reason = "GATT transfer exception: ${e.message}"
            GeminiDiagnosticWatcher.onTransferHandshakeEvent(
                context, "EXCEPTION", tid, session.targetName, reason, _state.value, captureSnapshot = true
            )
            GeminiDiagnosticWatcher.onHandshakeCompleted(false, reason)
            activeOutgoingTransfers.remove(tid)
            runCatching { activeGatt?.close() }
            _events.tryEmit(BleEvent.TransferCompleted(tid, false, session.targetName, reason))
        }
    }

    fun respondToMediaTransferRequest(
        transferId: String,
        peerAddress: String?,
        accepted: Boolean,
        responderName: String
    ) {
        val frame = BleChunkProtocol.encodeTransferResponse(
            transferId = transferId,
            accepted = accepted,
            responderName = responderName,
            reason = if (accepted) null else "$responderName declined"
        )
        scope.launch {
            sendControlFrameToPeer(transferId, peerAddress, frame)
            GeminiDiagnosticWatcher.onTransferHandshakeEvent(
                context = context,
                stage = if (accepted) "RECEIVER_ACCEPTED" else "RECEIVER_DECLINED",
                transferId = transferId,
                targetName = responderName,
                detail = "Sent TYPE_TRANSFER_RESPONSE(accepted=$accepted) back to sender",
                bleState = _state.value
            )
        }
    }

    fun sendTransferAck(
        transferId: String,
        peerAddress: String?,
        success: Boolean,
        responderName: String,
        reason: String? = null
    ) {
        val frame = BleChunkProtocol.encodeTransferAck(
            transferId = transferId,
            success = success,
            responderName = responderName,
            reason = reason
        )
        scope.launch {
            sendControlFrameToPeer(transferId, peerAddress, frame)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendControlFrameToPeer(
        transferId: String,
        peerAddress: String?,
        frame: ByteArray
    ) {
        if (!BlePermissions.hasConnectPermission(context)) return
        val device = transferPeerDevices[transferId]
            ?: peerAddress?.let { connectedServerDevices[it] ?: discoveredDevices[it] ?: runCatching { bluetoothAdapter?.getRemoteDevice(it) }.getOrNull() }
            ?: return

        val ch = gattServerChar
        val server = gattServer
        if (server != null && ch != null && connectedServerDevices.containsKey(device.address)) {
            val notified = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    server.notifyCharacteristicChanged(device, ch, false, frame) ==
                        android.bluetooth.BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    ch.value = frame
                    @Suppress("DEPRECATION")
                    server.notifyCharacteristicChanged(device, ch, false)
                }
            }.getOrDefault(false)
            if (notified) {
                DebugLogger.log("GATT", "Delivered control frame ($transferId) via GATT notify")
                return
            }
        }

        // Fallback: open brief client GATT write to sender's GATT server
        writeOneShotPayloadToDevice(device, frame, device.address ?: "Sender")
    }

    @SuppressLint("MissingPermission")
    private fun writeCharacteristicCompat(
        gatt: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        chunk: ByteArray
    ): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    ch,
                    chunk,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ) == android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                ch.value = chunk
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(ch)
            }
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    private fun writeOneShotPayloadToDevice(device: BluetoothDevice, payload: ByteArray, peerLabel: String) {
        if (!BlePermissions.hasConnectPermission(context)) return
        cancelClassicDiscoverySafe()
        val chunks = BleChunkProtocol.chunkBytes(payload)
        try {
            device.connectGatt(
                context,
                false,
                object : BluetoothGattCallback() {
                    private var chunkIndex = 0

                    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            runCatching { gatt.requestMtu(512) }
                            runCatching { gatt.discoverServices() }
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            runCatching { gatt.close() }
                        }
                    }

                    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                        val ch = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHAR_DATA_UUID)
                        if (status == BluetoothGatt.GATT_SUCCESS && ch != null && chunks.isNotEmpty()) {
                            writeNextChunk(gatt, ch)
                        } else {
                            runCatching { gatt.disconnect() }
                        }
                    }

                    override fun onCharacteristicWrite(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        status: Int
                    ) {
                        if (status == BluetoothGatt.GATT_SUCCESS && chunkIndex < chunks.size) {
                            writeNextChunk(gatt, characteristic)
                        } else {
                            runCatching { gatt.disconnect() }
                        }
                    }

                    private fun writeNextChunk(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic) {
                        if (chunkIndex >= chunks.size) {
                            runCatching { gatt.disconnect() }
                            return
                        }
                        val chunk = chunks[chunkIndex++]
                        if (!writeCharacteristicCompat(gatt, ch, chunk)) {
                            runCatching { gatt.disconnect() }
                        }
                    }
                },
                BluetoothDevice.TRANSPORT_LE
            )
        } catch (e: Exception) {
            DebugLogger.log("GATT", "One-shot write error ($peerLabel): ${e.message}", "ERROR")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val addr = device.address ?: return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedServerDevices[addr] = device
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedServerDevices.remove(addr)
                subscribedNotifyAddresses.remove(addr)
                incomingChunkBuffers.remove(addr)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (!BlePermissions.hasConnectPermission(context)) return
            device.address?.let { subscribedNotifyAddresses.add(it) }
            if (responseNeeded) {
                runCatching {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (!BlePermissions.hasConnectPermission(context)) return
            val photoBytes = MediaStorageHelper.readContactPhotoBytesForBle(currentContactCard.photoPath)
            val bgBytes = if (currentContactCard.fullScreenBackgroundEnabled) {
                MediaStorageHelper.readContactPhotoBytesForBle(currentContactCard.backgroundPhotoPath, maxBytes = 18_000)
            } else {
                null
            }
            val payload = BleChunkProtocol.encodeContactPayload(currentContactCard, photoBytes, bgBytes)
            val slice = if (offset < payload.size) {
                payload.copyOfRange(offset, payload.size)
            } else {
                ByteArray(0)
            }
            runCatching {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (!BlePermissions.hasConnectPermission(context)) return
            val addr = device.address
            if (value != null && addr != null) {
                val stream = incomingChunkBuffers.getOrPut(addr) { ByteArrayOutputStream() }
                synchronized(stream) {
                    stream.write(value)
                    var accumulated = stream.toByteArray()
                    while (true) {
                        val expectedLen = BleChunkProtocol.expectedTotalFrameLength(accumulated) ?: break
                        if (accumulated.size < expectedLen) break
                        val frame = accumulated.copyOfRange(0, expectedLen)
                        val remaining = if (accumulated.size > expectedLen) {
                            accumulated.copyOfRange(expectedLen, accumulated.size)
                        } else {
                            ByteArray(0)
                        }
                        stream.reset()
                        if (remaining.isNotEmpty()) {
                            stream.write(remaining)
                        } else {
                            incomingChunkBuffers.remove(addr)
                        }

                        val decoded = BleChunkProtocol.decodePayload(frame)
                        if (decoded?.type == BleChunkProtocol.TYPE_TRANSFER_REQUEST && decoded.transferId != null) {
                            transferPeerDevices[decoded.transferId] = device
                        }
                        handleDecodedGattBytes(
                            address = addr,
                            deviceName = runCatching { device.name }.getOrNull() ?: "Name Drop Phone",
                            bytes = frame,
                            rssi = _state.value.smoothedRssi ?: _state.value.rssiThreshold
                        )
                        accumulated = remaining
                        if (accumulated.isEmpty()) break
                    }
                }
            }
            if (responseNeeded) {
                runCatching {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
            }
        }
    }
}
