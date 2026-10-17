package com.dama.blepoc.location

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import java.util.TimeZone

/**
 * Encodes the Nikon LSS TIME characteristic (0x2006) payload: 10 bytes, little-endian.
 *
 *   offset 0  u16  year        (UTC)
 *   offset 2  u8   month 1-12
 *   offset 3  u8   day 1-31
 *   offset 4  u8   hour 0-23   (UTC)
 *   offset 5  u8   minute 0-59
 *   offset 6  u8   second 0-59
 *   offset 7  u8   dst_offset         (daylight-saving offset, minutes/… — camera display only)
 *   offset 8  u8   tz_offset_hours    (signed local offset hours)
 *   offset 9  u8   tz_offset_minutes  (local offset minutes)
 *
 * The time fields are UTC; the camera applies the timezone/DST offsets for its local display.
 */
object TimePayloadEncoder {

    fun encode(nowMillis: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): ByteArray {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = nowMillis }

        val totalOffsetMin = zone.getOffset(nowMillis) / 60_000
        val dstMin = if (zone.inDaylightTime(java.util.Date(nowMillis))) zone.dstSavings / 60_000 else 0
        val tzHours = totalOffsetMin / 60
        val tzMinutes = kotlin.math.abs(totalOffsetMin % 60)

        return ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(utc.get(Calendar.YEAR).toShort())
            .put((utc.get(Calendar.MONTH) + 1).toByte())
            .put(utc.get(Calendar.DAY_OF_MONTH).toByte())
            .put(utc.get(Calendar.HOUR_OF_DAY).toByte())
            .put(utc.get(Calendar.MINUTE).toByte())
            .put(utc.get(Calendar.SECOND).toByte())
            .put(dstMin.toByte())
            .put(tzHours.toByte())
            .put(tzMinutes.toByte())
            .array()
    }
}
