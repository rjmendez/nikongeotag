package com.dama.blepoc.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.util.Log
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * GATT-level client for Nikon Z "smart mode" BLE.
 *
 * Implements the pairing handshake and GPS metadata write verified against
 * github.com/hurui200320/nsg and github.com/gkoh/furble:
 *  - Primary service de00.
 *  - PAIR char 0x2000: WRITE pairing messages, INDICATE stages 2 & 4.
 *  - NOT1 char 0x2008: NOTIFY only; camera sends `01 00` on success.
 *  - ID   char 0x2002: WRITE controller friendly name after stage 4.
 *  - GEO  char 0x2007: WRITE 41-byte GPS payload (write-with-response).
 *
 * This class stops at the GATT-level handshake + GEO write. Bluetooth Classic
 * bonding (required before GEO writes are accepted by the camera) is the
 * caller's responsibility (MainActivity).
 */
@Suppress("DEPRECATION")
class NikonBleClient private constructor(
    private val appContext: Context,
    private val macAddress: String?,
    private val presetDevice: BluetoothDevice?
) {
    constructor(appContext: Context, macAddress: String) : this(appContext, macAddress, null)
    constructor(appContext: Context, device: BluetoothDevice) : this(appContext, null, device)

    data class HandshakeResult(val device: Int, val nonce: Int, val serial: String)

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000DE00-3DD4-4255-8D62-6DC7B9BD5561")
        val PAIR_UUID: UUID = UUID.fromString("00002000-3DD4-4255-8D62-6DC7B9BD5561")
        val ID_UUID: UUID = UUID.fromString("00002002-3DD4-4255-8D62-6DC7B9BD5561")
        val GEO_UUID: UUID = UUID.fromString("00002007-3DD4-4255-8D62-6DC7B9BD5561")
        val TIME_UUID: UUID = UUID.fromString("00002006-3DD4-4255-8D62-6DC7B9BD5561")
        val NOT1_UUID: UUID = UUID.fromString("00002008-3DD4-4255-8D62-6DC7B9BD5561")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        private const val TAG = "NikonBleClient"
        private const val INDICATION_TIMEOUT_MS = 10_000L
        private const val WRITE_TIMEOUT_MS = 10_000L
        private const val DESCRIPTOR_TIMEOUT_MS = 10_000L
        private const val GEO_PAYLOAD_SIZE = 41
        private const val TIME_PAYLOAD_SIZE = 10
        private const val ID_FIELD_SIZE = 32
    }

    private val stateLock = Any()
    private var gatt: BluetoothGatt? = null
    private var pairChar: BluetoothGattCharacteristic? = null
    private var idChar: BluetoothGattCharacteristic? = null
    private var geoChar: BluetoothGattCharacteristic? = null
    private var timeChar: BluetoothGattCharacteristic? = null
    private var not1Char: BluetoothGattCharacteristic? = null

    private var connectLatch: CountDownLatch? = null
    private var writeLatch: CountDownLatch? = null
    private var descriptorLatch: CountDownLatch? = null

    private val writeStatus = AtomicInteger(BluetoothGatt.GATT_FAILURE)
    private val descriptorStatus = AtomicInteger(BluetoothGatt.GATT_FAILURE)
    private val servicesResolved = AtomicBoolean(false)

    private val pairIndications = LinkedBlockingQueue<ByteArray>()
    private val not1Success = AtomicBoolean(false)

    @SuppressLint("MissingPermission")
    fun connect(timeoutMs: Long = 20_000): Boolean {
        synchronized(stateLock) {
            close()
            connectLatch = CountDownLatch(1)
            servicesResolved.set(false)
            val bluetoothManager =
                appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter: BluetoothAdapter = bluetoothManager.adapter
                ?: throw IllegalStateException("Bluetooth adapter unavailable")
            val device: BluetoothDevice = presetDevice
                ?: adapter.getRemoteDevice(
                    macAddress ?: throw IllegalStateException("No device or MAC provided")
                )
            // Scanned device (pairing): direct connect. Reconnect by stored identity MAC:
            // use autoConnect=true so Android resolves the camera's rotating RPA via the
            // bond IRK and connects when it next advertises.
            val autoConnect = presetDevice == null
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(appContext, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(appContext, autoConnect, callback)
            }
        }
        val latch = connectLatch ?: return false
        val signalled = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!signalled) {
            close()
            throw IllegalStateException("BLE connect timeout")
        }
        return synchronized(stateLock) { servicesResolved.get() && geoChar != null }
    }

    fun isConnected(): Boolean = synchronized(stateLock) { gatt != null && geoChar != null }

    /**
     * Runs the full 4-stage PAIR handshake over char 0x2000, subscribing to
     * PAIR indications and the NOT1 success notification first, then writing
     * the controller friendly name to the ID char 0x2002.
     *
     * Returns the negotiated identity, or null on failure. Persisting
     * device/nonce for later reconnect is the caller's job.
     */
    @SuppressLint("MissingPermission")
    fun performHandshake(
        controllerName: String,
        savedDevice: Int? = null,
        savedNonce: Int? = null,
    ): HandshakeResult? {
        val pair = synchronized(stateLock) { pairChar }
            ?: throw IllegalStateException("PAIR characteristic missing")
        val id = synchronized(stateLock) { idChar }
            ?: throw IllegalStateException("ID characteristic missing")
        val not1 = synchronized(stateLock) { not1Char }
            ?: throw IllegalStateException("NOT1 characteristic missing")

        pairIndications.clear()
        not1Success.set(false)

        // Subscribe: PAIR indicates, NOT1 notifies.
        enableCccd(pair, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
        enableCccd(not1, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)

        val engine = NikonPairingEngine()

        // Reconnect reuses the saved controller identity; first pairing uses a fresh one.
        val stage1 = if (savedDevice != null && savedNonce != null) {
            engine.buildStage1(savedDevice, savedNonce)
        } else {
            engine.buildStage1()
        }
        writeCharacteristicBlocking(pair, stage1.encode())
        Log.i(TAG, "handshake: stage1 written")

        val stage2Bytes = pollPairIndication() ?: run {
            Log.w(TAG, "handshake: stage2 indication timeout")
            return null
        }
        val stage2 = PairingMessage.decode(stage2Bytes)
        Log.i(TAG, "handshake: stage2 received (stage=${stage2.stage})")

        val stage3 = engine.verifyStage2AndBuildStage3(stage1, stage2) ?: run {
            Log.w(TAG, "handshake: stage2 verification failed")
            return null
        }
        writeCharacteristicBlocking(pair, stage3.encode())
        Log.i(TAG, "handshake: stage3 written")

        val stage4Bytes = pollPairIndication() ?: run {
            Log.w(TAG, "handshake: stage4 indication timeout")
            return null
        }
        val stage4 = PairingMessage.decode(stage4Bytes)
        Log.i(TAG, "handshake: stage4 received (stage=${stage4.stage})")

        val serial = engine.extractSerial(stage4)

        // NOT1 `01 00` success is best-effort: nsg says it is safe to proceed
        // with the ID write even if it arrives late or not at all.
        if (!not1Success.get()) {
            Log.i(TAG, "handshake: NOT1 success not yet observed; proceeding")
        }

        // The ID characteristic is a fixed 32-byte ASCII field, zero-padded.
        val idField = ByteArray(ID_FIELD_SIZE)
        val nameBytes = controllerName.toByteArray(StandardCharsets.US_ASCII)
        System.arraycopy(nameBytes, 0, idField, 0, minOf(nameBytes.size, ID_FIELD_SIZE))
        writeCharacteristicBlocking(id, idField)
        Log.i(TAG, "handshake: controller name written to ID char")

        // Return OUR controller identity (stage1 device/nonce) for persistence + reconnect.
        return HandshakeResult(stage1.device, stage1.nonce, serial)
    }

    @SuppressLint("MissingPermission")
    fun writeLocationPayload(payload: ByteArray) {
        require(payload.size == GEO_PAYLOAD_SIZE) { "Location payload must be 41 bytes" }
        val geo = synchronized(stateLock) { geoChar }
            ?: throw IllegalStateException("GEO characteristic missing")
        writeCharacteristicBlocking(geo, payload)
    }

    /** Writes the 10-byte TIME payload to char 0x2006 (camera clock sync). No-op if absent. */
    @SuppressLint("MissingPermission")
    fun writeTimePayload(payload: ByteArray) {
        require(payload.size == TIME_PAYLOAD_SIZE) { "Time payload must be 10 bytes" }
        val time = synchronized(stateLock) { timeChar } ?: return
        writeCharacteristicBlocking(time, payload)
    }

    fun hasTimeCharacteristic(): Boolean = synchronized(stateLock) { timeChar != null }

    @SuppressLint("MissingPermission")
    fun close() {
        synchronized(stateLock) {
            gatt?.disconnect()
            gatt?.close()
            gatt = null
            pairChar = null
            idChar = null
            geoChar = null
            not1Char = null
            connectLatch = null
            writeLatch = null
            descriptorLatch = null
            servicesResolved.set(false)
            pairIndications.clear()
            not1Success.set(false)
        }
    }

    private fun pollPairIndication(): ByteArray? =
        pairIndications.poll(INDICATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)

    @SuppressLint("MissingPermission")
    private fun enableCccd(characteristic: BluetoothGattCharacteristic, cccdValue: ByteArray) {
        val activeGatt = synchronized(stateLock) { gatt }
            ?: throw IllegalStateException("BluetoothGatt is null")
        if (!activeGatt.setCharacteristicNotification(characteristic, true)) {
            throw IllegalStateException("setCharacteristicNotification failed for ${characteristic.uuid}")
        }
        val descriptor = characteristic.getDescriptor(CCCD_UUID)
            ?: throw IllegalStateException("CCCD missing on ${characteristic.uuid}")
        descriptorLatch = CountDownLatch(1)
        descriptorStatus.set(BluetoothGatt.GATT_FAILURE)
        descriptor.value = cccdValue
        if (!activeGatt.writeDescriptor(descriptor)) {
            throw IllegalStateException("writeDescriptor returned false for ${characteristic.uuid}")
        }
        val latch = descriptorLatch ?: throw IllegalStateException("descriptor latch null")
        if (!latch.await(DESCRIPTOR_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("CCCD write timeout for ${characteristic.uuid}")
        }
        if (descriptorStatus.get() != BluetoothGatt.GATT_SUCCESS) {
            throw IllegalStateException(
                "CCCD write failed for ${characteristic.uuid} with status ${descriptorStatus.get()}"
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeCharacteristicBlocking(
        characteristic: BluetoothGattCharacteristic,
        payload: ByteArray
    ) {
        val activeGatt = synchronized(stateLock) { gatt }
            ?: throw IllegalStateException("BluetoothGatt is null")
        writeLatch = CountDownLatch(1)
        writeStatus.set(BluetoothGatt.GATT_FAILURE)
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = payload
        if (!activeGatt.writeCharacteristic(characteristic)) {
            throw IllegalStateException("writeCharacteristic returned false for ${characteristic.uuid}")
        }
        val latch = writeLatch ?: throw IllegalStateException("write latch null")
        if (!latch.await(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("Characteristic write timeout for ${characteristic.uuid}")
        }
        if (writeStatus.get() != BluetoothGatt.GATT_SUCCESS) {
            throw IllegalStateException(
                "Characteristic write failed for ${characteristic.uuid} with status ${writeStatus.get()}"
            )
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connectLatch?.countDown()
                return
            }
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    if (!gatt.discoverServices()) {
                        connectLatch?.countDown()
                    }
                }
                BluetoothGatt.STATE_DISCONNECTED -> connectLatch?.countDown()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connectLatch?.countDown()
                return
            }
            val service: BluetoothGattService? = gatt.getService(SERVICE_UUID)
            synchronized(stateLock) {
                pairChar = service?.getCharacteristic(PAIR_UUID)
                idChar = service?.getCharacteristic(ID_UUID)
                geoChar = service?.getCharacteristic(GEO_UUID)
                timeChar = service?.getCharacteristic(TIME_UUID)
                not1Char = service?.getCharacteristic(NOT1_UUID)
            }
            servicesResolved.set(service != null)
            connectLatch?.countDown()
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value ?: return
            when (characteristic.uuid) {
                PAIR_UUID -> pairIndications.offer(value.copyOf())
                NOT1_UUID -> {
                    if (value.size >= 2 && value[0] == 0x01.toByte() && value[1] == 0x00.toByte()) {
                        not1Success.set(true)
                        Log.i(TAG, "NOT1: success (01 00) received")
                    }
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            writeStatus.set(status)
            writeLatch?.countDown()
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                descriptorStatus.set(status)
                descriptorLatch?.countDown()
            }
        }
    }
}
