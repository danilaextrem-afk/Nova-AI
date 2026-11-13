package ai.nova.app

import ai.nova.app.VoiceCallPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCallPolicyTest {
    private fun d(error: Int, count: Int = 0, elapsed: Long = 100) = VoiceCallPolicy.decide(error, count, elapsed)

    @Test fun quickNoMatchRetriesSilentlyTwiceThenPauses() {
        val first = d(VoiceCallPolicy.ERROR_NO_MATCH, 0)
        assertEquals(Action.RETRY_SILENTLY, first.action); assertEquals(1, first.nextCount); assertNull(first.message)
        val second = d(VoiceCallPolicy.ERROR_SPEECH_TIMEOUT, 1)
        assertEquals(Action.RETRY_SILENTLY, second.action); assertEquals(2, second.nextCount)
        val third = d(VoiceCallPolicy.ERROR_NO_MATCH, 2)
        assertEquals(Action.PAUSE, third.action); assertEquals(VoiceCallPolicy.MSG_NO_VOICE, third.message)
    }

    @Test fun slowSilenceKeepsListeningAndResetsCounter() {
        val r = d(VoiceCallPolicy.ERROR_SPEECH_TIMEOUT, 2, 5000)
        assertEquals(Action.RETRY_SILENTLY, r.action); assertEquals(0, r.nextCount)
    }

    @Test fun busyAndClientRetryAfterDelayLimitedTimes() {
        for (e in listOf(VoiceCallPolicy.ERROR_RECOGNIZER_BUSY, VoiceCallPolicy.ERROR_CLIENT)) {
            assertEquals(Action.RETRY_AFTER_DELAY, d(e, 0).action)
            assertEquals(3, d(e, 2).nextCount)
            assertEquals(Action.PAUSE, d(e, 3).action)
        }
    }

    @Test fun permissionNetworkAndUnknownErrors() {
        assertEquals(Action.NEED_MIC, d(VoiceCallPolicy.ERROR_INSUFFICIENT_PERMISSIONS).action)
        assertEquals(VoiceCallPolicy.MSG_NETWORK, d(VoiceCallPolicy.ERROR_NETWORK).message)
        assertEquals(VoiceCallPolicy.MSG_NETWORK, d(VoiceCallPolicy.ERROR_NETWORK_TIMEOUT).message)
        assertEquals(VoiceCallPolicy.MSG_GENERIC, d(VoiceCallPolicy.ERROR_AUDIO).message)
        assertEquals(Action.PAUSE, d(999).action)
    }
}
