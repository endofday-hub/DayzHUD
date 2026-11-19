package com.dayz.hud

import android.content.SharedPreferences
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// 오피넷(한국석유공사) 반경 내 주유소 가격. 좌표는 카카오 SDK와 같은 KATEC이라 변환 없이 씀
// https://www.opinet.co.kr/user/custapi/openApiInfoDtl.do?apiId=3
object Opinet {
    const val FUEL = "fuel" // prefs "hud": "" 끔, B027 휘발유, D047 경유
    val FUELS = listOf("" to "끔", "B027" to "휘발유", "D047" to "경유")

    fun fuel(p: SharedPreferences) = p.getString(FUEL, "").orEmpty()
    fun fuelName(code: String) = FUELS.firstOrNull { it.first == code }?.second ?: "끔"

    data class Station(val x: Double, val y: Double, val brand: String, val name: String, val price: Int)

    // 브랜드 코드 → 표지 글자·색 (로고 그림은 상표라 안 씀)
    fun badge(brand: String): Pair<String, Int> = when (brand) {
        "SKE" -> "SK" to 0xFFE2231A.toInt()
        "GSC" -> "GS" to 0xFF00A19B.toInt()
        "HDO" -> "HD" to 0xFF0072BC.toInt()
        "SOL" -> "S-OIL" to 0xFFFFC20E.toInt()
        "RTE", "RTX", "NHO" -> "알뜰" to 0xFF4CAF50.toInt()
        else -> "자가" to 0xFF78909C.toInt()
    }

    // 응답: {"RESULT":{"OIL":[{UNI_ID, POLL_DIV_CO|POLL_DIV_CD, OS_NM, PRICE, DISTANCE, GIS_X_COOR, GIS_Y_COOR}]}}
    fun parse(json: String): List<Station> {
        val oil = JSONObject(json).optJSONObject("RESULT")?.optJSONArray("OIL") ?: return emptyList()
        return (0 until oil.length()).mapNotNull { i ->
            val o = oil.getJSONObject(i)
            val price = o.optDouble("PRICE").takeIf { it > 0 } ?: return@mapNotNull null
            Station(
                o.optDouble("GIS_X_COOR"), o.optDouble("GIS_Y_COOR"),
                o.optString("POLL_DIV_CO").ifEmpty { o.optString("POLL_DIV_CD") },
                o.optString("OS_NM"), price.toInt(),
            )
        }
    }

    // 네트워크 → 백그라운드 스레드에서 부를 것. radius 최대 5000m
    fun around(key: String, x: Double, y: Double, fuel: String, radius: Int = 3000): List<Station> {
        // 키 이름은 code (안내 페이지엔 certkey로 적혀 있지만 그건 빈 결과만 옴 — 2026-11-20 실측)
        val q = "out=json&x=$x&y=$y&radius=$radius&sort=2&prodcd=$fuel&code=${URLEncoder.encode(key, "UTF-8")}"
        val c = URL("https://www.opinet.co.kr/api/aroundAll.do?$q").openConnection() as HttpURLConnection
        c.connectTimeout = 5_000
        c.readTimeout = 5_000
        return try {
            parse(c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
    }
}
