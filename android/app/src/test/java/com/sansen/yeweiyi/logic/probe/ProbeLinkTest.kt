package com.sansen.yeweiyi.logic.probe

import org.junit.Assert.*
import org.junit.Test

class ProbeLinkTest {
    /** 按轮询顺序喂成功/失败，返回每次之后遥测会报的 probeConnected（与 SerialPortManager 的计数规则一致）。 */
    private fun replay(probeType: Int, polls: List<Boolean>): List<Boolean> {
        var errorTimes = 0
        return polls.map { ok ->
            errorTimes = if (ok) 0 else errorTimes + 1
            ProbeLink.isConnected(probeType, errorTimes)
        }
    }

    @Test fun singleFailureBetweenSuccessesStaysConnected() {
        assertEquals(listOf(true, true, true), replay(0, listOf(true, false, true)))
    }

    @Test fun sustainedFailureIsLostAtTheAlarmThresholdAndOneSuccessRecovers() {
        val states = replay(0, List(6) { false } + true)
        assertEquals(listOf(true, true, true, true, false, false, true), states)
    }

    @Test fun type20UsesItsOwnThreshold() {
        assertTrue(ProbeLink.isConnected(20, 19))
        assertFalse(ProbeLink.isConnected(20, 20))
        assertFalse(ProbeLink.isConnected(1, 5))
    }

    @Test fun staggeredFailuresOnSeveralTanksAreJudgedPerTank() {
        val tank1 = replay(0, listOf(false, true, false, true))
        val tank2 = replay(0, listOf(true, false, true, false))
        assertTrue(tank1.all { it })
        assertTrue(tank2.all { it })
    }

    @Test fun lostAlarmTypesMatchTheFormerSwitch() {
        for (t in 0..6) assertTrue(ProbeLink.raisesLostAlarm(t))
        assertTrue(ProbeLink.raisesLostAlarm(20))
        assertFalse(ProbeLink.raisesLostAlarm(7))
        assertFalse(ProbeLink.raisesLostAlarm(-1))
    }
}
