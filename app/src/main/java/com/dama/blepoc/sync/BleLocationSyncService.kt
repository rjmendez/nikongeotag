package com.dama.blepoc.sync

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dama.blepoc.R
import com.dama.blepoc.ble.NikonBleClient
import com.dama.blepoc.location.LocationPayloadEncoder
import com.dama.blepoc.location.TimePayloadEncoder
import com.dama.blepoc.location.LocationProvider
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class BleLocationSyncService : Service() {
    companion object {
        const val ACTION_START = "com.dama.blepoc.action.START"
        const val ACTION_STOP = "com.dama.blepoc.action.STOP"
        const val ACTION_STATUS = "com.dama.blepoc.action.STATUS"
        const val EXTRA_CAMERA_MAC = "camera_mac"
        const val EXTRA_INTERVAL_SECONDS = "interval_seconds"
        const val EXTRA_STATUS_MESSAGE = "status_message"

        private const val CHANNEL_ID = "blepoc_sync"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS_NAME = "blepoc_prefs"
        private const val PREF_CONTROLLER_NAME = "controller_name"
        private const val PREF_PAIRED_DEVICE = "paired_device"
        private const val PREF_PAIRED_NONCE = "paired_nonce"
        private const val PREF_SYNC_ENABLED = "sync_enabled"
        private const val PREF_LAST_MAC = "last_sync_mac"
        private const val PREF_LAST_INTERVAL = "last_sync_interval"
        private const val PREFERRED_CAMERA_NAME = "Z_7_2_3033447"
        private const val NIKON_COMPANY_ID = 0x0399
        private val LSS_UUID: UUID = UUID.fromString("0000DE00-3DD4-4255-8D62-6DC7B9BD5561")

        // Reconnect retries per cycle, to beat Nikon's first-connection drop.
        private const val RECONNECT_MAX_ATTEMPTS = 4
        private const val RECONNECT_RETRY_DELAY_MS = 1200L
        private const val SCAN_TIMEOUT_MS = 12_000L
        // Adaptive back-off bounds (seconds) used while the camera is absent.
        private const val BACKOFF_START_SECONDS = 5L
        private const val BACKOFF_MAX_SECONDS = 60L
        // GPS fixes worse than this (metres) are skipped, but not treated as a drop.
        private const val GPS_MAX_ACCURACY_M = 50f
    }

    private enum class ConnState { STARTING, SCANNING, CONNECTED, WAITING, RECONNECTING, STOPPED }

    private val stateLock = Any()
    private var scheduler: ScheduledExecutorService? = null
    private var scheduledTask: ScheduledFuture<*>? = null
    private var bleClient: NikonBleClient? = null
    private var locationProvider: LocationProvider? = null
    private var syncRunning = false
    private var currentCameraMac: String? = null
    private var intervalSeconds: Long = 10
    private var backoffSeconds: Long = BACKOFF_START_SECONDS
    private var connState: ConnState = ConnState.STARTING
    private var lastFixAtMillis: Long = 0L
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private val timeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // System restart (START_STICKY) delivers a null intent — resume sync from persisted state.
        if (intent == null || intent.action == null) {
            return resumeIfEnabled()
        }
        when (intent.action) {
            ACTION_START -> {
                val mac = intent.getStringExtra(EXTRA_CAMERA_MAC)
                    ?: throw IllegalStateException("Missing camera MAC")
                val intervalSeconds = intent.getIntExtra(EXTRA_INTERVAL_SECONDS, -1)
                if (intervalSeconds !in 5..3600) {
                    throw IllegalStateException("Invalid sync interval: $intervalSeconds")
                }
                ensurePermissions()
                persistSyncParams(mac, intervalSeconds, enabled = true)
                synchronized(stateLock) { connState = ConnState.STARTING }
                startForeground(NOTIFICATION_ID, buildNotification())
                startSync(mac, intervalSeconds)
            }

            ACTION_STOP -> {
                persistSyncEnabled(false)
                stopSync("stopped by user")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                postStatus("unsupported action: ${intent.action}")
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    /** Re-arm sync after a process/system restart, using persisted params. */
    private fun resumeIfEnabled(): Int {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_SYNC_ENABLED, false)) return START_NOT_STICKY
        val mac = prefs.getString(PREF_LAST_MAC, null) ?: return START_NOT_STICKY
        val interval = prefs.getInt(PREF_LAST_INTERVAL, 30).coerceIn(5, 3600)
        return try {
            ensurePermissions()
            synchronized(stateLock) { connState = ConnState.STARTING }
            startForeground(NOTIFICATION_ID, buildNotification())
            startSync(mac, interval)
            START_STICKY
        } catch (_: Exception) {
            START_STICKY
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away must not stop background sync: restart the service if enabled.
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(PREF_SYNC_ENABLED, false)) {
            val restart = Intent(applicationContext, BleLocationSyncService::class.java)
            ContextCompat.startForegroundService(applicationContext, restart)
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun persistSyncParams(mac: String, interval: Int, enabled: Boolean) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(PREF_LAST_MAC, mac)
            .putInt(PREF_LAST_INTERVAL, interval)
            .putBoolean(PREF_SYNC_ENABLED, enabled)
            .apply()
    }

    private fun persistSyncEnabled(enabled: Boolean) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(PREF_SYNC_ENABLED, enabled).apply()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "blepoc:geo_sync").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    override fun onDestroy() {
        stopSync("service destroyed")
        super.onDestroy()
    }

    private fun startSync(macAddress: String, intervalSecondsArg: Int) {
        synchronized(stateLock) {
            if (syncRunning) {
                stopSync("restarting sync")
            }
            if (scheduler == null) {
                scheduler = Executors.newSingleThreadScheduledExecutor()
            }
            scheduledTask?.cancel(true)

            currentCameraMac = macAddress
            intervalSeconds = intervalSecondsArg.toLong()
            backoffSeconds = BACKOFF_START_SECONDS
            connState = ConnState.STARTING
            lastFixAtMillis = 0L
            bleClient = null
            locationProvider = LocationProvider(applicationContext)
            syncRunning = true
        }
        acquireWakeLock()
        postStatus("started")
        scheduleNext(0)
    }

    /**
     * Schedules the next adaptive cycle after [delaySeconds]. A single-thread scheduler is
     * reused; each cycle reschedules itself, so there is no fixed-rate spinning. No-ops once
     * the sync has been stopped so no stray task survives shutdown.
     */
    private fun scheduleNext(delaySeconds: Long) {
        synchronized(stateLock) {
            if (!syncRunning) return
            val exec = scheduler ?: return
            scheduledTask = exec.schedule(
                { runCycle() },
                delaySeconds.coerceAtLeast(0),
                TimeUnit.SECONDS
            )
        }
    }

    /**
     * One adaptive cycle. When connected with a good fix it writes GEO and reschedules at the
     * user's interval, reusing the live GATT across cycles. When the camera is absent it backs
     * off quietly; a dropped write or real error reconnects on a growing back-off.
     */
    private fun runCycle() {
        if (!isRunning()) return
        var nextDelay = intervalSeconds
        try {
            var client = synchronized(stateLock) { bleClient }
            if (client == null || !client.isConnected()) {
                when (val outcome = attemptReconnect()) {
                    is ConnectOutcome.Connected -> {
                        adoptClient(outcome.client)
                        client = outcome.client
                        resetBackoff()
                        setState(ConnState.CONNECTED)
                        // Sync the camera clock once per connection (best-effort).
                        try {
                            client.writeTimePayload(TimePayloadEncoder.encode())
                            postStatus("time synced")
                        } catch (e: Exception) {
                            // Time is non-critical; continue to GPS even if it fails.
                        }
                        postStatus("connected")
                    }
                    ConnectOutcome.CameraNotFound -> {
                        discardClient()
                        setState(ConnState.WAITING)
                        postStatus("camera idle; waiting")
                        nextDelay = nextBackoff()
                        return
                    }
                    is ConnectOutcome.Failed -> {
                        discardClient()
                        setState(ConnState.RECONNECTING)
                        postStatus("sync error: ${outcome.reason}")
                        nextDelay = nextBackoff()
                        return
                    }
                }
            }

            val provider = synchronized(stateLock) { locationProvider }
                ?: throw IllegalStateException("Location provider missing")
            val location = provider.getCurrentLocation()

            if (location.accuracy > GPS_MAX_ACCURACY_M) {
                // Poor fix: stay connected and keep normal cadence, do not treat as a drop.
                setState(ConnState.CONNECTED)
                postStatus("gps poor accuracy: ${location.accuracy}m")
                nextDelay = intervalSeconds
                return
            }

            val payload = LocationPayloadEncoder.encode(location)
            require(payload.size == 41) { "payload size invalid: ${payload.size}" }
            client!!.writeLocationPayload(payload)
            markFix()
            resetBackoff()
            setState(ConnState.CONNECTED)
            postStatus("sent lat=${location.latitude}, lon=${location.longitude}")
            nextDelay = intervalSeconds
        } catch (se: SecurityException) {
            discardClient()
            setState(ConnState.RECONNECTING)
            postStatus("permission error: ${se.message}")
            nextDelay = nextBackoff()
        } catch (e: Exception) {
            // Write/connection dropped or handshake blew up: reconnect on a back-off.
            discardClient()
            setState(ConnState.RECONNECTING)
            postStatus("sync error: ${e.message}")
            nextDelay = nextBackoff()
        } finally {
            scheduleNext(nextDelay)
        }
    }

    private sealed interface ConnectOutcome {
        data class Connected(val client: NikonBleClient) : ConnectOutcome
        object CameraNotFound : ConnectOutcome
        data class Failed(val reason: String) : ConnectOutcome
    }

    /**
     * Scans for the live camera advert and runs connect + handshake, retrying up to
     * [RECONNECT_MAX_ATTEMPTS] times to beat Nikon's first-connection drop. Reuses the
     * persisted paired device/nonce identity. If the very first scan finds nothing the camera
     * is treated as off/asleep (quiet back-off) rather than an error.
     */
    private fun attemptReconnect(): ConnectOutcome {
        setState(ConnState.SCANNING)
        var lastError: String? = null
        for (attempt in 1..RECONNECT_MAX_ATTEMPTS) {
            if (!isRunning()) return ConnectOutcome.Failed("stopped")
            postStatus("scanning for camera ($attempt/$RECONNECT_MAX_ATTEMPTS)")
            val device = scanForCamera(SCAN_TIMEOUT_MS)
            if (device == null) {
                if (attempt == 1) return ConnectOutcome.CameraNotFound
                lastError = "camera not found"
                continue
            }
            val candidate = NikonBleClient(applicationContext, device)
            try {
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                val savedDevice = if (prefs.contains(PREF_PAIRED_DEVICE)) prefs.getInt(PREF_PAIRED_DEVICE, 0) else null
                val savedNonce = if (prefs.contains(PREF_PAIRED_NONCE)) prefs.getInt(PREF_PAIRED_NONCE, 0) else null
                postStatus("handshaking")
                if (candidate.connect() &&
                    candidate.performHandshake(resolveControllerName(), savedDevice, savedNonce) != null
                ) {
                    return ConnectOutcome.Connected(candidate)
                }
                lastError = "handshake failed"
            } catch (e: Exception) {
                lastError = e.message
            }
            candidate.close()
            Thread.sleep(RECONNECT_RETRY_DELAY_MS)
        }
        return ConnectOutcome.Failed(lastError ?: "camera connect failed")
    }

    private fun isRunning(): Boolean = synchronized(stateLock) { syncRunning }

    private fun setState(state: ConnState) {
        synchronized(stateLock) { connState = state }
    }

    private fun markFix() {
        synchronized(stateLock) { lastFixAtMillis = System.currentTimeMillis() }
    }

    private fun resetBackoff() {
        synchronized(stateLock) { backoffSeconds = BACKOFF_START_SECONDS }
    }

    /** Returns the current back-off delay and advances it exponentially toward the cap. */
    private fun nextBackoff(): Long {
        synchronized(stateLock) {
            val current = backoffSeconds
            backoffSeconds = (current * 2).coerceAtMost(BACKOFF_MAX_SECONDS)
            return current
        }
    }

    private fun adoptClient(client: NikonBleClient) {
        synchronized(stateLock) {
            if (bleClient !== client) {
                bleClient?.close()
                bleClient = client
            }
        }
    }

    private fun discardClient() {
        synchronized(stateLock) {
            bleClient?.close()
            bleClient = null
        }
    }

    /**
     * Scans for the paired Nikon camera's live advertisement. Matches the LSS service UUID
     * (pairing advert), the device name, or Nikon manufacturer data (company id 0x0399,
     * saved-device reconnect advert). Returns the live BluetoothDevice or null on timeout.
     */
    private fun scanForCamera(timeoutMs: Long): BluetoothDevice? {
        val adapter: BluetoothAdapter =
            (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return null
        val scanner = adapter.bluetoothLeScanner ?: return null
        val found = AtomicReference<BluetoothDevice?>(null)
        val latch = CountDownLatch(1)

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val record = result.scanRecord
                val nameMatches =
                    device.name == PREFERRED_CAMERA_NAME || record?.deviceName == PREFERRED_CAMERA_NAME
                val lssMatches = record?.serviceUuids?.any { it.uuid == LSS_UUID } == true
                val nikonMfr = record?.getManufacturerSpecificData(NIKON_COMPANY_ID) != null
                if (nameMatches || lssMatches || nikonMfr) {
                    found.set(device)
                    latch.countDown()
                }
            }

            override fun onScanFailed(errorCode: Int) {
                latch.countDown()
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val filters = listOf(
            ScanFilter.Builder().setDeviceName(PREFERRED_CAMERA_NAME).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(LSS_UUID)).build(),
            ScanFilter.Builder()
                .setManufacturerData(NIKON_COMPANY_ID, byteArrayOf())
                .build()
        )
        return try {
            scanner.startScan(filters, settings, callback)
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            found.get()
        } finally {
            try {
                scanner.stopScan(callback)
            } catch (_: Exception) {
            }
        }
    }

    private fun resolveControllerName(): String {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val stored = prefs.getString(PREF_CONTROLLER_NAME, null)
        if (!stored.isNullOrEmpty()) {
            return stored
        }
        val suffix = "%04x".format(SecureRandom().nextInt(0x10000))
        val generated = "blepoc-$suffix"
        prefs.edit().putString(PREF_CONTROLLER_NAME, generated).apply()
        return generated
    }

    private fun stopSync(reason: String) {
        synchronized(stateLock) {
            scheduledTask?.cancel(true)
            scheduledTask = null
            bleClient?.close()
            bleClient = null
            locationProvider?.close()
            locationProvider = null
            scheduler?.shutdownNow()
            scheduler = null
            syncRunning = false
            currentCameraMac = null
            connState = ConnState.STOPPED
        }
        releaseWakeLock()
        postStatus(reason)
    }

    private fun ensurePermissions() {
        val missing = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            missing += Manifest.permission.BLUETOOTH_SCAN
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            missing += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }
        if (missing.isNotEmpty()) throw SecurityException("Missing permissions: ${missing.joinToString(", ")}")
    }

    private fun postStatus(message: String) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS_MESSAGE, message))
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        ensureNotificationChannel()
        val (state, fixAt) = synchronized(stateLock) { connState to lastFixAtMillis }
        val text = when (state) {
            ConnState.STARTING -> "Starting…"
            ConnState.SCANNING -> "Scanning for camera"
            ConnState.CONNECTED -> "Connected — sending"
            ConnState.WAITING -> "Waiting for camera"
            ConnState.RECONNECTING -> "Reconnecting…"
            ConnState.STOPPED -> "Paused"
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        if (fixAt > 0L) {
            builder.setSubText("Last fix ${timeFormatter.format(Instant.ofEpochMilli(fixAt))}")
        }
        return builder.build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "BLE metadata sync",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }
}
