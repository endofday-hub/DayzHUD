package com.dayz.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KakaoMappingTest {

    private fun n(text: String, vararg box: Int, desc: String = "") = KakaoMapping.Node(text, desc, box.toList())

    // 실기기 HUD 모드 화면 (uiautomator dump, 2026-10-30): 우회전 38m, 다음 좌회전 243m, 제한 50 / 280m, 0km/h
    private val sample = listOf(
        n("", 0, 0, 1080, 2198),
        n("오후 02:02", 63, 63, 336, 132),
        n("2.8km", 848, 63, 1017, 132),
        n("", 330, 326, 750, 746),    // 회전 화살표
        n("38m", 416, 778, 664, 916),
        n("", 364, 922, 716, 1048),   // "공항대로 방면" 그림
        n("", 435, 1116, 645, 1326),  // 다음 회전 화살표
        n("243m", 453, 1358, 627, 1433),
        n("", 63, 1885, 289, 2132),
        n("50", 124, 1980, 228, 2080),
        n("", 69, 2085, 284, 2164),
        n("280m", 104, 2093, 249, 2156),
        n("0", 718, 2036, 815, 2198),
        n("km/h", 815, 2096, 1017, 2196),
        n("", 0, 0, 0, 0, desc = "오후 02:02 2.8km 12분 남음"),
    )

    @Test
    fun hudScreenMapsToTbt() {
        val s = KakaoMapping.read(sample)!!
        assertEquals(2800, s.tbt.totalDistance)
        assertEquals(720L, s.tbt.totalTime)
        assertEquals(38, s.tbt.turnDistance)
        assertEquals(243, s.tbt.nextTurnDistance)
        assertEquals(0, s.tbt.speed)
        assertEquals(true, s.tbt.safe)
        assertEquals(50, s.tbt.safeSpeedLimit)
        assertEquals(280, s.tbt.safeDistance)
        assertEquals(listOf(330, 326, 750, 746), s.icon!!.box)
        assertEquals(listOf(435, 1116, 645, 1326), s.nextIcon!!.box)
    }

    @Test
    fun otherScreensAreIgnored() {
        assertNull(KakaoMapping.read(listOf(n("어디로 안내할까요?"), n("2.8km"))))
    }

    @Test
    fun arrowShapeIgnoresSizeAndColor() {
        // 같은 우회전 모양을 작은 크기·회색으로 그리면 같은 코드
        val right = "00000030003807fe1fff3ffe3838702070007000700070007000700070000000"
        val bits = right.flatMap { c -> (3 downTo 0).map { Character.digit(c, 16) shr it and 1 } }
        val w = 64
        val px = IntArray(w * w) { i -> if (bits[(i / w) / 4 * 16 + (i % w) / 4] == 1) 0xFF969696.toInt() else 0xFF000000.toInt() }
        assertEquals(right, KakaoMapping.signature(px, w, w))
        assertEquals(13, KakaoMapping.turnCode(right))
        assertNull(KakaoMapping.turnCode("f".repeat(64)))
    }
}
