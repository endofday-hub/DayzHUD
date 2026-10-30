package com.dayz.hud

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.TimeZone

// Kivic SM HUD 프레임: STX | escape(cmd p1 p2 payload) | ETX. 상세는 docs/kivic-protocol.md
object KivicPacket {
    private const val STX = 0x02
    private const val ETX = 0x03
    private const val ESC = 0x7D
    const val MODE_TBT = 9 // ANDROID_TBT_MODE

    fun frame(cmd: Int, p1: Int, p2: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArrayOutputStream().apply { write(STX) }
        for (b in byteArrayOf(cmd.toByte(), p1.toByte(), p2.toByte()) + payload) {
            val v = b.toInt() and 0xFF
            if (v == STX || v == ETX || v == ESC) {
                out.write(ESC)
                out.write(v xor ESC)
            } else out.write(v)
        }
        out.write(ETX)
        return out.toByteArray()
    }

    /** STX/ETX 사이 본문 → [cmd, p1, p2, payload…] */
    fun unescape(body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var esc = false
        for (b in body) {
            val v = b.toInt() and 0xFF
            when {
                esc -> { out.write(v xor ESC); esc = false }
                v == ESC -> esc = true
                else -> out.write(v)
            }
        }
        return out.toByteArray()
    }

    fun uartCheck() = command(6, 0)
    fun keepAlive() = command(15, 0)
    fun mode(mode: Int) = command(7, 0, payload { writeInt(mode) })

    // 필드 순서는 원본 TBTTmapDataCommandPacket.appendPayload 그대로
    fun tbt(t: Tbt) = command(18, 2, payload {
        writeBoolean(t.connected)
        writeInt(t.naviDisplayStatus)
        writeInt(t.naviStatus)
        writeInt(t.speed)
        writeInt(t.totalDistance)
        writeLong(t.totalTime)
        writeInt(t.turnDistance)
        writeInt(t.turnCode)
        writeUTF(t.turnName)
        writeInt(t.nextTurnDistance)
        writeInt(t.nextTurnCode)
        writeBoolean(t.safe)
        writeInt(t.safeCode)
        writeInt(t.safeDistance)
        writeInt(t.safeId)
        writeInt(t.safeSpeedLimit)
        writeBoolean(t.block)
        writeInt(t.blockDistance)
        writeInt(t.blockSpeed)
        writeBoolean(t.lane)
        writeInt(t.laneDistance)
        for (list in listOf(t.laneTurnInfo, t.laneEtc, t.laneAvailable)) {
            writeInt(list.size)
            list.forEach { writeInt(it) }
        }
    })

    // 원본 앱의 Display*/KeyStone/LayoutSize/… CommandPacket과 같은 ID·payload
    fun settings(s: HudSettings): List<ByteArray> = listOfNotNull(
        command(9, 11, payload { writeInt(s.theme) }),
        command(9, 13, payload { writeInt(s.navTheme) }),
        command(14, 0, payload { writeFloat(s.scale) }),
        command(3, 0, payload { writeFloat(s.keystone) }),
        command(22, 0, payload { writeInt(if (s.reverse) 1 else 0) }),
        command(8, 1, payload { writeBoolean(s.fullScreen) }),
        command(9, 3, payload { writeBoolean(s.showSpeed) }),
        command(9, 12, payload { writeBoolean(s.speedGauge) }),
        command(9, 10, payload { writeInt(s.speedColor) }),
        // HUD는 시간을 저장하지 않음 (공식 SDK 문서) → 시계 켜기 전에 매번 현재 시각 전송
        command(1, 0, payload { writeLong(System.currentTimeMillis()); writeUTF(TimeZone.getDefault().id) }),
        command(9, 4, payload { writeBoolean(s.clock) }),
        command(2, 1, payload { writeBoolean(s.autoBrightness) }),
        if (s.autoBrightness) null else command(2, 5, payload { writeInt(s.brightness) }),
    )

    private fun command(p1: Int, p2: Int, payload: ByteArray = ByteArray(0)) = frame(2, p1, p2, payload)

    private fun payload(block: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().also { DataOutputStream(it).apply(block).flush() }.toByteArray()
}

data class Tbt(
    val connected: Boolean = true,
    val naviDisplayStatus: Int = 1,
    val naviStatus: Int = 1,
    val speed: Int = 0,
    val totalDistance: Int = 0,
    val totalTime: Long = 0,
    val turnDistance: Int = 0,
    val turnCode: Int = 0, // TMAP TurnType (11 직진, 12 좌, 13 우, 14 유턴 …)
    val turnName: String = "",
    val nextTurnDistance: Int = 0,
    val nextTurnCode: Int = 0,
    val safe: Boolean = false,
    val safeCode: Int = 0,
    val safeDistance: Int = 0,
    val safeId: Int = 0,
    val safeSpeedLimit: Int = 0,
    val block: Boolean = false,
    val blockDistance: Int = 0,
    val blockSpeed: Int = 0,
    val lane: Boolean = false,
    val laneDistance: Int = 0,
    val laneTurnInfo: List<Int> = emptyList(),
    val laneEtc: List<Int> = emptyList(),
    val laneAvailable: List<Int> = emptyList(),
)

fun ByteArray.hex() = joinToString(" ") { "%02X".format(it) }
