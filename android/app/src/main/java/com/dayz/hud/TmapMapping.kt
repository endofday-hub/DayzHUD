package com.dayz.hud

// TMAP EDC RGData(Bundle → Map) → Kivic Tbt. 키 이름은 TMAP EDC SDK 문서 기준
object TmapMapping {

    val ENDED = Tbt(connected = false, naviDisplayStatus = 0)

    fun toTbt(m: Map<String, Any?>): Tbt {
        val first = m.sub("firstTBTInfo")
        val second = m.sub("secondTBTInfo")
        val sdi = m.sub("firstSDIInfo")
        val speed = m.int("speedInKmPerHour")
        if (!m.bool("hasTbtInfo") && m.int("nGoPosDist") <= 0) return ENDED.copy(speed = speed)

        // naviDisplayStatus/naviStatus는 Tbt 기본값(1/1) — 키빅 실기기 테스트 결과로 확정할 것
        // ponytail: 차로 안내 미전달, TMAP laneBubbleList ↔ Kivic lane 형식 대응 확인 후 추가
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
        )
    }

    private fun Map<*, *>.sub(k: String): Map<*, *> = this[k] as? Map<*, *> ?: emptyMap<Any, Any>()
    private fun Map<*, *>.int(k: String) = (this[k] as? Number)?.toInt() ?: 0
    private fun Map<*, *>.bool(k: String) = this[k] as? Boolean ?: false
    private fun Map<*, *>.str(k: String) = this[k] as? String ?: ""
}
