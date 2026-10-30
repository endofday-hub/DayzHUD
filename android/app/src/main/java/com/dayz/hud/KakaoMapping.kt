package com.dayz.hud

// 카카오내비: 알림엔 회전 정보가 없고, AA 안내는 차량으로만 간다 (PLAN.md Phase 2).
// 대신 폰 단독 + "HUD 모드" 화면을 접근성으로 읽는다 (KakaoHudReader). 숫자는 글자로, 회전 방향은 화살표 그림 모양으로.
// 화면 노드 순서 (2026-10-30, 카카오내비 4.52.0):
//   도착시각, 남은거리, [회전 화살표], 회전거리, [방면 그림], [다음 회전 화살표], 다음 회전거리, 제한속도, 카메라거리, 속도, "km/h"
// 남은 시간은 화면에 없고 숨은 노드 설명에만: "오후 02:02 2.8km 12분 남음"
object KakaoMapping {
    const val PKG = "com.locnall.KimGiSa"

    // 접근성 노드 한 개. box = 화면 좌표 (l, t, r, b)
    class Node(val text: String, val desc: String = "", val box: List<Int> = emptyList())

    // icon = 화살표 그림 노드 (스크린샷에서 잘라 turnCode로 판별)
    class Screen(val tbt: Tbt, val icon: Node?, val nextIcon: Node?)

    private val DIST = Regex("""[\d.,]+\s*(m|km)""")
    private val LIMIT = Regex("""\d{2,3}""")
    private val REMAIN = Regex("""(?:(\d+)시간)?\s*(?:(\d+)분)?\s*남음""")

    // HUD 모드 안내 화면이 아니면 null
    fun read(nodes: List<Node>): Screen? {
        val texts = nodes.indices.filter { nodes[it].text.isNotEmpty() }
        val kmh = texts.firstOrNull { nodes[it].text == "km/h" }
        val speedAt = kmh?.let { texts.getOrNull(texts.indexOf(it) - 1) }
        val body = texts.filter { kmh == null || it < (speedAt ?: kmh) }
        val limitAt = body.firstOrNull { LIMIT.matches(nodes[it].text) }
        // [남은거리, 회전거리, (다음 회전거리)]
        val dists = body.filter { (limitAt == null || it < limitAt) && DIST.matches(nodes[it].text) }
        if (dists.size < 2) return null
        val remain = nodes.firstNotNullOfOrNull { REMAIN.find(it.desc)?.takeIf { m -> m.value != "남음" } }
        val hours = remain?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val minutes = remain?.groupValues?.get(2)?.toIntOrNull() ?: 0
        val cameraAt = limitAt?.let { body.getOrNull(body.indexOf(it) + 1) }
        val tbt = Tbt(
            totalDistance = NaverMapping.parseDistance(nodes[dists[0]].text),
            totalTime = (hours * 3600L + minutes * 60L), // TMAP과 같은 초 단위
            turnDistance = NaverMapping.parseDistance(nodes[dists[1]].text),
            nextTurnDistance = dists.getOrNull(2)?.let { NaverMapping.parseDistance(nodes[it].text) } ?: 0,
            speed = speedAt?.let { nodes[it].text.toIntOrNull() } ?: 0,
            safe = limitAt != null,
            // ponytail: 카메라 종류("신호,속도" 등)는 그림이라 못 읽음 → 고정식 과속(1)으로 통일
            safeCode = 1,
            safeSpeedLimit = limitAt?.let { nodes[it].text.toInt() } ?: 0,
            safeDistance = cameraAt?.let { NaverMapping.parseDistance(nodes[it].text) } ?: 0,
        )
        return Screen(tbt, iconBefore(nodes, dists[1]), dists.getOrNull(2)?.let { iconBefore(nodes, it) })
    }

    // 거리 글자 바로 앞의 글자 없는 그림 노드 (회전거리 앞엔 회전 화살표, 다음 회전거리 앞엔 다음 화살표)
    private fun iconBefore(nodes: List<Node>, at: Int): Node? {
        val prev = nodes.getOrNull(at - 1) ?: return null
        val b = prev.box
        return prev.takeIf { it.text.isEmpty() && b.size == 4 && b[2] > b[0] && b[3] > b[1] }
    }

    // 2026-10-30 카카오내비 4.52.0 HUD 모드에서 수집. 새 모양은 HUD 로그에 뜨면 여기 추가
    private val TURN = mapOf(
        "00000030003807fe1fff3ffe3838702070007000700070007000700070000000" to 13, // 우회전
        "000004001c003fc0fff07ff81c1c040e000e000e000e000e000e000e000e0000" to 12, // 좌회전
    )

    // 크기·색이 달라도 같은 모양이면 같은 값 (큰 청록 화살표 = 작은 회색 다음 화살표). 칸 경계 차이는 거리로 흡수
    fun turnCode(sig: String): Int? {
        val best = TURN.minByOrNull { distance(it.key, sig) } ?: return null
        return best.value.takeIf { distance(best.key, sig) <= 24 }
    }

    fun distance(a: String, b: String) =
        a.indices.sumOf { Integer.bitCount(Character.digit(a[it], 16) xor Character.digit(b.getOrElse(it) { '0' }, 16)) }

    // 화살표 그림 → 16×16칸 평균 밝기 > 60 이면 1 → 64자리 hex. 배경은 검정
    fun signature(px: IntArray, w: Int, h: Int): String {
        val out = StringBuilder()
        var acc = 0
        for (gy in 0 until 16) for (gx in 0 until 16) {
            var sum = 0L
            var n = 0
            for (y in gy * h / 16 until (gy + 1) * h / 16) for (x in gx * w / 16 until (gx + 1) * w / 16) {
                val c = px[y * w + x]
                sum += ((c shr 16 and 255) + (c shr 8 and 255) + (c and 255)) / 3
                n++
            }
            acc = acc shl 1 or if (n > 0 && sum / n > 60) 1 else 0
            if (gx % 4 == 3) { out.append("0123456789abcdef"[acc]); acc = 0 }
        }
        return out.toString()
    }
}
