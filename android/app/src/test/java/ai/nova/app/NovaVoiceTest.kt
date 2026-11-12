package ai.nova.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaVoiceTest {
    private fun words(s: String) = s.split(Regex("\\s+")).filter { it.isNotEmpty() }

    @Test fun cleanStripsMarkupAndKeepsActionText() {
        assertEquals("улыбается Привет мир", NovaVoice.clean("*улыбается* **Привет** `мир`"))
    }

    @Test fun cleanDoesNotTruncateLongText() {
        val long = "слово ".repeat(2000)
        assertTrue(NovaVoice.clean(long).length > 3900)
    }

    @Test fun chunksNeverExceedMaxAndKeepAllWords() {
        val text = (1..500).joinToString(" ") { "слово$it" } + "."
        for (max in listOf(20, 57, 100, 333)) {
            val parts = NovaVoice.chunks(text, max)
            assertTrue(parts.all { it.length <= max })
            assertEquals(words(text), parts.flatMap { words(it) })
        }
    }

    @Test fun chunksPreferSentenceBoundaries() {
        val text = "Первое предложение здесь. Второе предложение тоже здесь. Третье."
        val parts = NovaVoice.chunks(text, 40)
        assertEquals("Первое предложение здесь.", parts[0])
        assertTrue(parts.dropLast(1).all { it.endsWith(".") })
    }

    @Test fun shortTextIsOneChunkAndEmptyIsNone() {
        assertEquals(listOf("Привет."), NovaVoice.chunks("Привет.", 100))
        assertTrue(NovaVoice.chunks("   ", 100).isEmpty())
    }

    @Test fun singleVeryLongWordIsCutHard() {
        val word = "а".repeat(95)
        val parts = NovaVoice.chunks(word, 40)
        assertTrue(parts.all { it.length <= 40 })
        assertEquals(word, parts.joinToString(""))
    }

    @Test fun genderKindParsesFreeText() {
        assertEquals(1, NovaVoice.genderKind("female"))
        assertEquals(1, NovaVoice.genderKind("женский"))
        assertEquals(2, NovaVoice.genderKind("male"))
        assertEquals(2, NovaVoice.genderKind("мужской"))
        assertEquals(0, NovaVoice.genderKind(null))
    }
}
