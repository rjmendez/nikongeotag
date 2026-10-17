package com.dama.blepoc.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Pure-Kotlin port of Nikon's SnapBridge "smart device" BLE pairing handshake crypto.
 *
 * Ported verbatim (logic/endianness/ordering) from the open-source, reverse-engineered
 * reference project github.com/hurui200320/nsg (kotlin-poc protocol package). Only the
 * package, class/field names, and public API types are adapted. The reference verified
 * this protocol against real Nikon Z bodies.
 *
 * This replaces the vendor native libLsSec-jni.so for legitimate interop with a Nikon Z
 * camera the owner physically possesses (GPS-metadata use).
 *
 * The underlying block cipher is STANDARD Blowfish, so Java's built-in
 * Blowfish/ECB/NoPadding is used (identical results to nsg, which also uses javax.crypto).
 */

/**
 * A 17-byte pairing message used in the Nikon smart-device handshake.
 *
 * Layout (little-endian on the wire):
 *   byte 0       stage
 *   bytes 1-8    timestamp (uint64)
 *   bytes 9-12   device (uint32)
 *   bytes 13-16  nonce  (uint32)
 *
 * In stage 4 the last 8 bytes (device||nonce region) carry an ASCII serial instead.
 *
 * encode()/decode() round-trip: decode(encode()) reproduces stage/timestamp and the
 * raw 32-bit device/nonce words bit-for-bit (stored here as [Int]s).
 */
class PairingMessage(
    val stage: Byte,
    val timestamp: Long,
    val device: Int,
    val nonce: Int,
) {
    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(stage)
        buffer.putLong(timestamp)
        buffer.putInt(device)
        buffer.putInt(nonce)
        return buffer.array()
    }

    override fun toString(): String =
        "PairingMessage(stage=$stage, timestamp=$timestamp, " +
            "device=0x${Integer.toHexString(device)}, nonce=0x${Integer.toHexString(nonce)})"

    companion object {
        const val SIZE: Int = 17

        fun decode(bytes: ByteArray): PairingMessage {
            require(bytes.size == SIZE) {
                "Pairing message must be exactly $SIZE bytes, got ${bytes.size}"
            }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val stage = buffer.get()
            val timestamp = buffer.getLong()
            val device = buffer.getInt()
            val nonce = buffer.getInt()
            return PairingMessage(stage, timestamp, device, nonce)
        }
    }
}

/**
 * Implements the custom Blowfish-based hash used in the Nikon pairing handshake.
 *
 * Standard Blowfish in ECB mode, no padding. 8-byte key: FF FF AA 55 11 22 33 00.
 * Hash input blocks are treated as big-endian 32-bit words. This is a CBC-MAC-like
 * construction: init (left=0x01020304, right=0x05060708); for each 32-bit word pair,
 * XOR into the running state then blowfish-encipher, carrying the output forward.
 */
internal class BlowfishHasher {

    private val cipher: Cipher = Cipher.getInstance("Blowfish/ECB/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(KEY, "Blowfish"))
    }

    /**
     * [blocks] must contain an even number of 32-bit big-endian words.
     * Returns the final (left, right) pair.
     */
    fun hash(blocks: IntArray): Pair<Int, Int> {
        require(blocks.size % 2 == 0) { "blocks length must be even" }

        var left = 0x01020304
        var right = 0x05060708

        for (i in blocks.indices step 2) {
            val inL = blocks[i] xor left
            val inR = blocks[i + 1] xor right
            val enc = encryptBlock(inL, inR)
            left = enc.first
            right = enc.second
        }

        return left to right
    }

    private fun encryptBlock(left: Int, right: Int): Pair<Int, Int> {
        val input = ByteBuffer.allocate(8)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(left)
            .putInt(right)
            .array()

        val output = cipher.update(input)
            ?: throw IllegalStateException("Blowfish encryption failed")

        val buffer = ByteBuffer.wrap(output).order(ByteOrder.BIG_ENDIAN)
        return buffer.getInt() to buffer.getInt()
    }

    companion object {
        private val KEY = byteArrayOf(
            0xFF.toByte(), 0xFF.toByte(), 0xAA.toByte(), 0x55.toByte(),
            0x11.toByte(), 0x22.toByte(), 0x33.toByte(), 0x00.toByte(),
        )
    }
}

/**
 * Drives the 4-stage Nikon smart-device pairing handshake.
 */
class NikonPairingEngine {

    private val hasher: BlowfishHasher = BlowfishHasher()

