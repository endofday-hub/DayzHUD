package com.dayz.hud

import org.junit.Assert.assertEquals
import org.junit.Test

class NaverMappingTest {

    @Test
    fun distanceFormats() {
        assertEquals(134, NaverMapping.parseDistance("134m"))
        assertEquals(1200, NaverMapping.parseDistance("1.2km"))
        assertEquals(1200, NaverMapping.parseDistance("1,200m"))
        assertEquals(0, NaverMapping.parseDistance("도착"))
    }

    @Test
    fun aaNotificationMapsToTbt() {
        // 실기기 샘플: content_title="134m", content_text="강서농수산물도매시장", 우회전 아이콘
        val t = NaverMapping.toTbt("134m", "강서농수산물도매시장", "6412f0b8158d")
        assertEquals(13, t.turnCode)
        assertEquals(134, t.turnDistance)
        assertEquals("강서농수산물도매시장", t.turnName)
        assertEquals(0, NaverMapping.toTbt("50m", "", "unknown").turnCode)
    }

    @Test
    fun speedSignIsCameraNotTurn() {
        // 실차 2026-11-20: 제한속도 50 표지가 "부천, 발산역" 고가 옆길 안내와 번갈아 옴
        assertEquals(null, NaverMapping.turnCode("6752f66291ee"))
        assertEquals(50, NaverMapping.speedLimit("6752f66291ee"))
        val turn = NaverMapping.toTbt("4.2km", "부천, 발산역", "d39f02eb6b52")
        assertEquals(124, turn.turnCode)
        val t = NaverMapping.withCamera(turn, 50, NaverMapping.parseDistance("589m"))
        assertEquals(124, t.turnCode) // 회전은 그대로
        assertEquals(4200, t.turnDistance)
        assertEquals(true, t.safe)
        assertEquals(50, t.safeSpeedLimit)
        assertEquals(589, t.safeDistance)
    }
}
