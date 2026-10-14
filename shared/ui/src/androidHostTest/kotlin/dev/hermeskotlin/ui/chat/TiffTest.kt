package dev.hermeskotlin.ui.chat

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TiffTest {
    @Test
    fun everyCompressionGivesTheSamePixels() {
        val expected = IntArray(W * H) { rgb(it % W, it / W) }
        listOf(
            "rgb-none", "rgb-packbits", "rgb-lzw", "rgb-lzw-strips", "rgb-deflate", "rgb-lzw-predictor",
            "rgb-deflate-predictor", "rgb-bigendian",
        ).forEach { name ->
            val image = assertNotNull(decodeTiff(fixture(name), maxEdge = 100), name)
            assertEquals(W, image.width, name)
            assertEquals(H, image.height, name)
            assertContentEquals(expected, image.argb, name)
            assertEquals(1, image.orientation, name)
        }
    }

    @Test
    fun grayPaletteBilevelAndAlpha() {
        val gray = assertNotNull(decodeTiff(fixture("gray-deflate"), 100))
        assertContentEquals(IntArray(W * H) { gray(it % W * 50 + it / W) }, gray.argb)

        val colors = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFF00.toInt())
        val palette = assertNotNull(decodeTiff(fixture("palette-packbits"), 100))
        assertContentEquals(IntArray(W * H) { colors[(it % W + it / W) % 4] }, palette.argb)

        val bilevel = assertNotNull(decodeTiff(fixture("bilevel-none"), 100))
        assertContentEquals(IntArray(W * H) { if ((it % W + it / W) % 2 == 0) gray(255) else gray(0) }, bilevel.argb)

        val rgba = assertNotNull(decodeTiff(fixture("rgba-lzw"), 100))
        assertEquals(0x800A141E.toInt(), rgba.argb[0])
        assertContentEquals(IntArray(W * H - 1) { rgb((it + 1) % W, (it + 1) / W) }, rgba.argb.copyOfRange(1, W * H))
    }

    @Test
    fun aLargePictureIsAveragedDownToTheEdge() {
        // 5 × 4 into an edge of 2: blocks of 3 × 3, the last ones narrower and shorter.
        val image = assertNotNull(decodeTiff(fixture("rgb-lzw"), maxEdge = 2))
        assertEquals(2, image.width)
        assertEquals(2, image.height)
        assertEquals(argb(60, 80, 215), image.argb[0])
        // x 3–4, y 3: r (180 + 240) / 2, g 240, b (135 + 95) / 2.
        assertEquals(argb(210, 240, 115), image.argb[3])
    }

    @Test
    fun brokenFilesAreNull() {
        listOf(
            "broken-truncated", "broken-header-only", "broken-huge", "broken-lying-strip", "broken-lzw-noise",
            "broken-garbage", "broken-ifd-offset", "broken-ifd-count",
        ).forEach { assertNull(decodeTiff(fixture(it), 100), it) }
        assertNull(decodeTiff(ByteArray(0), 100))
        assertNull(decodeTiff("not a tiff".encodeToByteArray(), 100))
    }

    @Test
    fun aPictureOverTheCapIsRefusedFromItsHeader() {
        assertNull(decodeTiff(fixture("rgb-none"), 100, maxPixels = W * H - 1L))
        assertNotNull(decodeTiff(fixture("rgb-none"), 100, maxPixels = W * H.toLong()))
        // Two sides whose product passes a Long.
        assertNull(decodeTiff(grayTiff(0xFFFFFFFFL, 0xFFFFFFFFL, ByteArray(16), compression = 1), 100))
        // 256 MP from a small Deflate strip: refused by its size before anything is inflated.
        assertNull(decodeTiff(grayTiff(16_000, 16_000, deflate(ByteArray(1 shl 20), times = 1), compression = 8), 100))
    }

    @Test
    fun aStripThatInflatesPastItsRowsIsOnlyReadForThem() {
        val image = assertNotNull(decodeTiff(grayTiff(4, 4, deflate(ByteArray(1 shl 20), times = 64), compression = 8), 100))
        assertContentEquals(IntArray(16) { gray(0) }, image.argb)
    }

    @Test
    fun changedOrCutFilesNeverThrow() {
        val random = Random(42)
        val good = listOf("rgb-packbits", "rgb-lzw", "rgb-lzw-strips", "rgb-deflate-predictor", "rgb-bigendian", "palette-packbits", "bilevel-none", "rgba-lzw")
            .map(::fixture)
        repeat(5000) { i ->
            val bytes = good[i % good.size].copyOf()
            val mutant = if (random.nextInt(5) == 0) {
                bytes.copyOf(random.nextInt(4, bytes.size))
            } else {
                repeat(random.nextInt(1, 9)) { bytes[random.nextInt(4, bytes.size)] = random.nextInt(256).toByte() }
                bytes
            }
            // Null or a picture; any exception fails the test.
            decodeTiff(mutant, maxEdge = 64)?.let { assertTrue(it.width in 1..64 && it.height in 1..64) }
        }
    }

    @Test
    fun recognisesBothByteOrders() {
        assertTrue(isTiff(fixture("rgb-none")))
        assertTrue(isTiff(fixture("rgb-bigendian")))
        assertFalse(isTiff(byteArrayOf(0x49, 0x49, 0x2B, 0x00)))
        assertFalse(isTiff(byteArrayOf(0x49, 0x49, 0x2A)))
    }

    private fun fixture(name: String): ByteArray =
        assertNotNull(javaClass.getResourceAsStream("/tiff/$name.tif"), name).use { it.readBytes() }

    private fun rgb(x: Int, y: Int) = argb(x * 60, y * 80, 255 - x * 40)

    private fun gray(v: Int) = argb(v, v, v)

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun deflate(chunk: ByteArray, times: Int): ByteArray {
        val deflater = Deflater(9)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        repeat(times) {
            deflater.setInput(chunk)
            while (!deflater.needsInput()) out.write(buffer, 0, deflater.deflate(buffer))
        }
        deflater.finish()
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }

    /** A little-endian 8-bit gray TIFF with one strip of [strip]. */
    private fun grayTiff(width: Long, height: Long, strip: ByteArray, compression: Int): ByteArray {
        val entries = listOf(
            Triple(256, 4, width), Triple(257, 4, height), Triple(258, 3, 8L), Triple(259, 3, compression.toLong()),
            Triple(262, 3, 1L), Triple(273, 4, (8 + 2 + 9 * 12 + 4).toLong()), Triple(277, 3, 1L), Triple(278, 4, height),
            Triple(279, 4, strip.size.toLong()),
        )
        val out = ByteArrayOutputStream()
        fun le(value: Long, bytes: Int) = repeat(bytes) { out.write((value ushr (8 * it)).toInt() and 0xFF) }
        out.write("II".encodeToByteArray())
        le(42, 2)
        le(8, 4)
        le(entries.size.toLong(), 2)
        entries.forEach { (tag, type, value) ->
            le(tag.toLong(), 2)
            le(type.toLong(), 2)
            le(1, 4)
            le(value, 4)
        }
        le(0, 4)
        out.write(strip)
        return out.toByteArray()
    }

    private companion object {
        const val W = 5
        const val H = 4
    }
}