    private val random = SecureRandom()

    /**
     * Build stage 1 (app -> cam): random timestamp, random device whose least-significant
     * wire byte is 0x01 (i.e. (device and 0xFF) == 0x01), and a random nonce.
     */
    fun buildStage1(): PairingMessage {
        val timestamp = random.nextLong()
        val device = generateDeviceId()
        val nonce = generateNonce()
        return PairingMessage(stage = 0x01, timestamp = timestamp, device = device, nonce = nonce)
    }

    /** Reconnect: reuse the controller identity (device/nonce) saved from the initial pairing. */
    fun buildStage1(device: Int, nonce: Int): PairingMessage {
        return PairingMessage(stage = 0x01, timestamp = random.nextLong(), device = device, nonce = nonce)
    }

    /**
     * Verify the camera's stage-2 challenge, find the matching salt, and build the
     * stage-3 response (app -> cam). Returns null if no salt matches.
     *
     * Stage 3 reuses stage 1's timestamp; device/nonce carry the Blowfish-derived
     * response (little-endian on the wire, hence the reverseBytes).
     */
    fun verifyStage2AndBuildStage3(
        stage1: PairingMessage,
        stage2: PairingMessage,
    ): PairingMessage? {
        val salt = findSalt(stage1, stage2) ?: return null

        val (ourLo, ourHi) = timestampHalvesBe(stage1.timestamp)
        val (camLo, camHi) = timestampHalvesBe(stage2.timestamp)

        // Stage 3: timestamp order swapped relative to the stage-2 salt match.
        val blocks = intArrayOf(
            SALTS[salt][0], SALTS[salt][1],
            ourLo, ourHi,
            camLo, camHi,
        )
        val (r0, r1) = hasher.hash(blocks)
        return PairingMessage(
            stage = 0x03,
            timestamp = stage1.timestamp,
            device = Integer.reverseBytes(r0),
            nonce = Integer.reverseBytes(r1),
        )
    }

    /**
     * Extract the 8-byte ASCII camera serial number from stage 4 (last 8 wire bytes).
     */
    fun extractSerial(stage4: PairingMessage): String {
        return stage4.encode()
            .copyOfRange(9, 17)
            .toString(Charsets.US_ASCII)
    }

    private fun generateDeviceId(): Int {
        // First byte on the wire must be 0x01. On a little-endian uint32 that is the LSB.
        return (random.nextInt() and 0xFFFFFF00.toInt()) or 0x01
    }

    private fun generateNonce(): Int = random.nextInt()

    private fun findSalt(stage1: PairingMessage, stage2: PairingMessage): Int? {
        val (camLo, camHi) = timestampHalvesBe(stage2.timestamp)
        val (ourLo, ourHi) = timestampHalvesBe(stage1.timestamp)
        val camDeviceBe = Integer.reverseBytes(stage2.device)
        val camNonceBe = Integer.reverseBytes(stage2.nonce)

        for (i in SALTS.indices) {
            val blocks = intArrayOf(
                SALTS[i][0], SALTS[i][1],
                camLo, camHi,
                ourLo, ourHi,
            )
            val (h0, h1) = hasher.hash(blocks)
            if (h0 == camDeviceBe && h1 == camNonceBe) {
                return i
            }
        }
        return null
    }

    private fun timestampHalvesBe(timestamp: Long): Pair<Int, Int> {
        val lo = (timestamp and 0xFFFFFFFFL).toInt()
        val hi = ((timestamp ushr 32) and 0xFFFFFFFFL).toInt()
        return Integer.reverseBytes(lo) to Integer.reverseBytes(hi)
    }

    companion object {
        private val SALTS = arrayOf(
            intArrayOf(0x704066e4.toInt(), 0x0433d552.toInt()),
            intArrayOf(0xed4b8fac.toInt(), 0x15f7e47b.toInt()),
            intArrayOf(0x24471f11.toInt(), 0x8b5ea1fc.toInt()),
            intArrayOf(0x05960c31.toInt(), 0x2b8c7f41.toInt()),
            intArrayOf(0xfda588c1.toInt(), 0xeba8b1f3.toInt()),
            intArrayOf(0x99166056.toInt(), 0x1bd3d550.toInt()),
            intArrayOf(0xcd32687f.toInt(), 0xa9e28a30.toInt()),
            intArrayOf(0x2a8fe834.toInt(), 0xdec7ebf4.toInt()),
        )
    }
}
