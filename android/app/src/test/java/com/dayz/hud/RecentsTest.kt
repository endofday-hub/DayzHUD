package com.dayz.hud

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentsTest {

    @Test
    fun roundTripAndMoveToTop() {
        val a = Place("서울역", 309252, 550771, "서울 중구 한강대로 405")
        val b = Place("허준\t근린공원", 297000, 551000)
        var list = Recents.add(Recents.add(emptyList(), a), b)
        list = Recents.add(list, a) // 다시 가면 맨 위로, 중복 없음
        val back = Recents.decode(Recents.encode(list))
        assertEquals(listOf("서울역", "허준 근린공원"), back.map { it.name })
        assertEquals(a, back[0])
        assertEquals(emptyList<Place>(), Recents.decode(null))
    }

    @Test
    fun favoritesPinOnTop() {
        val a = Place("집", 1, 1)
        val b = Place("회사", 2, 2)
        val c = Place("서울역", 3, 3)
        var fav = Favorites.toggle(emptyList(), b)
        fav = Favorites.toggle(fav, a)
        assertEquals(listOf("회사", "집"), fav.map { it.name }) // 고정한 순서대로
        assertEquals(listOf("회사", "집", "서울역"), Favorites.withRecents(fav, listOf(c, a)).map { it.name }) // 겹치면 한 번만
        assertEquals(listOf("집"), Favorites.toggle(fav, b.copy(name = "회사(다른 이름)")).map { it.name }) // 같은 좌표면 해제
    }

    @Test
    fun keepsAtMostEight() {
        val list = (1..10).fold(emptyList<Place>()) { l, i -> Recents.add(l, Place("p$i", i, i)) }
        assertEquals(8, list.size)
        assertEquals("p10", list.first().name)
    }
}
