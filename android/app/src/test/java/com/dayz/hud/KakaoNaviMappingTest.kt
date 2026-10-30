package com.dayz.hud

import org.junit.Assert.assertEquals
import org.junit.Test

class KakaoNaviMappingTest {

    @Test
    fun turnCodesFollowTmap() {
        assertEquals(12, KakaoNaviMapping.turnCode(1))   // 좌회전
        assertEquals(13, KakaoNaviMapping.turnCode(2))   // 우회전
        assertEquals(32, KakaoNaviMapping.turnCode(19))  // 2시 방향
        assertEquals(142, KakaoNaviMapping.turnCode(41)) // 로터리 12시
        assertEquals(131, KakaoNaviMapping.turnCode(70)) // 회전교차로 1시
        assertEquals(104, KakaoNaviMapping.turnCode(9))  // 오른쪽 고속도로 출구
        assertEquals(201, KakaoNaviMapping.turnCode(101))
        assertEquals(11, KakaoNaviMapping.turnCode(9999)) // 모르는 코드 → 직진
    }

    @Test
    fun guideMapsToTbt() {
        // 실기기 모의주행 로그: 우회전 122m [종로], 속도 60, 남은 3683m
        val t = KakaoNaviMapping.toTbt(
            KakaoNaviMapping.Guide(
                rg = 2, turnDistance = 122, name = "종로", nextRg = 2, nextDistance = 530,
                remainDistance = 3683, remainSeconds = 660, speed = 60,
                safety = KakaoNaviMapping.Safety("KNSafetyCode_CutInViolationCamera", 300),
            ),
        )
        assertEquals(13, t.turnCode)
        assertEquals(122, t.turnDistance)
        assertEquals("종로", t.turnName)
        assertEquals(13, t.nextTurnCode)
        assertEquals(530, t.nextTurnDistance)
        assertEquals(3683, t.totalDistance)
        assertEquals(660L, t.totalTime)
        assertEquals(60, t.speed)
        assertEquals(true, t.safe)
        assertEquals(12, t.safeCode) // 끼어들기
        assertEquals(300, t.safeDistance)
    }

    @Test
    fun safetyOnlyNearAndKnown() {
        fun safe(s: KakaoNaviMapping.Safety) = KakaoNaviMapping.toTbt(
            KakaoNaviMapping.Guide(rg = 0, turnDistance = 1, name = "", remainDistance = 1, remainSeconds = 1, speed = 0, safety = s),
        )
        assertEquals(false, safe(KakaoNaviMapping.Safety("KNSafetyCode_SpeedViolationCamera", 1_500)).safe)
        assertEquals(false, safe(KakaoNaviMapping.Safety("KNSafetyCode_FogArea", 100)).safe)
        val section = safe(KakaoNaviMapping.Safety("KNSafetyCode_SpeedViolationSection", 800, speedLimit = 100, sectionSpeed = 92))
        assertEquals(4, section.safeCode)
        assertEquals(100, section.safeSpeedLimit)
        assertEquals(true, section.block)
        assertEquals(92, section.blockSpeed)
    }
}
