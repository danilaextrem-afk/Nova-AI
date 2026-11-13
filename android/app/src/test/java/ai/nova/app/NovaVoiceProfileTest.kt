package ai.nova.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaVoiceProfileTest {
    private val ids = listOf("a", "char-1", "nova", "zzz-42", "Алиса", "x".repeat(40))
    private fun d(id: String, gender: String? = null, style: String? = null, age: Int? = null) =
        NovaVoice.defaultProfile(id, gender, style, age)

    @Test fun calmAndEnergeticStylesGiveDifferentRates() {
        for (id in ids) {
            val fast = d(id, "female", "Говорит быстро и энергично, с восклицаниями")
            val slow = d(id, "female", "Спокойная, неторопливая речь")
            assertTrue(fast.rate in 1.10f..1.151f)
            assertTrue(slow.rate in 0.88f..0.931f)
            assertTrue(fast.rate > slow.rate)
        }
    }

    @Test fun cuesAreCaseInsensitive() {
        assertEquals(d("a", null, "БЫСТРАЯ речь").rate, d("a", null, "быстрая речь").rate, 0f)
        assertTrue(d("a", null, "БЫСТРАЯ речь").rate > 1.0f)
    }

    @Test fun noCuesOrBothCuesGiveNormalRate() {
        for (id in ids) {
            assertEquals(1.0f, d(id).rate, 0f)
            assertEquals(1.0f, d(id, "male", "").rate, 0f)
            assertEquals(1.0f, d(id, "male", "обычная манера").rate, 0f)
            assertEquals(1.0f, d(id, "male", "быстрая, но спокойная").rate, 0f)
        }
    }

    @Test fun genderPitchRangesAreKept() {
        for (id in ids) {
            assertTrue(d(id, "female").pitch in 1.05f..1.301f)
            assertTrue(d(id, "женский").pitch in 1.05f..1.301f)
            assertTrue(d(id, "male").pitch in 0.75f..0.951f)
            assertTrue(d(id, "мужской").pitch in 0.75f..0.951f)
            assertTrue(d(id, null).pitch in 0.85f..1.201f)
        }
    }

    @Test fun elderlyLowerPitchAndRate() {
        for (id in ids) {
            val base = d(id, "female", "быстро")
            val old = d(id, "female", "быстро", 60)
            assertEquals(base.pitch - 0.05f, old.pitch, 0.001f)
            assertEquals(base.rate - 0.05f, old.rate, 0.001f)
            assertEquals(d(id, "male").pitch, d(id, "male", null, 59).pitch, 0f)
        }
    }

    @Test fun youngRaisePitchOnly() {
        for (id in ids) {
            val base = d(id, "male", "спокойно")
            val young = d(id, "male", "спокойно", 24)
            assertEquals(base.pitch + 0.03f, young.pitch, 0.001f)
            assertEquals(base.rate, young.rate, 0f)
            assertEquals(base.pitch, d(id, "male", "спокойно", 25).pitch, 0f)
        }
    }

    @Test fun valuesStayInSliderRange() {
        for (id in ids) for (g in listOf(null, "female", "male")) for (age in listOf(null, 5, 24, 60, 120, -3, Int.MAX_VALUE))
            for (s in listOf(null, "быстро", "медленно", "быстро и медленно")) {
                val p = d(id, g, s, age)
                assertTrue(p.pitch in 0.6f..1.6f)
                assertTrue(p.rate in 0.6f..1.6f)
            }
    }

    @Test fun resultsAreStable() {
        for (id in ids) assertEquals(d(id, "female", "тихая", 70), d(id, "female", "тихая", 70))
    }

    @Test fun differentIdsCanDiffer() {
        assertNotEquals(ids.map { d(it, "male").pitch }.toSet().size, 1)
    }
}
