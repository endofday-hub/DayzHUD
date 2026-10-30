package com.dayz.hud

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KivicPacketTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun commandFramesMatchOriginalApp() {
        // command 바이트 0x02가 escape되어 7D 7F
        assertEquals("02 7D 7F 06 00 03", KivicPacket.uartCheck().hex())
        assertEquals("02 7D 7F 0F 00 03", KivicPacket.keepAlive().hex())
        assertEquals("02 7D 7F 07 00 00 00 00 09 03", KivicPacket.mode(KivicPacket.MODE_TBT).hex())
    }

    @Test
    fun settingsFrames() {
        val frames = KivicPacket.settings(HudSettings(theme = 1, keystone = 0.1f)).map { it.hex() }
        assertEquals(12, frames.size) // 자동 밝기면 수동 밝기 패킷 생략
        assertEquals("02 7D 7F 09 0B 00 00 00 01 03", frames[0])
        // KeyStone: p1 = 0x03 → 7D 7E, payload float 0.1f = 3D CC CC CD
        assertEquals("02 7D 7F 7D 7E 00 3D CC CC CD 03", frames[3])
        assertEquals(13, KivicPacket.settings(HudSettings(autoBrightness = false)).size)
        // SystemTime(2,1,0): p1 0x01 그대로, payload는 long 시각 + utf 타임존
        assertTrue(frames.any { it.startsWith("02 7D 7F 01 00 ") })
    }

    @Test
    fun escapeRoundTrip() {
        val payload = bytes(0x02, 0x03, 0x7D, 0x41)
        val f = KivicPacket.frame(2, 18, 2, payload)
        assertEquals(0x02, f.first().toInt())
        assertEquals(0x03, f.last().toInt())
        // 본문에는 STX/ETX가 나오면 안 된다
        assertTrue(f.drop(1).dropLast(1).none { it == 0x02.toByte() || it == 0x03.toByte() })
        assertArrayEquals(bytes(2, 18, 2) + payload, KivicPacket.unescape(f.copyOfRange(1, f.size - 1)))
    }

    @Test
    fun tbtHeaderAndLength() {
        val body = KivicPacket.tbt(Tbt(turnCode = 12, turnDistance = 300, turnName = "강남"))
            .let { KivicPacket.unescape(it.copyOfRange(1, it.size - 1)) }
        assertArrayEquals(bytes(2, 18, 2), body.copyOfRange(0, 3))
        // connected | 4 int | totalTime long | 2 int | utf("강남" = 2+6) | 2 int | safe + 4 int | block + 2 int
        // | lane + laneDistance | 빈 리스트 3개의 size int
        val expected = 1 + 4 * 4 + 8 + 4 * 2 + (2 + 6) + 4 * 2 + (1 + 16) + (1 + 8) + (1 + 4) + 12
        assertEquals(3 + expected, body.size)
    }
}
