package com.dayz.hud

import androidx.car.app.navigation.model.LaneDirection
import androidx.car.app.navigation.model.Maneuver
import org.junit.Assert.assertEquals
import org.junit.Test

class CarManeuverTest {

    @Test
    fun kakaoCodesMapToAaManeuvers() {
        assertEquals(Maneuver.TYPE_TURN_NORMAL_RIGHT to null, CarManeuver.of(2))
        assertEquals(Maneuver.TYPE_KEEP_LEFT to null, CarManeuver.of(82))     // 왼쪽 차선 직진
        assertEquals(Maneuver.TYPE_TURN_SLIGHT_RIGHT to null, CarManeuver.of(19)) // 2시
        assertEquals(Maneuver.TYPE_TURN_SHARP_LEFT to null, CarManeuver.of(25))   // 8시
        assertEquals(Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE to 90, CarManeuver.of(32)) // 로터리 3시
        assertEquals(Maneuver.TYPE_DESTINATION to null, CarManeuver.of(101))
        assertEquals(Maneuver.TYPE_STRAIGHT to null, CarManeuver.of(9999))
        assertEquals(-90, CarManeuver.angle(Maneuver.TYPE_TURN_NORMAL_LEFT))
    }

    @Test
    fun laneBitsMapToAaLaneShapes() {
        // 10 = 좌(2) + 직진(8): 왼쪽부터 순서대로
        assertEquals(listOf(LaneDirection.SHAPE_NORMAL_LEFT, LaneDirection.SHAPE_STRAIGHT), CarManeuver.laneDirs(10).map { it.shape })
        assertEquals(listOf(Maneuver.TYPE_U_TURN_LEFT), CarManeuver.laneDirs(1).map { it.arrow })
        assertEquals(emptyList<Int>(), CarManeuver.laneDirs(0).map { it.bit })
    }

    @Test
    fun sdiLabels() {
        assertEquals("과속 카메라", KakaoNaviMapping.sdiLabel(1))
        assertEquals("구간단속 중", KakaoNaviMapping.sdiLabel(4))
        assertEquals("주의", KakaoNaviMapping.sdiLabel(999))
    }
}
