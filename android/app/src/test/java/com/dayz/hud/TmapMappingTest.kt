package com.dayz.hud

import org.junit.Assert.assertEquals
import org.junit.Test

class TmapMappingTest {

    @Test
    fun routingDataMapsToTbt() {
        val rg = mapOf(
            "speedInKmPerHour" to 62,
            "hasTbtInfo" to true,
            "nGoPosDist" to 12_400,
            "nGoPosTime" to 1_080,
            "firstTBTInfo" to hashMapOf<String, Any>("nTBTDist" to 300, "nTBTTurnType" to 12, "szTBTMainText" to "강남역"),
            "secondTBTInfo" to hashMapOf<String, Any>("nTBTDist" to 900, "nTBTTurnType" to 13),
            "showSDI" to true,
            "firstSDIInfo" to hashMapOf<String, Any>("nSdiType" to 1, "nSdiDist" to 450, "nSdiSpeedLimit" to 60),
        )
        val t = TmapMapping.toTbt(rg)
        assertEquals(true, t.connected)
        assertEquals(62, t.speed)
        assertEquals(12_400, t.totalDistance)
        assertEquals(1_080L, t.totalTime)
        assertEquals(12, t.turnCode)
        assertEquals(300, t.turnDistance)
        assertEquals("강남역", t.turnName)
        assertEquals(13, t.nextTurnCode)
        assertEquals(true, t.safe)
        assertEquals(450, t.safeDistance)
        assertEquals(60, t.safeSpeedLimit)
    }

    @Test
    fun noRouteMeansEnded() {
        val t = TmapMapping.toTbt(mapOf("speedInKmPerHour" to 40, "hasTbtInfo" to false))
        assertEquals(TmapMapping.ENDED.copy(speed = 40), t)
    }

    @Test
    fun crossNameFallback() {
        val t = TmapMapping.toTbt(mapOf("hasTbtInfo" to true, "firstTBTInfo" to mapOf("szCrossName" to "역삼")))
        assertEquals("역삼", t.turnName)
    }
}
