package com.dayz.hud

import android.content.SharedPreferences

// 카카오 길안내 목적지 (KATEC 좌표 — KNSDK가 쓰는 좌표계)
data class Place(val name: String, val x: Int, val y: Int, val address: String = "") {
    fun same(o: Place) = x == o.x && y == o.y
}

// 최근 목적지: prefs 문자열 하나에 "이름\tx\ty\t주소" 줄로, 최신이 위
object Recents {
    const val KEY = "kakaoRecent"
    private const val MAX = 8

    fun decode(s: String?): List<Place> = s.orEmpty().lines().mapNotNull { line ->
        val f = line.split('\t')
        val x = f.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
        val y = f.getOrNull(2)?.toIntOrNull() ?: return@mapNotNull null
        Place(f[0], x, y, f.getOrElse(3) { "" })
    }

    fun encode(list: List<Place>) = list.joinToString("\n") {
        listOf(it.name, it.x, it.y, it.address).joinToString("\t") { v -> v.toString().replace('\t', ' ').replace('\n', ' ') }
    }

    // 같은 곳은 맨 위로 올리고 MAX개까지
    fun add(list: List<Place>, p: Place) = (listOf(p) + list.filterNot(p::same)).take(MAX)

    // 폰 검색 화면·차량 목적지 화면이 같이 쓰는 prefs("hud")
    fun load(prefs: SharedPreferences) = decode(prefs.getString(KEY, null))
    fun remember(prefs: SharedPreferences, p: Place) = prefs.edit().putString(KEY, encode(add(load(prefs), p))).apply()
}

// 즐겨찾기: 최근 목적지에서 핀으로 고정한 곳. 개수 제한·밀려남 없음, 고정한 순서대로. 목록에선 최근보다 위
object Favorites {
    private const val KEY = "kakaoFav"

    fun toggle(list: List<Place>, p: Place) = if (list.any(p::same)) list.filterNot(p::same) else list + p

    // 즐겨찾기 먼저, 그다음 최근 (겹치면 즐겨찾기에만)
    fun withRecents(fav: List<Place>, recent: List<Place>) = fav + recent.filterNot { r -> fav.any(r::same) }

    fun load(prefs: SharedPreferences) = Recents.decode(prefs.getString(KEY, null))
    fun toggle(prefs: SharedPreferences, p: Place) = prefs.edit().putString(KEY, Recents.encode(toggle(load(prefs), p))).apply()
}
