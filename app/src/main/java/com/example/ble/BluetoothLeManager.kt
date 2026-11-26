package com.example.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
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
import com.example.data.DebugLogger
import com.example.data.MediaStorageHelper
import com.example.model.BleProximityState
import com.example.model.ContactCard
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
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BluetoothLeManager(private val context: Context) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000FD9A-0000-1000-8000-00805F9B34FB")
        val CHAR_DATA_UUID: UUID = UUID.fromString("0000FD9B-0000-1000-8000-00805F9B34FB")
        private const val TRIGGER_COOLDOWN_MS = 8_000L
        private const val RSSI_WINDOW_MS = 2_500L
        private const val CANDIDATE_STALE_MS = 5_000L
        private const val CLASSIC_DISCOVERY_PULSE_MS = 8_000L

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
    }

    private data class CandidateSignal(
        val address: String,
        val name: String,
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

        data class PhotoOfferReceived(
            val senderName: String,
            val fileName: String,
            val mimeType: String,
            val isVideo: Boolean,
            val photoBytes: ByteArray,
            val isGroupShare: Boolean
        ) : BleEvent

        data class TransferProgress(val progress: Float) : BleEvent
        data class TransferCompleted(val success: Boolean, val peerName: String) : BleEvent
        data class RadioStateChanged(val bluetoothOn: Boolean, val locationOn: Boolean) : BleEvent
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private val _state = MutableStateFlow(BleProximityState())
    val state: StateFlow<BleProximityState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<BleEvent>(extraBufferCapacity = 24)
    val events: SharedFlow<BleEvent> = _events.asSharedFlow()

    private var scanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var activeClientGatt: BluetoothGatt? = null

    private val discoveredDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val nameDropPeerAddresses = ConcurrentHashMap.newKeySet<String>()
    private val bondedAddresses = ConcurrentHashMap.newKeySet<String>()
    private val candidateSignals = ConcurrentHashMap<String, CandidateSignal>()
    private val rssiHistory = ConcurrentHashMap<String, MutableList<Pair<Long, Int>>>()
    private val incomingChunkBuffers = ConcurrentHashMap<String, ByteArrayOutputStream>()

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

    /**
     * Strict Active Card / Cooldown Lock:
     * When locked = true, ignores repeated BLE proximity triggers while phones remain held together.
     * Call with locked = false when the user dismisses the card or starts an explicit new share.
     */
    fun setProximityTriggerLocked(locked: Boolean, resetCooldown: Boolean = false) {
        proximityTriggerLocked = locked
        if (!locked && resetCooldown) {
            lastTriggerTimestampMs = System.currentTimeMillis()
        }
        DebugLogger.log("BLE", "Proximity trigger lock=$locked")
    }

    fun clearCooldownForImmediateShare() {
        proximityTriggerLocked = false
        lastTriggerTimestampMs = 0L
    }

    fun hasDiscoveredTarget(): Boolean =
        candidateSignals.isNotEmpty() || discoveredDevices.isNotEmpty()

    fun getBestPeerSnapshot(): Pair<String, Int>? {
        val best = candidateSignals.values.maxWithOrNull(
            compareBy<CandidateSignal> { it.priorityTier }.thenBy { it.smoothedRssi }
        ) ?: return null
        return best.name to best.smoothedRssi
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
                    discoveredDevices.putIfAbsent(dev.address, dev)
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

    fun restartScanNow() {
        lastTriggerTimestampMs = 0L
        candidateSignals.clear()
        rssiHistory.clear()
        _state.value = _state.value.copy(
            nearestDeviceName = null,
            nearestRssi = null,
            smoothedRssi = null,
            nearbySignalCount = 0,
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
        rssiHistory.clear()
        _state.value = _state.value.copy(
            isScanning = false,
            isAdvertising = false,
            nearestDeviceName = null,
            nearestRssi = null,
            smoothedRssi = null,
            nearbySignalCount = 0,
            matchedPeerIsNameDropApp = false,
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
            _state.value = _state.value.copy(
                isScanning = true,
                lastScanError = null,
                statusMessage = if (lowPowerBackground) "Background BLE active" else "Scanning nearby phones"
            )
            DebugLogger.log(
                "BLE",
                "Started BLE scan (${if (lowPowerBackground) "LOW_POWER filtered" else "LOW_LATENCY open"}, threshold=${_state.value.rssiThreshold} dBm)"
            )
        } catch (se: SecurityException) {
            _state.value = _state.value.copy(isScanning = false, lastScanError = se.message)
            DebugLogger.log("BLE", "SecurityException starting scan: ${se.message}", "ERROR")
        } catch (e: Exception) {
            _state.value = _state.value.copy(isScanning = false, lastScanError = e.message)
            DebugLogger.log("BLE", "Failed starting scan: ${e.message}", "ERROR")
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
            service.addCharacteristic(charData)
            server.addService(service)
            gattServer = server
            DebugLogger.log("GATT", "GATT Server started cleanly")
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
            return
        }
        runCatching { gattServer?.close() }
        gattServer = null
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
            val device = result.device ?: return
            processDiscoveredSignal(
                device = device,
                scanRecord = result.scanRecord,
                rssi = result.rssi,
                fromClassicDiscovery = false
            )
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { res ->
                res.device?.let { dev ->
                    processDiscoveredSignal(dev, res.scanRecord, res.rssi, fromClassicDiscovery = false)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            DebugLogger.log("BLE", "BLE scan failed with code=$errorCode", "ERROR")
            _state.value = _state.value.copy(
                isScanning = false,
                statusMessage = "Scan error ($errorCode)",
                lastScanError = "BLE scan error code $errorCode"
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

        if (!isPhoneCandidate && rssi < -55) return
        if (fromClassicDiscovery && !isBonded && majorClass != BluetoothClass.Device.Major.PHONE) return

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

        if (isNameDropPeer || isBonded || smoothed >= -72) {
            val label = when {
                isNameDropPeer -> "$rawName (Name Drop)"
                isBonded -> "$rawName (Paired)"
                else -> rawName
            }
            candidateSignals[address] = CandidateSignal(
                address = address,
                name = label,
                device = device,
                rssi = rssi,
                smoothedRssi = smoothed,
                isNameDropPeer = isNameDropPeer,
                isBonded = isBonded,
                lastSeenMs = now
            )
        }

        candidateSignals.entries.removeIf { now - it.value.lastSeenMs > CANDIDATE_STALE_MS }

        val best = candidateSignals.values.maxWithOrNull(
            compareBy<CandidateSignal> { it.priorityTier }.thenBy { it.smoothedRssi }
        )

        if (best != null) {
            _state.value = _state.value.copy(
                nearestDeviceName = best.name,
                nearestRssi = best.rssi,
                smoothedRssi = best.smoothedRssi,
                nearbySignalCount = candidateSignals.size,
                matchedPeerIsNameDropApp = best.isNameDropPeer,
                statusMessage = when {
                    _state.value.isReceiveListening -> "Waiting for nearby device... (${best.smoothedRssi} dBm)"
                    best.isNameDropPeer -> "Name Drop phone nearby"
                    best.isBonded -> "Paired phone detected"
                    else -> "Nearby phone signal"
                }
            )
        }

        // Strict active-card lock: do NOT re-trigger proximity animation/transfer while card is open
        if (proximityTriggerLocked) return

        val threshold = _state.value.rssiThreshold
        val triggerTarget = best ?: return
        val shouldTrigger = if (triggerTarget.isNameDropPeer || triggerTarget.isBonded) {
            triggerTarget.smoothedRssi >= threshold
        } else {
            triggerTarget.smoothedRssi >= maxOf(threshold, -52)
        }

        if (shouldTrigger && now - lastTriggerTimestampMs >= TRIGGER_COOLDOWN_MS) {
            lastTriggerTimestampMs = now
            proximityTriggerLocked = true
            cancelClassicDiscoverySafe()
            DebugLogger.log(
                "BLE",
                "Proximity matched! ${triggerTarget.name} RSSI=${triggerTarget.rssi} dBm (smoothed=${triggerTarget.smoothedRssi} >= $threshold, nameDropPeer=${triggerTarget.isNameDropPeer})"
            )
            _events.tryEmit(
                BleEvent.ProximityMatched(
                    deviceAddress = triggerTarget.address,
                    deviceName = triggerTarget.name,
                    rssi = triggerTarget.smoothedRssi,
                    isNameDropPeer = triggerTarget.isNameDropPeer
                )
            )
            if (triggerTarget.isNameDropPeer || triggerTarget.isBonded) {
                connectAndReadContact(
                    device = triggerTarget.device,
                    deviceName = triggerTarget.name,
                    rssi = triggerTarget.smoothedRssi
                )
            }
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
            ?: "Nearby Phone"
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
            DebugLogger.log("BLE", "Advertising started (TX HIGH)")
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
                _state.value = _state.value.copy(isAdvertising = false)
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
        if (!decoded.verifiedSha256) {
            DebugLogger.log("GATT", "Photo SHA-256 verification mismatch!", "WARN")
        }
        when (decoded.type) {
            BleChunkProtocol.TYPE_CONTACT_CARD -> {
                decoded.contact?.let { card ->
                    _events.tryEmit(
                        BleEvent.ContactReceived(
                            deviceAddress = address,
                            deviceName = deviceName,
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
                            deviceName = deviceName,
                            contact = card,
                            photoBytes = decoded.photoBytes,
                            backgroundPhotoBytes = decoded.backgroundPhotoBytes,
                            rssi = rssi
                        )
                    )
                }
            }

            BleChunkProtocol.TYPE_PHOTO_SHARE,
            BleChunkProtocol.TYPE_GROUP_PHOTO_SHARE,
            BleChunkProtocol.TYPE_VIDEO_SHARE,
            BleChunkProtocol.TYPE_GROUP_VIDEO_SHARE -> {
                val mediaBytes = decoded.photoBytes ?: return
                val isGroup = decoded.type == BleChunkProtocol.TYPE_GROUP_PHOTO_SHARE ||
                    decoded.type == BleChunkProtocol.TYPE_GROUP_VIDEO_SHARE
                _events.tryEmit(
                    BleEvent.PhotoOfferReceived(
                        senderName = decoded.senderName ?: deviceName,
                        fileName = decoded.fileName ?: if (decoded.isVideo) "NameDrop_Video.mp4" else "NameDrop_Photo.jpg",
                        mimeType = decoded.mimeType,
                        isVideo = decoded.isVideo,
                        photoBytes = mediaBytes,
                        isGroupShare = isGroup
                    )
                )
            }
        }
    }

    private fun pickBestTargetDevice(): BluetoothDevice? {
        return candidateSignals.values
            .filter { it.isNameDropPeer || it.isBonded }
            .maxWithOrNull(compareBy<CandidateSignal> { it.priorityTier }.thenBy { it.smoothedRssi })
            ?.device
            ?: candidateSignals.values.maxByOrNull { it.smoothedRssi }?.device
            ?: discoveredDevices.values.firstOrNull()
    }

    fun sendContactToNearestPeer(card: ContactCard, asMutualHandshake: Boolean = false): Boolean {
        val target = pickBestTargetDevice()
        if (target == null) {
            DebugLogger.log("GATT", "No discovered BLE peer available for contact push", "WARN")
            return false
        }
        val isKnownNameDropPeer = nameDropPeerAddresses.contains(target.address)
        val peerName = candidateSignals[target.address]?.name ?: "Nearby Phone"
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
            writePayloadToDevice(target, payload, peerName)
        }
        return isKnownNameDropPeer
    }

    fun sendMediaToNearbyPeers(
        senderName: String,
        fileName: String,
        mediaBytes: ByteArray,
        mimeType: String,
        isVideo: Boolean,
        isGroupShare: Boolean
    ): Pair<Int, String?> {
        val ranked = candidateSignals.values
            .sortedWith(compareByDescending<CandidateSignal> { it.priorityTier }.thenByDescending { it.smoothedRssi })
            .map { it.device to it.name }
            .ifEmpty { discoveredDevices.values.map { it to "Nearby Device" } }

        val targets = if (isGroupShare) ranked else ranked.take(1)
        if (targets.isEmpty()) return 0 to null
        scope.launch {
            val payload = BleChunkProtocol.encodeMediaPayload(
                senderName = senderName,
                fileName = fileName,
                mediaBytes = mediaBytes,
                mimeType = mimeType,
                isVideo = isVideo,
                isGroupShare = isGroupShare
            )
            targets.forEach { (dev, label) ->
                writePayloadToDevice(dev, payload, label)
            }
        }
        return targets.size to targets.firstOrNull()?.second
    }

    @SuppressLint("MissingPermission")
    private fun writePayloadToDevice(device: BluetoothDevice, payload: ByteArray, peerLabel: String) {
        if (!BlePermissions.hasConnectPermission(context)) {
            _events.tryEmit(BleEvent.TransferCompleted(false, peerLabel))
            return
        }
        cancelClassicDiscoverySafe()
        val chunks = BleChunkProtocol.chunkBytes(payload)
        try {
            device.connectGatt(
                context,
                false,
                object : BluetoothGattCallback() {
                    private var chunkIndex = 0
                    private var completedEmitted = false

                    override fun onConnectionStateChange(
                        gatt: BluetoothGatt,
                        status: Int,
                        newState: Int
                    ) {
                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            runCatching { gatt.requestMtu(512) }
                            runCatching { gatt.discoverServices() }
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            if (!completedEmitted) {
                                completedEmitted = true
                                _events.tryEmit(
                                    BleEvent.TransferCompleted(chunkIndex >= chunks.size, peerLabel)
                                )
                            }
                            runCatching { gatt.close() }
                        }
                    }

                    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                        val ch = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHAR_DATA_UUID)
                        if (status == BluetoothGatt.GATT_SUCCESS && ch != null && chunks.isNotEmpty()) {
                            writeNextChunk(gatt, ch)
                        } else {
                            if (!completedEmitted) {
                                completedEmitted = true
                                _events.tryEmit(BleEvent.TransferCompleted(false, peerLabel))
                            }
                            runCatching { gatt.disconnect() }
                        }
                    }

                    override fun onCharacteristicWrite(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        status: Int
                    ) {
                        if (status == BluetoothGatt.GATT_SUCCESS && chunkIndex < chunks.size) {
                            _events.tryEmit(
                                BleEvent.TransferProgress(chunkIndex.toFloat() / chunks.size.toFloat())
                            )
                            writeNextChunk(gatt, characteristic)
                        } else {
                            _events.tryEmit(BleEvent.TransferProgress(1f))
                            if (!completedEmitted) {
                                completedEmitted = true
                                _events.tryEmit(
                                    BleEvent.TransferCompleted(status == BluetoothGatt.GATT_SUCCESS, peerLabel)
                                )
                            }
                            runCatching { gatt.disconnect() }
                        }
                    }

                    private fun writeNextChunk(
                        gatt: BluetoothGatt,
                        ch: BluetoothGattCharacteristic
                    ) {
                        if (chunkIndex >= chunks.size) {
                            _events.tryEmit(BleEvent.TransferProgress(1f))
                            if (!completedEmitted) {
                                completedEmitted = true
                                _events.tryEmit(BleEvent.TransferCompleted(true, peerLabel))
                            }
                            runCatching { gatt.disconnect() }
                            return
                        }
                        val chunk = chunks[chunkIndex++]
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeCharacteristic(
                                ch,
                                chunk,
                                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            ch.value = chunk
                            @Suppress("DEPRECATION")
                            gatt.writeCharacteristic(ch)
                        }
                    }
                },
                BluetoothDevice.TRANSPORT_LE
            )
        } catch (e: Exception) {
            DebugLogger.log("GATT", "Error writing payload: ${e.message}", "ERROR")
            _events.tryEmit(BleEvent.TransferCompleted(false, peerLabel))
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
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
            if (value != null) {
                val stream = incomingChunkBuffers.getOrPut(device.address) { ByteArrayOutputStream() }
                stream.write(value)
                val accumulated = stream.toByteArray()
                val decoded = BleChunkProtocol.decodePayload(accumulated)
                if (decoded != null) {
                    incomingChunkBuffers.remove(device.address)
                    handleDecodedGattBytes(
                        address = device.address,
                        deviceName = runCatching { device.name }.getOrNull() ?: "Nearby Phone",
                        bytes = accumulated,
                        rssi = _state.value.smoothedRssi ?: _state.value.rssiThreshold
                    )
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
