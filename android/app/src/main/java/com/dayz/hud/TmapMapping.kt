package com.dayz.hud

// TMAP EDC RGData(Bundle → Map) → Kivic Tbt. 키 이름은 TMAP EDC SDK 문서 기준
object TmapMapping {

    // 원본 앱의 종료 패킷: 새 패킷에 isConnected=false, naviDisplayStatus=0만 설정 → 나머지 전부 0
    val ENDED = Tbt(connected = false, naviDisplayStatus = 0, naviStatus = 0)

    fun toTbt(m: Map<String, Any?>): Tbt {
        val first = m.sub("firstTBTInfo")
        val second = m.sub("secondTBTInfo")
        val sdi = m.sub("firstSDIInfo")
        val speed = m.int("speedInKmPerHour")
        if (!m.bool("hasTbtInfo") && m.int("nGoPosDist") <= 0) return ENDED.copy(speed = speed)

        // naviDisplayStatus/naviStatus는 Tbt 기본값(1/1)
        // 차로: TMAP int[] 비트마스크(1 유턴, 2 좌, … / 0x80 권장)를 원본 Kivic 앱처럼 그대로 전달
        val laneCount = m.int("laneCount")
        return Tbt(
            speed = speed,
            totalDistance = m.int("nGoPosDist"),
            totalTime = m.int("nGoPosTime").toLong(), // TMAP은 초. Kivic 단위 미확인
            turnDistance = first.int("nTBTDist"),
            turnCode = first.int("nTBTTurnType"), // TMAP TurnType = Kivic curTbtCode 체계
            turnName = first.str("szTBTMainText").ifEmpty { first.str("szCrossName") },
            nextTurnDistance = second.int("nTBTDist"),
            nextTurnCode = second.int("nTBTTurnType"),
            safe = m.bool("showSDI") && sdi.isNotEmpty(),
            safeCode = sdi.int("nSdiType"),
            safeDistance = sdi.int("nSdiDist"),
            safeSpeedLimit = sdi.int("nSdiSpeedLimit"),
            block = sdi.bool("nSdiBlockSertion"),
            blockDistance = sdi.int("nSdiBlockDist"),
            blockSpeed = sdi.int("nSdiBlockSpeed"),
            lane = m.bool("showLane") && laneCount > 0,
            laneDistance = m.int("laneDistance"),
            laneTurnInfo = m.ints("laneTurnInfo").take(laneCount), // Kivic은 laneCount개만 읽음
            laneEtc = m.ints("laneEtcInfo"),
            laneAvailable = m.ints("laneAvailableInfo"),
        )
    }

    private fun Map<*, *>.ints(k: String): List<Int> = when (val v = this[k]) {
        is IntArray -> v.toList()
        is Collection<*> -> v.mapNotNull { (it as? Number)?.toInt() }
        else -> emptyList()
    }

    private fun Map<*, *>.sub(k: String): Map<*, *> = this[k] as? Map<*, *> ?: emptyMap<Any, Any>()
    private fun Map<*, *>.int(k: String) = (this[k] as? Number)?.toInt() ?: 0
    private fun Map<*, *>.bool(k: String) = this[k] as? Boolean ?: false
    private fun Map<*, *>.str(k: String) = this[k] as? String ?: ""
}
