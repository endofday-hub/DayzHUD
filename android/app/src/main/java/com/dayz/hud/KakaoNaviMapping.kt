package com.dayz.hud

// 카카오내비 SDK 안내 값 → Kivic Tbt. Kivic은 TMAP TurnType/SDI 코드로 그리므로 카카오 코드를 TMAP 코드로 바꾼다.
// SDK 객체는 KakaoNaviActivity에서 숫자·이름으로 풀어 넘긴다 (여기는 JVM 단위 테스트 가능하게 순수 코드)
object KakaoNaviMapping {

    // KNRGCode 값 → TMAP TurnType (androidDoc TurnType 표). 표에 없으면 직진
    private val TURN = buildMap {
        put(0, 11); put(1, 12); put(2, 13); put(3, 14)               // 직진, 좌, 우, 유턴
        put(5, 118); put(6, 117)                                      // 왼쪽 / 오른쪽 도로
        put(7, 106); put(8, 105); put(9, 104)                         // 고속도로 출구 직진·왼쪽·오른쪽
        put(10, 103); put(11, 102); put(12, 101)                      // 고속도로 입구
        put(14, 120); put(15, 119); put(16, 124); put(17, 123)       // 고가 / 지하차도 진입, 옆길
        for (h in 1..12) put(17 + h, 30 + h)                         // n시 방향 (18~29 → 31~42)
        for (h in 1..12) put(29 + h, 130 + h)                        // 로터리 n시 (30~41 → 131~142)
        for (h in 1..12) put(69 + h, 130 + h)                        // 회전교차로 n시 (70~81 → 로터리 표시)
        put(42, 116); put(43, 115); put(44, 114)                      // 도시고속도로 출구
        put(45, 113); put(46, 112); put(47, 111)                      // 도시고속도로 입구
        put(48, 118); put(49, 117)                                    // 고속도로 분기 왼쪽 / 오른쪽
        put(82, 52); put(83, 53)                                      // 왼쪽 / 오른쪽 차선 직진
        put(84, 249); put(85, 249)                                    // 톨게이트, 하이패스
        put(100, 200); put(101, 201); put(1000, 185)                  // 출발, 도착, 경유지
    }

    fun turnCode(rg: Int): Int = TURN[rg] ?: 11

    // KNSafetyCode 이름(접두어 뺀 것) → TMAP nSdiType. 카메라·주요 주의 지점만, 나머지는 HUD에 안 띄움
    private val SDI = mapOf(
        "SignalAndSpeedViolationCamera" to 0, "SignalAndSpeedViolationBackwardCamera" to 0,
        "SpeedViolationCamera" to 1, "SpeedViolationBackwardCamera" to 1,
        "SpeedViolationSectionInCamera" to 2, "SpeedViolationSectionInOnlyCamera" to 2,
        "SpeedViolationSectionInBackwardCamera" to 2, "SpeedViolationSectionInOnlyBackwardCamera" to 2,
        "SpeedViolationSectionOutCamera" to 3, "SpeedViolationSectionOutOnlyCamera" to 3,
        "SpeedViolationSectionOutBackwardCamera" to 3, "SpeedViolationSectionOutOnlyBackwardCamera" to 3,
        "SpeedViolationSection" to 4, "SpeedViolationSectionHalf" to 4,
        "SignalViolationCamera" to 6,
        "MovableSpeedViolationCamera" to 7,
        "BoxedSpeedViolationCamera" to 8,
        "BuslaneViolationCamera" to 9, "BuslaneAndSpeedViolationCamera" to 9,
        "ShoulderLaneViolationCamera" to 11,
        "CutInViolationCamera" to 12,
        "TrafficCollectionCamera" to 13,
        "OverloadViolationCamera" to 15,
        "CargoViolationCamera" to 16,
        "ParkingViolationCamera" to 17,
        "RailroadCrossing" to 19,
        "ChildrenProtectionZone" to 20,
        "Hump" to 22,
        "OldDieselCamera" to 64,
        "LandChangeViolationSectionInCamera" to 65, "LandChangeViolationSectionOutCamera" to 65,
    )

    fun sdiCode(name: String): Int? = SDI[name.removePrefix("KNSafetyCode_")]

