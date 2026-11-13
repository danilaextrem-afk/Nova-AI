package ai.nova.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class CallState { NEED_MIC, LISTENING, THINKING, SPEAKING, PAUSED, UNAVAILABLE }

/**
 * Pure decision logic for recognizer errors, free of Android calls so it runs in JVM unit tests.
 * Error codes are copies of the SpeechRecognizer.ERROR_* constants (stable platform values).
 */
object VoiceCallPolicy {
    const val ERROR_NETWORK_TIMEOUT = 1
    const val ERROR_NETWORK = 2
    const val ERROR_AUDIO = 3
    const val ERROR_SERVER = 4
    const val ERROR_CLIENT = 5
    const val ERROR_SPEECH_TIMEOUT = 6
    const val ERROR_NO_MATCH = 7
    const val ERROR_RECOGNIZER_BUSY = 8
    const val ERROR_INSUFFICIENT_PERMISSIONS = 9
    const val ERROR_SERVER_DISCONNECTED = 11 // API 31+

    /** A recognition that failed sooner than this after starting is a service failure, not the user being quiet. */
    const val QUICK_FAIL_MS = 1500L
    const val MAX_SILENT_RETRIES = 2
    const val MAX_BUSY_RETRIES = 3
    const val SILENT_RETRY_DELAY_MS = 250L
    const val BUSY_RETRY_DELAY_MS = 350L

    enum class Action { RETRY_SILENTLY, RETRY_AFTER_DELAY, PAUSE, NEED_MIC }

    /** [nextCount] is the value the caller must store as the consecutive-failure counter. */
    data class Decision(val action: Action, val message: String? = null, val nextCount: Int = 0)

    const val MSG_NO_VOICE = "Не слышу тебя. Нажми «Продолжить» и говори."
    const val MSG_NETWORK = "Распознаванию речи нужен интернет. Проверь связь."
    const val MSG_GENERIC = "Распознавание речи прервалось. Нажми «Продолжить»."

    /** [consecutive] = quick/busy failures in a row so far (reset to 0 on any real result); [elapsedMs] = time since listening began. */
    fun decide(error: Int, consecutive: Int, elapsedMs: Long): Decision = when (error) {
        ERROR_NO_MATCH, ERROR_SPEECH_TIMEOUT -> when {
            elapsedMs >= QUICK_FAIL_MS -> Decision(Action.RETRY_SILENTLY, null, 0) // ordinary silence
            consecutive < MAX_SILENT_RETRIES -> Decision(Action.RETRY_SILENTLY, null, consecutive + 1)
            else -> Decision(Action.PAUSE, MSG_NO_VOICE, 0)
        }
        ERROR_RECOGNIZER_BUSY, ERROR_CLIENT, ERROR_SERVER_DISCONNECTED ->
            if (consecutive < MAX_BUSY_RETRIES) Decision(Action.RETRY_AFTER_DELAY, null, consecutive + 1)
            else Decision(Action.PAUSE, MSG_GENERIC, 0)
        ERROR_INSUFFICIENT_PERMISSIONS -> Decision(Action.NEED_MIC, null, 0)
        ERROR_NETWORK, ERROR_NETWORK_TIMEOUT -> Decision(Action.PAUSE, MSG_NETWORK, 0)
        else -> Decision(Action.PAUSE, MSG_GENERIC, 0)
    }
}

/**
 * Hands-free voice conversation: the phone listens, the recognised words are sent as a normal chat
 * message, the character's reply is spoken, and listening starts again. Speech recognition and speech
 * synthesis are the phone's own services, so it costs nothing; it is turn-by-turn rather than a duplex
 * audio stream. An incoming call or another app taking the audio pauses the conversation.
 */
