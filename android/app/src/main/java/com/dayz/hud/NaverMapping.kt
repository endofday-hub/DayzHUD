package com.dayz.hud

import kotlin.math.roundToInt

// 네이버지도: 폰 단독일 땐 알림에 회전 정보가 없고, AA 연결 중에만 알림 extras의 CarAppExtender에 실린다 (PLAN.md Phase 2).
// 회전 방향은 아이콘 그림으로만 오므로 아이콘 해시(iconHash) → TMAP TurnType 표로 바꾼다.
object NaverMapping {
    const val PKG = "com.nhn.android.nmap"
    const val CAR_EXTENSIONS = "androidx.car.app.EXTENSIONS"

    // 2026-10-30 DHU + Lockito 모의주행으로 수집 (네이버지도 6.11.2.2). 새 해시는 HUD 로그에 뜨면 여기 추가
    private val TURN = mapOf(
        "6412f0b8158d" to 13,  // 우회전
        "c24ab34163f5" to 12,  // 좌회전
        "983eb17a52f4" to 14,  // 유턴
        "e7772f0f7e8f" to 18,  // 2시 방향
        "93c1500859ed" to 118, // 왼쪽 주행 → 왼쪽 도로 (Kivic 순회에서 표시 확인된 코드)
        "cc2fb1699896" to 201, // 도착지 주변
        "153699ab97a7" to 11,  // 관문(IC·지하차도 입구 모양) 아래 직진 화살표 → 직진 (실차 2026-11-05, "시흥IC, 오류IC" 방면)
        // 실차 2026-11-20 (출근길)
        "d39f02eb6b52" to 124, // 고가도로 왼쪽에 두고 오른쪽 옆길로 → 고가도로 옆길 ("부천, 발산역" 방면)
        "921607df18db" to 11,  // 터널 입구 아래 직진 화살표 → 직진 (관문 직진과 같게)
        "bbb23dab124e" to 117, // 오른쪽으로 비켜 가는 화살표 → 오른쪽 도로 (왼쪽 도로 118의 짝)
    )

    // 회전이 아니라 제한속도 표지 (과속 카메라 알림). 회전 안내와 몇 초씩 번갈아 옴. 다른 숫자는 새 아이콘으로 로그에 뜸
    private val SPEED = mapOf(
        "6752f66291ee" to 50,
    )

    fun turnCode(iconHash: String?): Int? = TURN[iconHash]
    fun speedLimit(iconHash: String?): Int? = SPEED[iconHash]

    // 마지막 회전 안내에 카메라 정보를 얹음 (TMAP nSdiType 1 = 과속 카메라)
    fun withCamera(turn: Tbt, limit: Int, distance: Int) =
        turn.copy(safe = true, safeCode = 1, safeSpeedLimit = limit, safeDistance = distance)

    // 남은 거리·시간·속도는 알림에 없음 → 0 (HUD는 회전·거리·명칭만 표시)
    fun toTbt(distance: String, name: String, iconHash: String?) = Tbt(
        turnDistance = parseDistance(distance),
        turnCode = turnCode(iconHash) ?: 0,
        turnName = name,
    )

    // "134m", "1.2km", "1,200m" → 미터
    fun parseDistance(s: String): Int {
        val m = Regex("""([\d.,]+)\s*(km|m)""").find(s) ?: return 0
        val v = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return 0
        return (if (m.groupValues[2] == "km") v * 1000 else v).roundToInt()
    }
}
