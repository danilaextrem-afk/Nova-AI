package ai.nova.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaVoiceLinesTest {
    @Test fun groupRepliesKeepTheirOrderAndOwner() {
        val texts = listOf(NovaVoice.clean("*кивает* Находка странная."), NovaVoice.clean("Вера права. Маяк молчит."))
        val plan = NovaVoice.plan(texts, 3800)
        assertEquals(listOf(0 to "Находка странная.", 1 to "Вера права. Маяк молчит."), plan)
    }

    @Test fun actionOnlyRepliesAreSkippedAndLongOnesAreSplitPerOwner() {
        val long = "Предложение номер один. ".repeat(40).trim()
        val plan = NovaVoice.plan(listOf(NovaVoice.clean("*молчит*"), long), 300)
        assertTrue(plan.size > 1)
        assertTrue(plan.all { it.first == 1 && it.second.length <= 300 })
    }

    @Test fun nothingToSayGivesAnEmptyPlan() {
        assertEquals(emptyList<Pair<Int, String>>(), NovaVoice.plan(listOf("", NovaVoice.clean("*улыбается*")), 3800))
    }
}