@Composable
fun VoiceCallDialog(model: NovaViewModel, voice: NovaVoice, onClose: () -> Unit) {
    val context = LocalContext.current
    val chat = model.activeChat ?: return
    val available = remember { SpeechRecognizer.isRecognitionAvailable(context) }
    fun micGranted() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    var state by remember { mutableStateOf(if (!available) CallState.UNAVAILABLE else if (micGranted()) CallState.PAUSED else CallState.NEED_MIC) }
    var heard by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    var lastSpokenId by remember { mutableStateOf(model.chatMessages.lastOrNull()?.id) }
    var listenStartedAt by remember { mutableStateOf(0L) }
    var replyDelayMs by remember { mutableStateOf<Long?>(null) }
    var sentAt by remember { mutableStateOf(0L) }
    val recognizer = remember { if (available) SpeechRecognizer.createSpeechRecognizer(context) else null }
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    // Scope is cancelled when the dialog leaves composition, so delayed restarts never outlive the call.
    val scope = rememberCoroutineScope()
    val restartJob = remember { arrayOfNulls<Job>(1) }
    var failures by remember { mutableStateOf(0) }

    /** Raw start of one recognition session; false when the service refused to start. */
    fun startRecognizer(): Boolean {
        val r = recognizer ?: return false
        listenStartedAt = System.currentTimeMillis()
        return runCatching {
            r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (NovaLang.code == "en") "en-US" else "ru-RU")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // Do not cut the player off at a short breath; recognisers may ignore these hints.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1600L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 800L)
            })
        }.isSuccess
    }
    fun pause(reason: String? = null) {
        restartJob[0]?.cancel(); restartJob[0] = null
        recognizer?.cancel(); voice.stop(); state = CallState.PAUSED; note = reason
    }
    fun listen() {
        if (recognizer == null) return
        restartJob[0]?.cancel(); restartJob[0] = null // never two starts racing each other
        state = CallState.LISTENING; note = null
        if (!startRecognizer()) pause(VoiceCallPolicy.MSG_GENERIC)
    }
    /** Restarts listening after [delayMs] (optionally cancelling a stuck session first); stays in LISTENING meanwhile. */
    fun restartLater(delayMs: Long, cancelFirst: Boolean) {
        restartJob[0]?.cancel()
        restartJob[0] = scope.launch {
            if (cancelFirst) recognizer?.cancel()
            delay(delayMs)
            if (state == CallState.LISTENING && !startRecognizer()) pause(VoiceCallPolicy.MSG_GENERIC)
        }
    }
    fun handleFailure(error: Int) {
        val d = VoiceCallPolicy.decide(error, failures, System.currentTimeMillis() - listenStartedAt)
        failures = d.nextCount
        when (d.action) {
            VoiceCallPolicy.Action.RETRY_SILENTLY -> restartLater(VoiceCallPolicy.SILENT_RETRY_DELAY_MS, false)
            VoiceCallPolicy.Action.RETRY_AFTER_DELAY -> restartLater(VoiceCallPolicy.BUSY_RETRY_DELAY_MS, true)
            VoiceCallPolicy.Action.NEED_MIC -> { recognizer?.cancel(); state = CallState.NEED_MIC }
            VoiceCallPolicy.Action.PAUSE -> pause(d.message)
        }
    }

    // Incoming calls and other apps taking the audio pause the conversation instead of talking over them.
    val focus = remember {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
                    pause("Разговор на паузе: звук занят звонком или другим приложением")
            }.build()
    }
    DisposableEffect(recognizer) {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                if (state != CallState.LISTENING) return
                if (text.isEmpty()) { handleFailure(VoiceCallPolicy.ERROR_NO_MATCH); return }
                failures = 0
                heard = text; state = CallState.THINKING; sentAt = System.currentTimeMillis()
                model.updateChatInput(text); model.sendChatMessage()
            }
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { heard = it }
            }
            override fun onError(error: Int) {
                if (state != CallState.LISTENING) return
                handleFailure(error)
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        audio.requestAudioFocus(focus)
        onDispose { restartJob[0]?.cancel(); recognizer?.destroy(); voice.stop(); audio.abandonAudioFocusRequest(focus) }
    }
    LaunchedEffect(Unit) { if (state == CallState.PAUSED) listen() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen() else note = "Без доступа к микрофону голосовой разговор невозможен. Разрешение можно выдать в настройках телефона."
    }
    // The reply arrived: speak every new line (a group turn has several), then listen again.
    LaunchedEffect(model.chatMessages.lastOrNull()?.id, model.loading) {
        if (state != CallState.THINKING || model.loading) return@LaunchedEffect
        val all = model.chatMessages
        val fresh = all.drop(all.indexOfFirst { it.id == lastSpokenId } + 1).filter { it.role != "user" }
        if (model.error != null || fresh.isEmpty()) { state = CallState.PAUSED; note = model.error ?: "Ответ не пришёл. Нажми «Продолжить» и повтори."; return@LaunchedEffect }
        lastSpokenId = all.last().id; replyDelayMs = System.currentTimeMillis() - sentAt
        state = CallState.SPEAKING
        voice.speak("call-" + all.last().id, fresh.joinToString(". ") { (it.speakerName?.let { n -> "$n. " } ?: "") + it.content }, chat.characterId, NovaLang.code, model.genderOf(chat.characterId))
        // A failed start never changes speakingId, so the effect below would not fire: go back to listening here.
        if (voice.speakingId == null) listen()
    }
    LaunchedEffect(voice.speakingId) { if (state == CallState.SPEAKING && voice.speakingId == null) listen() }

    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = NovaDesign.Ink, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
                NovaEyebrow("ГОЛОСОВОЙ РАЗГОВОР")
                NovaPortrait(chat.characterId, chat.characterName ?: "NOVA", Modifier.size(132.dp).clip(CircleShape), model.chatPortraitUrl(chat.characterId))
                androidx.compose.material3.Text(chat.characterName ?: chat.title ?: "NOVA", style = MaterialTheme.typography.headlineMedium)
                Text(when (state) {
                    CallState.NEED_MIC -> "Нужен доступ к микрофону"
                    CallState.LISTENING -> "Слушаю тебя…"
                    CallState.THINKING -> "Персонаж думает…"
                    CallState.SPEAKING -> "Персонаж говорит"
                    CallState.PAUSED -> "Пауза"
                    CallState.UNAVAILABLE -> "На телефоне нет службы распознавания речи"
                }, style = MaterialTheme.typography.titleMedium, color = NovaDesign.Lavender)
                if (state == CallState.LISTENING || state == CallState.THINKING) LinearProgressIndicator(Modifier.fillMaxWidth(0.6f))
                if (heard.isNotBlank()) androidx.compose.material3.Text("«$heard»", textAlign = TextAlign.Center, color = NovaDesign.Muted, maxLines = 4)
                note?.let { Text(it, textAlign = TextAlign.Center, color = NovaDesign.Rose, style = MaterialTheme.typography.bodySmall) }
                VoiceProblem(voice)
                replyDelayMs?.let { Text("Ответ пришёл за ${(it + 500) / 1000} с", style = MaterialTheme.typography.labelSmall, color = NovaDesign.Muted) }
                when (state) {
                    CallState.NEED_MIC -> Button(onClick = { permission.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Разрешить микрофон") }
                    CallState.PAUSED -> Button(onClick = { if (micGranted()) { audio.requestAudioFocus(focus); listen() } else state = CallState.NEED_MIC }, enabled = !model.loading) { Text("Продолжить") }
                    CallState.LISTENING -> OutlinedButton(onClick = { pause() }) { Text("Пауза") }
                    CallState.SPEAKING -> {
                        // Barge-in: cut the character off and listen at once.
                        Button(onClick = { voice.stop(); failures = 0; listen() }) { Text("Перебить") }
                        OutlinedButton(onClick = { pause() }) { Text("Пауза") }
                    }
                    CallState.THINKING -> OutlinedButton(onClick = { model.stopReply(); pause() }) { Text("Отменить ответ") }
                    CallState.UNAVAILABLE -> Text("Установи или включи «Голосовой ввод Google» в настройках телефона. Диктовка и озвучка в чате остаются доступны.", textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onClose) { Text("Завершить разговор") }
                Text("Речь распознаёт и озвучивает сам телефон; в NOVA уходит только текст. Это разговор по очереди, а не непрерывный аудиопоток.",
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = NovaDesign.Muted)
            }
        }
    }
}
