// UUIDv7 — time-ordered 128-bit identifier per RFC 9562 §5.7.
//
// Monotonic ordering within the same millisecond is preserved by a 12-bit
// counter stored in the rand_a section (bits 48..59, ignoring the version
// nibble) — RFC 9562 §6.2 "Method 1: Monotonic Random". Counter overflow
// inside a single millisecond bumps the clock by 1 ms so the next id still
// sorts after the previous batch.
//
// This file mirrors the Web SDK's `uuid.ts` 1:1 — same algorithm, same
// counter width, same canonical string format. Generated ids from any SDK
// (web / iOS / Android) sort consistently when interleaved.

package com.adfinia.sdk

import java.security.SecureRandom

internal object UuidV7 {
    private val rng = SecureRandom()
    private val lock = Any()

    @Volatile private var lastMs: Long = 0L
    @Volatile private var counter: Int = 0

    /**
     * Generate a UUIDv7 string in the canonical 36-character form
     * `xxxxxxxx-xxxx-7xxx-yxxx-xxxxxxxxxxxx` where the first 48 bits encode
     * milliseconds-since-epoch and the next 12 bits encode the per-ms counter.
     */
    @JvmStatic
    fun generate(): String = synchronized(lock) {
        var ms = System.currentTimeMillis()
        if (ms == lastMs) {
            counter += 1
            if (counter > 0xfff) {
                // Counter overflow within a single millisecond — push to the
                // next ms so monotonicity holds. Very rare in practice.
                ms = lastMs + 1
                lastMs = ms
                counter = 0
            }
        } else if (ms < lastMs) {
            // Clock went backwards (NTP adjust, suspend/resume). Pin to the
            // last seen ms so emitted ids never go backwards.
            ms = lastMs
            counter += 1
            if (counter > 0xfff) {
                ms = lastMs + 1
                lastMs = ms
                counter = 0
            }
        } else {
            lastMs = ms
            counter = 0
        }

        val bytes = ByteArray(16)
        rng.nextBytes(bytes)

        // First 48 bits = unix_ts_ms (big-endian).
        bytes[0] = ((ms shr 40) and 0xff).toByte()
        bytes[1] = ((ms shr 32) and 0xff).toByte()
        bytes[2] = ((ms shr 24) and 0xff).toByte()
        bytes[3] = ((ms shr 16) and 0xff).toByte()
        bytes[4] = ((ms shr 8) and 0xff).toByte()
        bytes[5] = (ms and 0xff).toByte()

        // Bits 48..59 = 12-bit monotonic counter (rand_a section per RFC 9562 §5.7).
        bytes[6] = ((counter shr 8) and 0x0f).toByte()
        bytes[7] = (counter and 0xff).toByte()

        // Version = 7 (high nibble of byte 6).
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x70).toByte()
        // Variant = 10 (high two bits of byte 8).
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()

        format(bytes)
    }

    private fun format(b: ByteArray): String {
        val hex = StringBuilder(36)
        for (i in b.indices) {
            if (i == 4 || i == 6 || i == 8 || i == 10) hex.append('-')
            val v = b[i].toInt() and 0xff
            hex.append(HEX[v ushr 4])
            hex.append(HEX[v and 0x0f])
        }
        return hex.toString()
    }

    private val HEX = charArrayOf(
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'a', 'b', 'c', 'd', 'e', 'f',
    )

    /** Internal — reset state between tests. */
    internal fun resetForTesting() = synchronized(lock) {
        lastMs = 0L
        counter = 0
    }
}
