package ai.nova.app

import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable fun SpeakButton(text: String) {
    val context=LocalContext.current
    var ready by remember { mutableStateOf(false) }
    val tts=remember { TextToSpeech(context) { ready=it==TextToSpeech.SUCCESS } }
    DisposableEffect(tts) { onDispose {tts.stop();tts.shutdown()} }
    TextButton(onClick={tts.language=Locale.forLanguageTag("ru-RU");tts.speak(text,TextToSpeech.QUEUE_FLUSH,null,"nova-message")},enabled=ready) {Text("Озвучить")}
}
@Composable fun DictationButton(model:NovaViewModel) {
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {model.updateChatInput(it)}
    }
    TextButton(onClick={runCatching {launcher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE,if(NovaLang.code=="en")"en-US" else "ru-RU")
        putExtra(RecognizerIntent.EXTRA_PROMPT,"Диктовка сообщения — текст можно изменить до отправки")
    })}.onFailure {model.showNotice("На устройстве нет службы распознавания речи")} }) {Text("Диктовать сообщение")}
}
@Composable fun StoryControls(model:NovaViewModel) {
    var expanded by remember {mutableStateOf(false)}
    OutlinedButton(onClick={expanded=!expanded}) {Text(if(expanded) "Скрыть память и настройки" else "Память и настройки истории")}
    if(!expanded)return
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("Художественная жизнь персонажа",style=MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf("дневник" to "Дневник","повседневное событие" to "Событие","внутренний монолог" to "Монолог","вымышленную заметку в телефоне" to "Заметка").forEach {(kind,label)->TextButton(onClick={model.fictionalScene(kind)},enabled=!model.loading){Text(label)}}
        }
        Text("Твоя роль в истории",style=MaterialTheme.typography.titleMedium)
        OutlinedTextField(value=model.playerPersona,onValueChange={model.playerPersona=it},label={Text("Имя, роль и факты о герое")},modifier=Modifier.fillMaxWidth())
        OutlinedTextField(value=model.storyScenario,onValueChange={model.storyScenario=it},label={Text("Сценарий / мир")},modifier=Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            listOf("concise" to "Кратко","balanced" to "Сбалансированно","story" to "Подробно").forEach {(value,label)->FilterChip(selected=model.responseStyle==value,onClick={model.responseStyle=value},label={Text(label)})}
        }
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            listOf("friendly" to "Дружелюбно","dramatic" to "Драматично","humorous" to "С юмором","calm" to "Спокойно").forEach {(value,label)->FilterChip(selected=model.storyTone==value,onClick={model.storyTone=value},label={Text(label)})}
        }
        Button(onClick={model.saveStorySettings()},enabled=!model.loading){Text("Сохранить настройки")}
        Text("Память этой истории",style=MaterialTheme.typography.titleMedium)
        Text("Записи используются только в этой переписке. Их можно исправить или удалить.",style=MaterialTheme.typography.bodySmall)
        model.memories.forEach { memory ->
            Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp)) {
                Text((if(memory.pinned) "📌 " else "")+memory.content)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick={model.pinMemory(memory)},enabled=!model.loading){Text(if(memory.pinned) "Открепить" else "Закрепить")}
                    TextButton(onClick={model.editingMemoryId=memory.id;model.memoryInput=memory.content}){Text("Изменить")}
                    TextButton(onClick={model.deleteMemory(memory.id)},enabled=!model.loading){Text("Удалить")}
                }
            }}
        }
        OutlinedTextField(value=model.memoryInput,onValueChange={model.memoryInput=it},label={Text("Важный факт или событие")},modifier=Modifier.fillMaxWidth())
        Button(onClick={model.saveMemory()},enabled=!model.loading&&model.memoryInput.isNotBlank()){Text(if(model.editingMemoryId==null)"Добавить воспоминание" else "Сохранить воспоминание")}
    }
}
@Composable fun CreatorFields(model:NovaViewModel) {
    listOf(
        Triple("Биография",model.draftBiography,{v:String->model.draftBiography=v}),
        Triple("Характер",model.draftPersonality,{v:String->model.draftPersonality=v}),
        Triple("Внешность взрослого персонажа",model.draftAppearance,{v:String->model.draftAppearance=v}),
        Triple("Интересы",model.draftInterests,{v:String->model.draftInterests=v}),
        Triple("Предыстория",model.draftBackstory,{v:String->model.draftBackstory=v}),
        Triple("Манера речи",model.draftSpeechStyle,{v:String->model.draftSpeechStyle=v}),
        Triple("Сценарий",model.draftScenario,{v:String->model.draftScenario=v}),
        Triple("Теги через запятую",model.draftTags,{v:String->model.draftTags=v}),
        Triple("Язык: ru / en",model.draftLanguage,{v:String->model.draftLanguage=v}),
        Triple("HTTPS-ссылка на свой портрет",model.draftAvatar,{v:String->model.draftAvatar=v})
    ).forEach {(label,value,set)->OutlinedTextField(value=value,onValueChange=set,label={Text(label)},modifier=Modifier.fillMaxWidth())}
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Checkbox(checked = model.draftMature, onCheckedChange = { model.draftMature = it })
        Text("Для взрослой аудитории 18+ (романтика без откровенного контента). Проходит модерацию.")
    }
}
