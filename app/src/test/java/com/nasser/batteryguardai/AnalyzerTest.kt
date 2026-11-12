package com.nasser.batteryguardai

import com.nasser.batteryguardai.data.BatterySample
import com.nasser.batteryguardai.engine.Analyzer
import com.nasser.batteryguardai.engine.HOUR
import org.junit.Assert.*
import org.junit.Test

class AnalyzerTest {
    private fun s(t: Long, l: Int, ch: Boolean = false) =
        BatterySample(time = t, level = l, charging = ch, tempC = 30f, voltageMv = 4000, currentUa = 0, chargeCounterUah = 0, screenOn = false)

    @Test fun dischargeRateIsPercentPerHour() {
        val l = (0..4).map { s(it * HOUR, 100 - it * 5) }
        val r = Analyzer.dischargeRate(l)!!
        assertEquals(5.0, r.perHour, 0.01)
    }

    @Test fun insufficientDataReturnsNull() {
        assertNull(Analyzer.dischargeRate(listOf(s(0, 90), s(10 * 60_000L, 89))))
    }

    @Test fun chargeRateIgnoresDischargePairs() {
        val l = listOf(s(0, 50, true), s(HOUR, 70, true), s(2 * HOUR, 60))
        assertEquals(20.0, Analyzer.chargeRate(l)!!.perHour, 0.01)
    }

    @Test fun remainingTime() {
        assertEquals(10.0, Analyzer.remainingHours(50, Analyzer.Rate(5.0, 80))!!, 0.01)
    }
}
