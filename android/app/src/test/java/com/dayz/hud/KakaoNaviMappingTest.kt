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
    fun lanesFollowTmapBits() {
        // 3차로: 좌회전+유턴 / 직진(안내, 권장) / 직진+우회전(우측 버스)
        val t = KakaoNaviMapping.toTbt(
            KakaoNaviMapping.Guide(
                rg = 0, turnDistance = 300, name = "", remainDistance = 1, remainSeconds = 1, speed = 0,
                lanes = listOf(
                    KakaoNaviMapping.Lane(turn = 3, pocket = 1),
                    KakaoNaviMapping.Lane(turn = 8, highlight = 4, suggest = 1),
                    KakaoNaviMapping.Lane(turn = 40, bus = 2),
                ),
                laneDistance = 280,
            ),
        )
        assertEquals(true, t.lane)
        assertEquals(280, t.laneDistance)
        assertEquals(listOf(3, 8, 40), t.laneTurnInfo)
        assertEquals(listOf(1, 0x80, 64), t.laneEtc)
        assertEquals(listOf(0, 8, 0), t.laneAvailable)
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

    @Test
    fun routeSummaryText() {
        assertEquals("1시간 20분 · 95.3km · 통행료 3,400원", KakaoNaviMapping.routeSummary(4_800, 95_300, 3_400))
        assertEquals("12분 · 850m · 무료", KakaoNaviMapping.routeSummary(700, 850, 0))
    }

    @Test
    fun addressDropsCodeAndShortensCity() {
        // 실기기 로그: 1150061100 | 서울특별시 | 강서구 | 발산제1동
        assertEquals("서울시 강서구 발산제1동", KakaoNaviMapping.address(listOf("1150061100", "서울특별시", "강서구", "발산제1동")))
        assertEquals("부산시 해운대구 우동", KakaoNaviMapping.address(listOf("2635010500", "부산광역시", "해운대구", "우동")))
        assertEquals("경기도 군포시 금정동", KakaoNaviMapping.address(listOf(null, "경기도", "군포시", "금정동")))
        assertEquals(null, KakaoNaviMapping.address(listOf("123", "", null, " ")))
    }

    @Test
    fun labelCyclesAddressDestNameDestAddress() {
        val items = listOf("서울시 강서구 발산제1동", "예성라온팰리스오피스텔", "서울 구로구 경인로 240")
        assertEquals(listOf(0, 1, 2, 0), (0L..3L).map { KakaoNaviMapping.labelTurn(items, it) })
        // 주소를 아직 몰라도·목적지 주소가 이름과 같아도 건너뜀
        assertEquals(listOf(1, 1), (0L..1L).map { KakaoNaviMapping.labelTurn(listOf(null, "정읍시 부전동 1019-1", "정읍시 부전동 1019-1"), it) })
        assertEquals(null, KakaoNaviMapping.labelTurn(listOf(null, "", " "), 0))
    }

    @Test
    fun laneColorOnlyNearTurn() {
        assertEquals(0xFFFF5CA8.toInt(), KakaoNaviMapping.laneColor("KNRGLaneColor_Pink", 500))
        assertEquals(0xFF2ECC71.toInt(), KakaoNaviMapping.laneColor("KNRGLaneColor_Green", 120))
        assertEquals(null, KakaoNaviMapping.laneColor("KNRGLaneColor_Pink", 4_052)) // 실차: 4km 앞부터 옴
        assertEquals(null, KakaoNaviMapping.laneColor("KNRGLaneColor_None", 100))
    }

    @Test
    fun safeDriveKeepsOnlySpeedAndCamera() {
        val t = KakaoNaviMapping.safeTbt(KakaoNaviMapping.Guide(
            rg = 0, turnDistance = 0, name = "", remainDistance = 0, remainSeconds = 0, speed = 72,
            safety = KakaoNaviMapping.Safety("KNSafetyCode_SpeedViolationCamera", 420, speedLimit = 60),
        ))
        assertEquals(0, t.turnCode)
        assertEquals(0, t.totalDistance)
        assertEquals(72, t.speed)
        assertEquals(true, t.safe)
        assertEquals(1, t.safeCode)
        assertEquals(420, t.safeDistance)
        assertEquals(60, t.safeSpeedLimit)
    }

    @Test
    fun sectionShownAllTheWay() {
        // 구간 끝 카메라가 1km보다 멀어도 (안전 정보는 범위 밖) 구간 내내 단속중·평균 속도
        val far = KakaoNaviMapping.Safety("KNSafetyCode_SpeedViolationSectionOutCamera", 5_200, speedLimit = 100)
        val t = KakaoNaviMapping.toTbt(KakaoNaviMapping.Guide(
            rg = 0, turnDistance = 1, name = "", remainDistance = 1, remainSeconds = 1, speed = 95,
            safety = far, section = KakaoNaviMapping.Section(avgSpeed = 97, remain = 5_200, speedLimit = 100),
        ))
        assertEquals(true, t.safe)
        assertEquals(4, t.safeCode)
        assertEquals(100, t.safeSpeedLimit)
        assertEquals(true, t.block)
        assertEquals(97, t.blockSpeed)
        assertEquals(5_200, t.blockDistance)
        // 끝 카메라가 가까우면 그 카메라를 보이고 단속중은 그대로
        val near = KakaoNaviMapping.toTbt(KakaoNaviMapping.Guide(
            rg = 0, turnDistance = 1, name = "", remainDistance = 1, remainSeconds = 1, speed = 95,
            safety = KakaoNaviMapping.Safety("KNSafetyCode_SpeedViolationSectionOutCamera", 600, speedLimit = 100),
            section = KakaoNaviMapping.Section(avgSpeed = 97, remain = 600, speedLimit = 100),
        ))
        assertEquals(3, near.safeCode)
        assertEquals(true, near.block)
        assertEquals(97, near.blockSpeed)
    }
}
