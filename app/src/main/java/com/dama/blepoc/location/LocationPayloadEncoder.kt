package com.dama.blepoc.location

import android.location.Location
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar

object LocationPayloadEncoder {
    private const val FLAG_LATITUDE = 1 shl 0
    private const val FLAG_LONGITUDE = 1 shl 1
    private const val FLAG_SATELLITES = 1 shl 2
    private const val FLAG_ALTITUDE = 1 shl 3
    private const val FLAG_TIME = 1 shl 4
    private const val FLAG_GPS = 1 shl 5
    private const val FLAG_MAP = 1 shl 6

    private const val NORTH_LAT = 'N'.code.toByte()
    private const val SOUTH_LAT = 'S'.code.toByte()
    private const val EAST_LON = 'E'.code.toByte()
    private const val WEST_LON = 'W'.code.toByte()
    private const val ALT_PLUS = 'P'.code.toByte()
    private const val ALT_MINUS = 'M'.code.toByte()

    private val MAP_WGS84 = byteArrayOf(
        WEST_LON, 'G'.code.toByte(), SOUTH_LAT, '-'.code.toByte(), '8'.code.toByte(), '4'.code.toByte(),
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0
    )

    fun encode(location: Location): ByteArray {
        require(location.latitude.isFinite() && location.longitude.isFinite()) {
            "invalid GPS coordinates"
        }

        // Nikon GEO header is always 0x007F (all lat/lon/sat/alt/time/gps/map flags set),
        // regardless of whether satellite count is available in the fix.
        val flags = FLAG_LATITUDE or FLAG_LONGITUDE or FLAG_SATELLITES or
            FLAG_ALTITUDE or FLAG_TIME or FLAG_GPS or FLAG_MAP

        val lat = toDms(location.latitude, true)
        val lon = toDms(location.longitude, false)
        val altitude = location.altitude
        val calendar = Calendar.getInstance().apply {
            timeInMillis = location.time
            val tzOffsetMs = get(Calendar.ZONE_OFFSET) + get(Calendar.DST_OFFSET)
            timeInMillis = location.time - (tzOffsetMs / 60_000) * 60_000L
        }

        val satellites = location.extras?.getInt("satellites") ?: 0
        val altAbs = kotlin.math.abs(altitude)
        val altShort = if (altAbs > Short.MAX_VALUE.toDouble()) {
            Short.MAX_VALUE.toInt()
        } else {
            altAbs.toInt()
        }

        return ByteBuffer.allocate(41).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(flags.toShort())
            .put(lat.ref)
            .put(lat.deg)
            .put(lat.min)
            .put(lat.subMin1)
            .put(lat.subMin2)
            .put(lon.ref)
            .put(lon.deg)
            .put(lon.min)
            .put(lon.subMin1)
            .put(lon.subMin2)
            .put(satellites.coerceIn(0, 255).toByte())
            .put(if (altitude >= 0.0) ALT_PLUS else ALT_MINUS)
            .putShort(altShort.toShort())
            .putShort(calendar.get(Calendar.YEAR).toShort())
            .put((calendar.get(Calendar.MONTH) + 1).toByte())
            .put(calendar.get(Calendar.DAY_OF_MONTH).toByte())
            .put(calendar.get(Calendar.HOUR_OF_DAY).toByte())
            .put(calendar.get(Calendar.MINUTE).toByte())
            .put(calendar.get(Calendar.SECOND).toByte())
            .put((calendar.get(Calendar.MILLISECOND) / 10).toByte())
            .put(1)
            .put(MAP_WGS84)
            .array()
    }

    private data class Dms(
        val ref: Byte,
        val deg: Byte,
        val min: Byte,
        val subMin1: Byte,
        val subMin2: Byte
    )

    private fun toDms(value: Double, isLatitude: Boolean): Dms {
        val ref = when {
            isLatitude && value >= 0 -> NORTH_LAT
            isLatitude && value < 0 -> SOUTH_LAT
            !isLatitude && value >= 0 -> EAST_LON
            else -> WEST_LON
        }

        val absolute = kotlin.math.abs(value)
        val deg = kotlin.math.floor(absolute).toInt()
        val minuteFloat = (absolute - deg) * 60.0
        val min = kotlin.math.floor(minuteFloat).toInt()
        val subMinuteFloat = (minuteFloat - min) * 100.0
        val subMin1 = kotlin.math.floor(subMinuteFloat).toInt()
        val subMin2 = kotlin.math.floor((subMinuteFloat - subMin1) * 100.0).toInt()

        return Dms(
            ref = ref,
            deg = deg.toByte(),
            min = min.toByte(),
            subMin1 = subMin1.toByte(),
            subMin2 = subMin2.toByte()
        )
    }
}
