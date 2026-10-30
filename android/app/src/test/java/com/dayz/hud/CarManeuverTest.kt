package com.dayz.hud

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
}
