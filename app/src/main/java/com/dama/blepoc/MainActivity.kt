package com.dama.blepoc

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.dama.blepoc.ble.NikonBleClient
import com.dama.blepoc.sync.BleLocationSyncService
import com.google.android.material.chip.Chip
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MainActivity : ComponentActivity() {
    companion object {
        private const val PREFERRED_CAMERA_NAME = "Z_7_2_3033447"
        private const val PREFS_NAME = "blepoc_prefs"
        private const val PREF_CAMERA_MAC = "camera_mac"
        private const val PREF_CONTROLLER_NAME = "controller_name"
        private const val PREF_PAIRED_DEVICE = "paired_device"
        private const val PREF_PAIRED_NONCE = "paired_nonce"
        private const val BOND_TIMEOUT_SECONDS = 40L
        private const val BOND_MAX_ATTEMPTS = 3
        private val LSS_UUID: UUID = UUID.fromString("0000DE00-3DD4-4255-8D62-6DC7B9BD5561")
    }

    private lateinit var intervalInput: EditText
    private lateinit var statusText: TextView
    private lateinit var connectionStateChip: Chip
    private lateinit var lastFixText: TextView
    private val lastFixRegex = Regex("lat=(-?\\d+\\.?\\d*),\\s*lon=(-?\\d+\\.?\\d*)")
    private var activeScanCallback: ScanCallback? = null
    private var activeScanner: BluetoothLeScanner? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var scanTimeoutRunnable: Runnable? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val denied = grants.entries.filter { !it.value }.map { it.key }
            if (denied.isNotEmpty()) {
                statusText.text = "Status: permissions denied: ${denied.joinToString(", ")}"
                updateConnectionState("permissions denied")
                Toast.makeText(this, R.string.required_permissions_missing, Toast.LENGTH_LONG).show()
            } else {
                // Permissions just granted: auto-start sync if a camera is already paired.
                startSyncService(manualStart = false)
            }
        }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BleLocationSyncService.ACTION_STATUS) return
            val message = intent.getStringExtra(BleLocationSyncService.EXTRA_STATUS_MESSAGE)
                ?: throw IllegalStateException("status message missing")
            showStatus(message)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        intervalInput = findViewById(R.id.intervalInput)
        statusText = findViewById(R.id.statusText)
        connectionStateChip = findViewById(R.id.connectionStateChip)
        lastFixText = findViewById(R.id.lastFixText)
        val startButton: Button = findViewById(R.id.startButton)
        val stopButton: Button = findViewById(R.id.stopButton)

        enforceCapabilities()
        requestMissingPermissions()
        requestBatteryOptimizationExemption()

        val resolvedMac = resolveCameraMac()
        if (resolvedMac != null) {
            showStatus("paired Nikon camera detected: $PREFERRED_CAMERA_NAME ($resolvedMac)")
        } else {
            showStatus("no paired Nikon camera found; tap Pair camera")
        }

        startButton.setOnClickListener { startSyncService(manualStart = true) }

        stopButton.setOnClickListener {
            val intent = Intent(this, BleLocationSyncService::class.java).apply {
                action = BleLocationSyncService.ACTION_STOP
            }
            startService(intent)
        }

        val pairButton: Button = findViewById(R.id.pairButton)
        pairButton.setOnClickListener {
            scanAndPair()
        }

        // Auto-start sync on launch if a camera is already paired and permissions are granted.
        if (hasAllRequiredPermissions() && resolvedMac != null) {
            startSyncService(manualStart = false)
        }
    }

    /**
     * Starts the foreground sync service. The camera is resolved automatically from the
     * bonded-device list; no manual MAC entry. Safe to call on launch and from the button.
     */
    private fun startSyncService(manualStart: Boolean) {
        if (!hasAllRequiredPermissions()) {
            requestMissingPermissions()
            return
        }
        val mac = resolveCameraMac()
        if (mac == null || !isBondedMac(mac)) {
            if (manualStart) showStatus("no paired Nikon camera; tap Pair camera first")
            return
        }
        saveCameraMac(mac)

        val intervalSeconds = intervalInput.text.toString().trim().ifEmpty { "30" }.toIntOrNull()
        if (intervalSeconds == null || intervalSeconds !in 5..3600) {
            showStatus(getString(R.string.interval_invalid))
            return
        }

        val intent = Intent(this, BleLocationSyncService::class.java).apply {
            action = BleLocationSyncService.ACTION_START
            putExtra(BleLocationSyncService.EXTRA_CAMERA_MAC, mac)
            putExtra(BleLocationSyncService.EXTRA_INTERVAL_SECONDS, intervalSeconds)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryOptimizationExemption() {
        try {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:$packageName")
                    )
                )
            }
        } catch (_: Exception) {
            // Non-fatal: the service still runs as a foreground service without the exemption.
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            IntentFilter(BleLocationSyncService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(statusReceiver)
    }

    private fun enforceCapabilities() {
        val pm = packageManager
        require(pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            "Device does not support BLE"
        }
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter = bluetoothManager.adapter
            ?: throw IllegalStateException("Bluetooth adapter unavailable")
        require(adapter.isEnabled) { "Bluetooth must be enabled" }
    }

    private fun requestMissingPermissions() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun hasAllRequiredPermissions(): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun requiredPermissions(): List<String> {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        return permissions
    }

    private fun detectPairedCameraMac(): String? {
        val adapter: BluetoothAdapter? =
            (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        return adapter?.bondedDevices
            ?.firstOrNull { it.name == PREFERRED_CAMERA_NAME }
            ?.address
            ?.uppercase(Locale.US)
    }

    private fun resolveCameraMac(): String? {
        val detected = detectPairedCameraMac()
        val saved = getStoredCameraMac()?.uppercase(Locale.US)

        return when {
            detected != null -> {
                if (saved != detected) saveCameraMac(detected)
                detected
            }
            saved != null && isBondedMac(saved) -> saved
            else -> null
        }
    }

    private fun isBondedMac(mac: String): Boolean {
        val normalized = mac.uppercase(Locale.US)
        val adapter: BluetoothAdapter? =
            (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        return adapter?.bondedDevices?.any {
            it.name == PREFERRED_CAMERA_NAME && it.address.uppercase(Locale.US) == normalized
        } == true
    }

    private fun getStoredCameraMac(): String? {
        val prefs: SharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getString(PREF_CAMERA_MAC, null)
    }

    private fun saveCameraMac(mac: String) {
        val prefs: SharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putString(PREF_CAMERA_MAC, mac.uppercase(Locale.US)).apply()
    }

    private fun scanAndPair() {
        if (!hasAllRequiredPermissions()) {
            requestMissingPermissions()
            showStatus("grant Bluetooth permissions before scanning")
            return
        }
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: run {
                showStatus("Bluetooth adapter unavailable")
                return
            }
        if (!adapter.isEnabled) {
            showStatus("enable Bluetooth before scanning")
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: run {
            showStatus("BLE scanner unavailable")
            return
        }

        stopActiveScan()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        // OR-matched filters: by advertised local name, and by the Nikon LSS service UUID.
        val filters = listOf(
            ScanFilter.Builder().setDeviceName(PREFERRED_CAMERA_NAME).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(LSS_UUID)).build()
        )

        activeScanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val advertisesLss =
                    result.scanRecord?.serviceUuids?.any { it.uuid == LSS_UUID } == true
                val nameMatches = device.name == PREFERRED_CAMERA_NAME
                if (!nameMatches && !advertisesLss) return
                cancelScanTimeout()
                stopActiveScan()
                val label = device.name ?: PREFERRED_CAMERA_NAME
                showStatus("camera found: $label (${device.address}, rssi ${result.rssi}); pairing...")
                bondAndFinish(adapter, device)
            }

            override fun onScanFailed(errorCode: Int) {
                cancelScanTimeout()
                stopActiveScan()
                showStatus("camera scan failed (code $errorCode)")
            }
        }

        activeScanner = scanner
        showStatus("scanning for Nikon camera $PREFERRED_CAMERA_NAME...")
        scanner.startScan(filters, settings, activeScanCallback)

        scanTimeoutRunnable = Runnable {
            stopActiveScan()
            showStatus("camera not found; power it on and set 'Connect to smart device' mode, then retry")
        }.also { mainHandler.postDelayed(it, 15_000) }
    }

    private fun bondAndFinish(adapter: BluetoothAdapter, device: BluetoothDevice) {
        val address = device.address.uppercase(Locale.US)
        showStatus("camera found ($address); running pairing handshake...")
        runFullPairFlow(adapter, device)
    }

    /**
     * Corrected Nikon Z smart-mode pairing flow:
     *  1. BLE GATT connect + 4-stage handshake + controller-name (ID) write via NikonBleClient.
     *  2. Close the GATT.
     *  3. Bluetooth Classic (BR/EDR) bonding: discover the camera by name and createBond().
     *  4. Persist the resolved classic MAC; GEO writes are only accepted once this bond exists.
     */
    private fun runFullPairFlow(adapter: BluetoothAdapter, scannedDevice: BluetoothDevice) {
        val controllerName = resolveControllerName()
        Thread {
            // Nikon cameras routinely drop the first BLE connection after entering pairing mode,
            // so connect + handshake is retried a few times on the live advertising device.
            var handshake: NikonBleClient.HandshakeResult? = null
            val maxAttempts = 4
            for (attempt in 1..maxAttempts) {
                val client = NikonBleClient(applicationContext, scannedDevice)
                try {
                    runOnUiThread {
                        showStatus("handshake attempt $attempt/$maxAttempts as $controllerName...")
                    }
                    if (client.connect()) {
                        handshake = client.performHandshake(controllerName)
                    }
                } catch (e: Exception) {
                    showStatus("attempt $attempt error: ${e.message}")
                } finally {
                    client.close()
                }
                if (handshake != null) break
                Thread.sleep(1500)
            }

            if (handshake == null) {
                showStatus("handshake failed after $maxAttempts attempts; keep camera in pairing mode and retry")
                return@Thread
            }

            // Persist the controller identity for reconnect re-handshake.
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putInt(PREF_PAIRED_DEVICE, handshake.device)
                .putInt(PREF_PAIRED_NONCE, handshake.nonce)
                .apply()

            showStatus("handshake OK (serial ${handshake.serial}); pairing over Bluetooth Classic...")

            val bondedMac = try {
                performClassicBond(adapter)
            } catch (e: Exception) {
                showStatus("classic bonding error: ${e.message}")
                return@Thread
            }

            if (bondedMac != null) {
                saveCameraMac(bondedMac)
                runOnUiThread {
                    showStatus("registered + bonded ($bondedMac); starting sync")
                    startSyncService(manualStart = false)
                }
            } else {
                showStatus("Bluetooth Classic bonding timed out; confirm the passkey shown on the camera and retry")
            }
        }.start()
    }

    /**
     * Discover the camera over Bluetooth Classic and bond with it. Returns the bonded classic
     * MAC on success (uppercase), or null after all attempts fail. Blocks the calling
     * (background) thread. Retries the discover+createBond cycle a few times because the first
     * Nikon passkey exchange frequently ends in BOND_NONE before succeeding.
     */
    private fun performClassicBond(adapter: BluetoothAdapter): String? {
        // Remove any stale (e.g. real SnapBridge) bond for the camera first. An existing
        // BR/EDR link key that the camera no longer honours makes createBond fail
        // (BOND_BONDED -> BOND_NONE) instead of starting a fresh passkey pairing.
        adapter.bondedDevices
            ?.filter { isTargetCamera(it.name) }
            ?.forEach { removeBondReflect(it) }

        for (attempt in 1..BOND_MAX_ATTEMPTS) {
            // An already-BONDED target (e.g. stale-bond removal declined) is usable as-is.
            adapter.bondedDevices?.firstOrNull {
                isTargetCamera(it.name) && it.bondState == BluetoothDevice.BOND_BONDED
            }?.let { return it.address.uppercase(Locale.US) }

            showStatus("Bluetooth Classic bonding attempt $attempt/$BOND_MAX_ATTEMPTS; confirm the passkey shown on the camera")
            val mac = attemptClassicBond(adapter)
            if (mac != null) return mac
            if (attempt < BOND_MAX_ATTEMPTS) {
                showStatus("bond attempt $attempt failed; retrying, keep the camera in pairing mode")
                Thread.sleep(2000)
            }
        }
        return null
    }

    /** Single discover+createBond cycle. Returns the bonded MAC, or null on failure/timeout. */
    private fun attemptClassicBond(adapter: BluetoothAdapter): String? {
        val latch = CountDownLatch(1)
        val bondedMac = AtomicReference<String?>(null)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = extractBondDevice(intent) ?: return
                        if (!isTargetCamera(device.name)) return
                        try {
                            adapter.cancelDiscovery()
                        } catch (_: SecurityException) {
                        }
                        if (device.bondState == BluetoothDevice.BOND_BONDED) {
                            bondedMac.set(device.address.uppercase(Locale.US))
                            latch.countDown()
                            return
                        }
                        showStatus("bonding over Bluetooth Classic with ${device.name} (${device.address}); confirm the passkey shown on the camera")
                        device.createBond()
                    }

                    BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                        val device = extractBondDevice(intent) ?: return
                        if (!isTargetCamera(device.name)) return
                        // Best-effort: the user confirms the matching passkey on the camera.
                        try {
                            device.setPairingConfirmation(true)
                        } catch (_: SecurityException) {
                        } catch (_: Exception) {
                        }
                    }

                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val device = extractBondDevice(intent) ?: return
                        if (!isTargetCamera(device.name)) return
                        val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                        when (state) {
                            BluetoothDevice.BOND_BONDED -> {
                                bondedMac.set(device.address.uppercase(Locale.US))
                                latch.countDown()
                            }
                            BluetoothDevice.BOND_NONE -> {
                                showStatus("classic bonding failed/rejected; confirm the passkey shown on the camera")
                                // End this attempt early so the retry loop can start a fresh cycle.
                                latch.countDown()
                            }
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)

        return try {
            try {
                adapter.cancelDiscovery()
            } catch (_: SecurityException) {
            }
            adapter.startDiscovery()
            latch.await(BOND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            bondedMac.get()
        } finally {
            try {
                adapter.cancelDiscovery()
            } catch (_: Exception) {
            }
            try {
                unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    private fun removeBondReflect(device: BluetoothDevice) {
        try {
            if (device.bondState != BluetoothDevice.BOND_NONE) {
                device.javaClass.getMethod("removeBond").invoke(device)
                showStatus("removed stale bond for ${device.name} (${device.address}); re-pairing...")
                Thread.sleep(1200)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Single entry point for UI status updates: sets the detail line, derives a coarse
     * connection-state chip + color, and refreshes the last-fix line from "sent lat=..." messages.
     * Safe to call from any thread.
     */
    private fun showStatus(detail: String) {
        runOnUiThread {
            statusText.text = "Status: $detail"
            updateConnectionState(detail)
            maybeUpdateLastFix(detail)
        }
    }

    private fun updateConnectionState(detail: String) {
        val lower = detail.lowercase(Locale.US)
        val (labelRes, colorRes) = when {
            lower.contains("error") || lower.contains("fail") || lower.contains("denied") ||
                lower.contains("reject") || lower.contains("timed out") || lower.contains("timeout") ||
                lower.contains("poor accuracy") || lower.contains("invalid") ||
                lower.contains("not found") || lower.contains("unavailable") ||
                lower.contains("missing") ->
                R.string.state_error to R.color.state_error
            lower.contains("sent lat") ->
                R.string.state_sent to R.color.state_sent
            lower.contains("registered") ->
                R.string.state_connected to R.color.state_connected
            lower.contains("sending") || lower.contains("writing") ->
                R.string.state_sending to R.color.state_sending
            lower.contains("connected") ->
                R.string.state_connected to R.color.state_connected
            lower.contains("bond") ->
                R.string.state_bonding to R.color.state_bonding
            lower.contains("pair") || lower.contains("handshak") || lower.contains("passkey") ->
                R.string.state_pairing to R.color.state_pairing
            lower.contains("scan") ->
                R.string.state_scanning to R.color.state_pairing
            else ->
                R.string.state_idle to R.color.state_idle
        }
        val color = ContextCompat.getColor(this, colorRes)
        connectionStateChip.text = getString(labelRes)
        connectionStateChip.chipBackgroundColor = ColorStateList.valueOf(color)
        connectionStateChip.setTextColor(ContextCompat.getColor(this, R.color.state_on_color))
    }

    private fun maybeUpdateLastFix(detail: String) {
        val match = lastFixRegex.find(detail) ?: return
        val lat = match.groupValues[1]
        val lon = match.groupValues[2]
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        lastFixText.text = "Last fix: $lat, $lon @ $time"
    }

    private fun isTargetCamera(name: String?): Boolean {
        if (name == null) return false
        return name == PREFERRED_CAMERA_NAME || name.startsWith("Z_")
    }

    private fun extractBondDevice(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private fun resolveControllerName(): String {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val stored = prefs.getString(PREF_CONTROLLER_NAME, null)
        if (!stored.isNullOrEmpty()) {
            return stored
        }
        val suffix = "%04x".format(java.security.SecureRandom().nextInt(0x10000))
        val generated = "blepoc-$suffix"
        prefs.edit().putString(PREF_CONTROLLER_NAME, generated).apply()
        return generated
    }

    private fun cancelScanTimeout() {
        scanTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        scanTimeoutRunnable = null
    }

    private fun stopActiveScan() {
        val scanner = activeScanner
        val callback = activeScanCallback
        if (scanner != null && callback != null) {
            try {
                scanner.stopScan(callback)
            } catch (_: IllegalStateException) {
                // scanner already stopped; ignore
            }
        }
        activeScanner = null
        activeScanCallback = null
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelScanTimeout()
        stopActiveScan()
    }
}
