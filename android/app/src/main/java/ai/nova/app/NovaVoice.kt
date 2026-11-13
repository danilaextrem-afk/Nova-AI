package ai.nova.app

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs

/**
 * Character voices with the phone's own speech engine: free, offline-capable, nothing is sent anywhere.
 * Each character gets its own pitch and speed (a stable default from its id, adjustable by the user).
 */
class NovaVoice(context: Context) {
    private val prefs = context.getSharedPreferences("nova_voice", Context.MODE_PRIVATE)
    var ready by mutableStateOf(false); private set
    var speakingId by mutableStateOf<String?>(null); private set
    /** Russian user-facing text of the last speech failure; null when the last start succeeded. */
    var problem by mutableStateOf<String?>(null); private set
    var autoRead by mutableStateOf(prefs.getBoolean("autoRead", false))
        private set
    @Volatile private var lastUtteranceId: String? = null
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                // Only the last chunk of a reply ends the speech.
                if (id != null && id == lastUtteranceId && speakingId == id.substringBeforeLast('#')) speakingId = null
            }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) {
                if (id != null && speakingId == id.substringBeforeLast('#')) {
                    speakingId = null
                    problem = "Не удалось озвучить ответ"
                }
            }
        })
    }

    data class Profile(val pitch: Float, val rate: Float)
    /** What the app knows about a character that shapes its default voice. */
    data class VoiceHint(val gender: String? = null, val speechStyle: String? = null, val age: Int? = null)
    /** Saved per-character profile wins; otherwise a default that fits the character (gender, speech style, age). */
    fun profile(characterId: String, gender: String? = null, hint: VoiceHint? = null): Profile {
        val saved = prefs.getString("p:$characterId", null)?.split('|')?.mapNotNull { it.toFloatOrNull() }
        if (saved != null && saved.size == 2) return Profile(saved[0], saved[1])
        return defaultProfile(characterId, gender ?: hint?.gender, hint?.speechStyle, hint?.age)
    }
    fun saveProfile(characterId: String, p: Profile) { prefs.edit().putString("p:$characterId", "${p.pitch}|${p.rate}").apply() }
    fun updateAutoRead(on: Boolean) { autoRead = on; prefs.edit().putBoolean("autoRead", on).apply() }

    private fun fail(message: String) { speakingId = null; lastUtteranceId = null; problem = message }

    /** One reply to speak in its own character's voice. */
    data class Line(val text: String, val characterId: String, val gender: String? = null, val hint: VoiceHint? = null)

    fun speak(id: String, text: String, characterId: String, language: String = "ru", gender: String? = null, hint: VoiceHint? = null) =
        speakLines(id, listOf(Line(text, characterId, gender, hint)), language)

    /**
     * Speaks several replies in one queue, each with its own character's pitch and speed (group scenes).
     * Pitch and speed are captured by each speak() call, so setting them before a line's first chunk is enough.
     */
    fun speakLines(id: String, lines: List<Line>, language: String = "ru") {
        if (!ready) { fail("Голосовой движок телефона ещё не готов"); return }
        val plan = plan(lines.map { clean(it.text) }, maxChunk())
        if (plan.isEmpty()) return
        val lang = tts.setLanguage(Locale.forLanguageTag(if (language == "en") "en-US" else "ru-RU"))
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            fail(if (language == "en") "В телефоне не установлен английский голос. Установи его в настройках синтеза речи."
                else "В телефоне не установлен русский голос. Установи его в настройках синтеза речи.")
            return
        }
        speakingId = id
        lastUtteranceId = "$id#${plan.lastIndex}"
        var voiced = -1
        plan.forEachIndexed { i, (lineIndex, part) ->
            if (lineIndex != voiced) {
                val line = lines[lineIndex]
                val p = profile(line.characterId, line.gender, line.hint)
                tts.setPitch(p.pitch); tts.setSpeechRate(p.rate); voiced = lineIndex
            }
            val r = tts.speak(part, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "$id#$i")
            if (r == TextToSpeech.ERROR) { tts.stop(); fail("Не удалось озвучить ответ"); return }
        }
        problem = null
    }
    fun stop() { tts.stop(); speakingId = null; lastUtteranceId = null }
    fun shutdown() { tts.stop(); tts.shutdown() }

    /** Opens the system screen that installs missing voice data. */
    fun installVoiceData(context: Context) {
        runCatching {
            context.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun maxChunk(): Int = runCatching { TextToSpeech.getMaxSpeechInputLength() - 100 }.getOrDefault(3800).let { if (it < 200) 3800 else it }

    companion object {
        /** Reads the words, not the markup: *actions* keep their text, symbols and emoji are dropped. */
        /** Text to speak: *stage directions* are not read aloud (they are not speech); other markup goes. */
        fun clean(text: String) = text
            .replace(ACTION, " ")
            .replace(Regex("[*_~`#>|\\[\\]]"), " ")
            .replace(Regex("[\\p{So}\\p{Cn}\\x{1F000}-\\x{1FFFF}]"), " ")
            .replace(Regex("\\s+"), " ").trim()

        /** Utterance plan for several already-cleaned texts: (index of the text, chunk), empty texts skipped. */
        fun plan(texts: List<String>, max: Int): List<Pair<Int, String>> =
            texts.flatMapIndexed { index, text -> chunks(text, max).filter { it.isNotBlank() }.map { index to it } }

        // A single-asterisk action such as *улыбается*; **bold** (double asterisks) keeps its words.
        private val ACTION = Regex("(?<!\\*)\\*(?![*\\s])[^*\\n]{1,200}(?<![*\\s])\\*(?!\\*)")

        private val FAST_CUES = listOf("быстр", "тарато", "энергич", "эмоционал", "восклиц", "взахлёб", "взахлеб", "бойк")
        private val SLOW_CUES = listOf("медлен", "неторопл", "спокойн", "сдержан", "тих", "коротк", "немногослов", "лаконич", "растягива")

        /**
         * Pure default voice: pitch from gender + stable id hash, rate from speech-style cues, small age shifts.
         * Both values are clamped to the slider range 0.6..1.6.
         */
        fun defaultProfile(characterId: String, gender: String?, speechStyle: String?, age: Int?): Profile {
            val h = abs(characterId.hashCode() % 1000) / 1000f
            var pitch = when (genderKind(gender)) {
                1 -> 1.05f + h * 0.25f // female: 1.05 … 1.30
                2 -> 0.75f + h * 0.20f // male: 0.75 … 0.95
                else -> 0.85f + h * 0.35f // unknown: 0.85 … 1.20
            }
            val style = speechStyle?.lowercase(Locale.ROOT).orEmpty()
            val fast = FAST_CUES.any { style.contains(it) }
            val slow = SLOW_CUES.any { style.contains(it) }
            var rate = when {
                fast && !slow -> 1.10f + h * 0.05f // 1.10 … 1.15
                slow && !fast -> 0.88f + h * 0.05f // 0.88 … 0.93
                else -> 1.0f
            }
            if (age != null) {
                if (age >= 60) { pitch -= 0.05f; rate -= 0.05f }
                else if (age < 25) pitch += 0.03f
            }
            return Profile(pitch.coerceIn(0.6f, 1.6f), rate.coerceIn(0.6f, 1.6f))
        }

        /** 1 = female, 2 = male, 0 = unknown. Gender comes from the API as free text. */
        internal fun genderKind(gender: String?): Int {
            val g = gender?.trim()?.lowercase(Locale.ROOT) ?: return 0
            return when {
                g.startsWith("f") || g.contains("жен") || g.contains("девуш") || g.contains("girl") || g.contains("woman") -> 1
                g.startsWith("m") || g.contains("муж") || g.contains("парен") || g.contains("boy") -> 2
                else -> 0
            }
        }

        /** Splits text into pieces of at most [max] chars, preferring sentence ends, then spaces. Pure; no words are lost. */
        fun chunks(text: String, max: Int): List<String> {
            val limit = if (max < 1) 1 else max
            val out = ArrayList<String>()
            var rest = text.trim()
            while (rest.length > limit) {
                val window = rest.substring(0, limit + 1) // one extra char to see whether the cut falls on a space
                var cut = -1
                var i = limit - 1
                while (i >= limit / 3) { // sentence end followed by a space
                    val c = window[i]
                    if ((c == '.' || c == '!' || c == '?' || c == '…' || c == '\n') && window[i + 1].isWhitespace()) { cut = i + 1; break }
                    i--
                }
                if (cut < 0) {
                    val sp = window.lastIndexOf(' ', limit)
                    cut = if (sp > 0) sp else limit // a single very long word is cut hard
                }
                val piece = rest.substring(0, cut).trim()
                if (piece.isNotEmpty()) out.add(piece)
                rest = rest.substring(cut).trim()
            }
            if (rest.isNotEmpty()) out.add(rest)
            return out
        }
    }
}

/** Gender of a character known to the app (catalog, own, favourites, recommendations); null when not loaded. */
fun NovaViewModel.genderOf(characterId: String?): String? =
    if (characterId == null) null
    else (catalog + owned + favorites + recommended).firstOrNull { it.id == characterId }?.gender

/** Gender, speech style and age of a character known to the app; null when the character is not loaded. */
fun NovaViewModel.voiceHintOf(characterId: String?): NovaVoice.VoiceHint? {
    if (characterId == null) return null
    val c = (catalog + owned + favorites + recommended).firstOrNull { it.id == characterId } ?: return null
    return NovaVoice.VoiceHint(c.gender, c.speechStyle, c.age)
}

/** Small error line with a button that opens the system voice-data installer. */
@Composable
fun VoiceProblem(voice: NovaVoice) {
    val problem = voice.problem ?: return
    val context = LocalContext.current
    Column {
        Text(problem, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = { voice.installVoiceData(context) }) { Text("Установить голос") }
    }
}

@Composable
fun rememberNovaVoice(): NovaVoice {
    val context = LocalContext.current
    val voice = remember { NovaVoice(context) }
    DisposableEffect(voice) { onDispose { voice.shutdown() } }
    return voice
}

/** Voice section of the chat settings: auto-read, pitch and speed for this character. */
@Composable
fun VoiceSettings(voice: NovaVoice, characterId: String, sample: String, gender: String? = null, hint: NovaVoice.VoiceHint? = null) {
    var p by remember(characterId) { mutableStateOf(voice.profile(characterId, gender, hint)) }
    NovaEyebrow("ГОЛОС ПЕРСОНАЖА")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Читать ответы вслух", Modifier.weight(1f))
        Switch(checked = voice.autoRead, onCheckedChange = { voice.updateAutoRead(it) })
    }
    Text("Тембр: " + when { p.pitch < 0.92f -> "низкий"; p.pitch > 1.12f -> "высокий"; else -> "средний" }, style = MaterialTheme.typography.bodySmall)
    Slider(value = p.pitch, onValueChange = { p = p.copy(pitch = it) }, valueRange = 0.6f..1.6f,
        onValueChangeFinished = { voice.saveProfile(characterId, p) })
    Text("Скорость: " + String.format(Locale.US, "%.1f×", p.rate), style = MaterialTheme.typography.bodySmall)
    Slider(value = p.rate, onValueChange = { p = p.copy(rate = it) }, valueRange = 0.6f..1.6f,
        onValueChangeFinished = { voice.saveProfile(characterId, p) })
    TextButton(onClick = { voice.saveProfile(characterId, p); voice.speak("sample", tr(sample), characterId, NovaLang.code, gender, hint) }, enabled = voice.ready) { Text("Прослушать голос") }
    if (!voice.ready) Text("Голосовой движок телефона ещё загружается или не установлен (Настройки → Синтез речи).", style = MaterialTheme.typography.labelSmall, color = NovaDesign.Muted)
    VoiceProblem(voice)
    Spacer(Modifier.height(8.dp))
}
