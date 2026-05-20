// UuidV7Test — mirrors the Web SDK's `uuid.test.ts`:
//   - canonical 36-char format
//   - encodes current timestamp in the first 48 bits
//   - monotonically ordered across rapid-fire calls (counter logic)
//   - high-throughput monotonicity (50k ids — counter overflow path)

package com.adfinia.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UuidV7Test {

    @Before fun setUp() { UuidV7.resetForTesting() }

    @Test
    fun `emits the canonical 36-character format`() {
        val id = UuidV7.generate()
        val pattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        assertTrue("id $id did not match $pattern", pattern.matches(id))
    }

    @Test
    fun `encodes the current timestamp in the first 48 bits`() {
        val before = System.currentTimeMillis()
        val id = UuidV7.generate()
        val after = System.currentTimeMillis()
        val tsHex = id.replace("-", "").substring(0, 12)
        val ts = tsHex.toLong(16)
        assertTrue("ts=$ts < before=$before", ts >= before)
        assertTrue("ts=$ts > after=$after", ts <= after)
    }

    @Test
    fun `emits monotonically-ordered ids across calls`() {
        val ids = Array(100) { UuidV7.generate() }
        val sorted = ids.copyOf().also { it.sort() }
        // ids should already be sorted because UUIDv7 is time-ordered + the
        // 12-bit counter preserves order within a single millisecond.
        for (i in ids.indices) {
            assertEquals("ids[$i] not in sorted order", sorted[i], ids[i])
        }
    }

    @Test
    fun `high-throughput burst stays monotonic across counter boundary`() {
        // 50k rapid-fire ids will straddle several millisecond boundaries
        // and exercise the 12-bit counter overflow path.
        val ids = Array(50_000) { UuidV7.generate() }
        for (i in 1 until ids.size) {
            assertTrue(
                "id $i went backwards: ${ids[i - 1]} -> ${ids[i]}",
                ids[i] > ids[i - 1],
            )
        }
    }

    @Test
    fun `every id is unique within a million-draw burst`() {
        // 1M is overkill for unit tests but cheap (<2s) and proves the rand
        // bits + counter combination guarantees no collisions.
        val seen = HashSet<String>(1_000_000)
        repeat(1_000_000) {
            assertTrue(seen.add(UuidV7.generate()))
        }
    }

    @Test
    fun `sets version 7 and variant 10`() {
        val id = UuidV7.generate()
        // Version = char at index 14 (first nibble of byte 6, after dashes).
        assertEquals('7', id[14])
        // Variant = first char of group 4 ∈ {8, 9, a, b}.
        assertTrue("variant char ${id[19]} not in 8/9/a/b", id[19] in setOf('8', '9', 'a', 'b'))
    }
}
