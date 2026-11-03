package com.dayz.hud

import org.junit.Assert.assertEquals
import org.junit.Test

class CarPathTest {

    // ㄱ자 길: (0,0) → (10,0) → (10,10), 전체 20
    private val l = listOf(0f to 0f, 10f to 0f, 10f to 10f)

    @Test
    fun walksAlongCorners() {
        assertEquals((5f to 0f) to 0, CarPath.along(l, 0.25f))
        assertEquals((10f to 5f) to 1, CarPath.along(l, 0.75f)) // 모서리를 돌아서 (대각선으로 질러가지 않음)
        assertEquals((10f to 10f) to 1, CarPath.along(l, 1f))
        assertEquals((0f to 0f) to 0, CarPath.along(l, 0f))
    }

    @Test
    fun anglesTakeShortWay() {
        assertEquals(0f, CarPath.lerpAngle(350f, 10f, 0.5f), 0.01f)
        assertEquals(45f, CarPath.lerpAngle(0f, 90f, 0.5f), 0.01f)
        assertEquals(-20f, CarPath.turn(10f, 350f), 0.01f) // 지도 회전 기준: 작은 쪽으로 잼
        assertEquals(170f, CarPath.turn(0f, 170f), 0.01f)
    }

    @Test
    fun headingIsCompass() {
        assertEquals(90f, CarPath.heading(l, 0)!!, 0.01f) // x 동·y 북: (0,0)→(10,0)은 동쪽
        assertEquals(0f, CarPath.heading(l, 1)!!, 0.01f) // (10,0)→(10,10)은 북쪽
        assertEquals(0f, CarPath.heading(l + (10f to 10f), 2)!!, 0.01f) // 길이 0 마디면 뒤쪽 마디로
    }
}
