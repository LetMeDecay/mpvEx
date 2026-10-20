package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import org.junit.Assert.*
import org.junit.Test

class ContainerHeaderProbeTest {
  private fun u32(value: Int) = byteArrayOf(
    (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
  )

  private fun putU32(bytes: ByteArray, at: Int, value: Int) {
    bytes[at] = (value ushr 24).toByte()
    bytes[at + 1] = (value ushr 16).toByte()
    bytes[at + 2] = (value ushr 8).toByte()
    bytes[at + 3] = value.toByte()
  }

  private fun box(type: String, payload: ByteArray): ByteArray =
    u32(payload.size + 8) + type.toByteArray(Charsets.US_ASCII) + payload

  @Test fun readsDurationAndDimensionsFromMp4() {
    val mvhd = ByteArray(100).also {
      it[0] = 0 // version 0
      putU32(it, 12, 1000)
      putU32(it, 16, 123_456)
    }
    val tkhd = ByteArray(84).also {
      it[0] = 0
      putU32(it, 76, 1920 shl 16)
      putU32(it, 80, 1080 shl 16)
    }
    val file = box("ftyp", "isom".toByteArray(Charsets.US_ASCII)) +
      box("moov", box("mvhd", mvhd) + box("trak", box("tkhd", tkhd)))

    val header = ContainerHeaderProbe.parse(file)
    assertEquals(123_456L, header?.durationMs)
    assertEquals(1920, header?.width)
    assertEquals(1080, header?.height)
  }

  @Test fun readsTrailingMoovFromTailSlice() {
    val mvhd = ByteArray(100).also {
      it[0] = 0
      putU32(it, 12, 1000)
      putU32(it, 16, 60_000)
    }
    val moov = box("moov", box("mvhd", mvhd))
    // A tail slice starts inside mdat; the moov box must be found by signature.
    val file = ByteArray(32) + moov
    assertEquals(60_000L, ContainerHeaderProbe.parse(file)?.durationMs)
  }

  private fun ebmlId(vararg bytes: Int) = ByteArray(bytes.size) { bytes[it].toByte() }

  private fun element(id: ByteArray, payload: ByteArray): ByteArray {
    check(payload.size < 127)
    return id + byteArrayOf((0x80 or payload.size).toByte()) + payload
  }

  private fun floatBytes(value: Float): ByteArray {
    val bits = java.lang.Float.floatToIntBits(value)
    return u32(bits)
  }

  @Test fun readsDurationAndDimensionsFromMatroska() {
    val scale = element(ebmlId(0x2A, 0xD7, 0xB1), byteArrayOf(0x0F, 0x42, 0x40)) // 1_000_000
    val duration = element(ebmlId(0x44, 0x89), floatBytes(123_456f))
    val info = element(ebmlId(0x15, 0x49, 0xA9, 0x66), scale + duration)
    val width = element(ebmlId(0xB0), byteArrayOf(0x07, 0x80.toByte())) // 1920
    val height = element(ebmlId(0xBA), byteArrayOf(0x04, 0x38)) // 1080
    val video = element(ebmlId(0xE0), width + height)
    val track = element(ebmlId(0xAE), video)
    val tracks = element(ebmlId(0x16, 0x54, 0xAE, 0x6B), track)
    val segment = ebmlId(0x18, 0x53, 0x80, 0x67) + byteArrayOf(0xFF.toByte()) + info + tracks
    val ebmlHeader = ebmlId(0x1A, 0x45, 0xDF, 0xA3) + byteArrayOf(0x80.toByte())

    val header = ContainerHeaderProbe.parse(ebmlHeader + segment)
    assertEquals(123_456L, header?.durationMs)
    assertEquals(1920, header?.width)
    assertEquals(1080, header?.height)
  }

  @Test fun unknownOrTruncatedDataIsRejected() {
    assertNull(ContainerHeaderProbe.parse(ByteArray(0)))
    assertNull(ContainerHeaderProbe.parse(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)))
    assertNull(ContainerHeaderProbe.parse(u32(8) + "junk".toByteArray(Charsets.US_ASCII)))
  }
}