    // TMAP nSdiType → 차량 화면에 쓸 이름 (위 SDI 표에 나오는 코드만)
    private val SDI_LABEL = mapOf(
        0 to "신호·과속 카메라", 1 to "과속 카메라", 2 to "구간단속 시작", 3 to "구간단속 끝", 4 to "구간단속 중",
        6 to "신호위반 카메라", 7 to "이동식 카메라", 8 to "박스형 카메라", 9 to "버스전용차로 카메라",
        11 to "갓길 단속", 12 to "끼어들기 단속", 13 to "교통정보 수집", 15 to "과적 단속", 16 to "적재불량 단속",
        17 to "주정차 단속", 19 to "철길 건널목", 20 to "어린이 보호구역", 22 to "과속방지턱",
        64 to "노후경유차 단속", 65 to "차로변경 단속",
    )

    fun sdiLabel(code: Int) = SDI_LABEL[code] ?: "주의"

    // 이 거리 안의 안전 정보만 HUD에 (TMAP showSDI와 비슷하게)
    private const val SDI_RANGE = 1_000

    // code = KNSafetyCode 이름, sectionSpeed = 구간단속 평균속도 (구간 중일 때)
    class Safety(val code: String, val distance: Int, val speedLimit: Int = 0, val sectionSpeed: Int = 0)

    // KNLane_LaneInfo 그대로 (왼쪽 차로부터). turn 비트는 TMAP laneTurnInfo와 같다: 1 유턴, 2 좌, 4 좌측, 8 직진, 16 우측, 32 우
    class Lane(
        val turn: Int,
        val pocket: Int = 0,    // 1 좌포켓, 2 우포켓
        val bus: Int = 0,       // 0 아님, 1 중앙, 2 우측 버스전용
        val facility: Int = 0,  // 1 고가, 2 지하
        val highlight: Int = 0, // 안내 차로면 0 아님
        val suggest: Int = 0,   // 1 권장 차로
    )

    class Guide(
        val rg: Int,
        val turnDistance: Int,
        val name: String,
        val nextRg: Int? = null,
        val nextDistance: Int = 0,
        val remainDistance: Int,
        val remainSeconds: Int,
        val speed: Int,
        val safety: Safety? = null,
        val lanes: List<Lane> = emptyList(),
        val laneDistance: Int = 0,
    )

    // TMAP laneEtcInfo: 1 좌포켓, 2 우포켓, 4 고가, 8 지하, 64 버스, 0x80 권장
    private fun etc(l: Lane) = (if (l.pocket in 1..2) l.pocket else 0) or
        (if (l.facility == 1) 4 else 0) or (if (l.facility == 2) 8 else 0) or
        (if (l.bus != 0) 64 else 0) or (if (l.suggest == 1) 0x80 else 0)

    fun toTbt(g: Guide): Tbt {
        val s = g.safety?.takeIf { it.distance in 0..SDI_RANGE }
        val sdi = s?.let { sdiCode(it.code) }
        val inSection = sdi == 4
        return Tbt(
            speed = g.speed,
            totalDistance = g.remainDistance,
            totalTime = g.remainSeconds.toLong(), // TMAP과 같은 초 단위
            turnDistance = g.turnDistance,
            turnCode = turnCode(g.rg),
            turnName = g.name,
            nextTurnDistance = if (g.nextRg != null) g.nextDistance else 0,
            nextTurnCode = g.nextRg?.let(::turnCode) ?: 0,
            safe = sdi != null,
            safeCode = sdi ?: 0,
            safeDistance = if (sdi != null) s.distance else 0,
            safeSpeedLimit = if (sdi != null) s.speedLimit else 0,
            block = inSection,
            blockDistance = if (inSection) s!!.distance else 0,
            blockSpeed = if (inSection) s!!.sectionSpeed else 0,
            lane = g.lanes.isNotEmpty(),
            laneDistance = g.laneDistance,
            laneTurnInfo = g.lanes.map { it.turn },
            laneEtc = g.lanes.map(::etc),
            // TMAP처럼 안내 차로는 그 차로의 턴 정보, 아니면 0
            laneAvailable = g.lanes.map { if (it.highlight != 0) it.turn else 0 },
        )
    }
}
