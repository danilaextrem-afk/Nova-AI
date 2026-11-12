package ai.nova.app

import android.content.Context
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
    var autoRead by mutableStateOf(prefs.getBoolean("autoRead", false))
        private set
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { if (speakingId == id) speakingId = null }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { if (speakingId == id) speakingId = null }
        })
    }

    data class Profile(val pitch: Float, val rate: Float)
    fun profile(characterId: String): Profile {
        val saved = prefs.getString("p:$characterId", null)?.split('|')?.mapNotNull { it.toFloatOrNull() }
        if (saved != null && saved.size == 2) return Profile(saved[0], saved[1])
        val h = abs(characterId.hashCode() % 1000) / 1000f
        return Profile(0.8f + h * 0.45f, 1.0f) // 0.8 (deep) … 1.25 (light)
    }
    fun saveProfile(characterId: String, p: Profile) { prefs.edit().putString("p:$characterId", "${p.pitch}|${p.rate}").apply() }
    fun updateAutoRead(on: Boolean) { autoRead = on; prefs.edit().putBoolean("autoRead", on).apply() }

    fun speak(id: String, text: String, characterId: String, language: String = "ru") {
        if (!ready) return
        val p = profile(characterId)
        tts.language = Locale.forLanguageTag(if (language == "en") "en-US" else "ru-RU")
        tts.setPitch(p.pitch); tts.setSpeechRate(p.rate)
        speakingId = id
        tts.speak(clean(text), TextToSpeech.QUEUE_FLUSH, null, id)
    }
    fun stop() { tts.stop(); speakingId = null }
    fun shutdown() { tts.stop(); tts.shutdown() }

    companion object {
        /** Reads the words, not the markup: *actions* keep their text, symbols and emoji are dropped. */
        fun clean(text: String) = text
            .replace(Regex("[*_~`#>|\\[\\]]"), " ")
            .replace(Regex("[\\p{So}\\p{Cn}\\x{1F000}-\\x{1FFFF}]"), " ")
            .replace(Regex("\\s+"), " ").trim().take(3900)
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
fun VoiceSettings(voice: NovaVoice, characterId: String, sample: String) {
    var p by remember(characterId) { mutableStateOf(voice.profile(characterId)) }
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
    TextButton(onClick = { voice.saveProfile(characterId, p); voice.speak("sample", tr(sample), characterId, NovaLang.code) }, enabled = voice.ready) { Text("Прослушать голос") }
    if (!voice.ready) Text("Голосовой движок телефона ещё загружается или не установлен (Настройки → Синтез речи).", style = MaterialTheme.typography.labelSmall, color = NovaDesign.Muted)
    Spacer(Modifier.height(8.dp))
}
