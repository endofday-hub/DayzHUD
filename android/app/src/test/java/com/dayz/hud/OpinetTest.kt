package com.dayz.hud

import org.junit.Assert.assertEquals
import org.junit.Test

class OpinetTest {
    @Test fun parse() {
        val json = """{"RESULT":{"OIL":[
            {"UNI_ID":"A1","POLL_DIV_CO":"SKE","OS_NM":"가","PRICE":1689,"DISTANCE":120.5,"GIS_X_COOR":314681.8,"GIS_Y_COOR":544837.0},
            {"UNI_ID":"A2","POLL_DIV_CD":"SOL","OS_NM":"나","PRICE":"1702","GIS_X_COOR":"314700","GIS_Y_COOR":"544900"},
            {"UNI_ID":"A3","POLL_DIV_CO":"GSC","OS_NM":"가격 없음","PRICE":0,"GIS_X_COOR":1,"GIS_Y_COOR":1}
        ]}}"""
        val s = Opinet.parse(json)
        assertEquals(2, s.size)
        assertEquals(Opinet.Station(314681.8, 544837.0, "SKE", "가", 1689), s[0])
        assertEquals("SOL", s[1].brand) // 필드 이름이 POLL_DIV_CD로 와도
        assertEquals(1702, s[1].price) // 숫자가 문자열로 와도
        assertEquals(0, Opinet.parse("""{"RESULT":{}}""").size)
        assertEquals("S-OIL", Opinet.badge("SOL").first)
        assertEquals("자가", Opinet.badge("ETC").first)
    }
}
