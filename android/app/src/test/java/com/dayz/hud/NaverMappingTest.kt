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
}
