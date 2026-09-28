package earth.levi.flowopenrouter.client

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WavHeaderTest {

    @Test
    fun wrapsPcmWithValidRiffHeader() {
        val pcm = ByteArray(8) { it.toByte() }
        val wav = OpenRouterClient.wrapWav(pcm)

        // 44-byte RIFF/WAVE header + payload
        assertArrayEquals("RIFF".toByteArray(), wav.copyOfRange(0, 4))
        assertArrayEquals("WAVE".toByteArray(), wav.copyOfRange(8, 12))
        assertArrayEquals("fmt ".toByteArray(), wav.copyOfRange(12, 16))
        assertArrayEquals("data".toByteArray(), wav.copyOfRange(36, 40))

        // riff size = 36 + data size; data size = pcm length
        assertEqualsLE(36 + pcm.size, wav, 4)
        assertEqualsLE(pcm.size, wav, 40)

        // 16 kHz mono 16-bit PCM
        assertEqualsLE(16, wav, 16)        // fmt chunk size
        assertEqualsByte(1, wav, 20)       // audio format = PCM
        assertEqualsByte(1, wav, 22)       // channels = mono
        assertEqualsLE(16000, wav, 24)     // sample rate
        assertEqualsLE(16000 * 2, wav, 28) // byte rate
        assertEqualsByte(2, wav, 32)       // block align
        assertEqualsByte(16, wav, 34)      // bits per sample

        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
        assertEquals(44 + pcm.size, wav.size)
    }

    @Test
    fun wrapsEmptyPcm() {
        val wav = OpenRouterClient.wrapWav(ByteArray(0))
        assertEqualsLE(36, wav, 4)
        assertEqualsLE(0, wav, 40)
        assertEquals(44, wav.size)
    }

    private fun assertEqualsByte(expected: Int, bytes: ByteArray, offset: Int) {
        org.junit.Assert.assertEquals(expected, bytes[offset].toInt() and 0xFF)
    }

    private fun assertEqualsLE(expected: Int, bytes: ByteArray, offset: Int) {
        var value = 0
        for (i in 0 until 4) {
            value = value or ((bytes[offset + i].toInt() and 0xFF) shl (8 * i))
        }
        org.junit.Assert.assertEquals(expected, value)
    }
}