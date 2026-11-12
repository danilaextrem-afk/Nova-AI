package ai.nova.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val TRAITS = listOf("Заботлив" to "ый", "Уверенн" to "ый", "Спокойн" to "ый", "Страстн" to "ый", "Весёл" to "ый", "Загадочн" to "ый",
    "Смел" to "ый", "Романтичн" to "ый", "Ироничн" to "ый", "Мечтательн" to "ый", "Дерзк" to "ий", "Добр" to "ый")
/** Trait chips follow the chosen gender grammatically (Заботливая / Заботливый). */
private fun traitWord(stem: Pair<String, String>, gender: String?) = stem.first + when {
    gender == "male" -> stem.second
    stem.second == "ий" -> "ая"
    else -> "ая"
}
private val SCENES = listOf("Романтика", "Повседневное", "Ролевая игра", "Фэнтези", "Приключения", "Детектив", "Фантастика")
private val FIELD_LABELS = mapOf("name" to "Имя", "biography" to "Описание персонажа", "personality" to "Характер подробно",
    "appearance" to "Внешность", "interests" to "Интересы", "speechStyle" to "Манера речи", "backstory" to "Предыстория",
    "scenario" to "Сценарий общения", "greeting" to "Первое сообщение")
private val FIELD_MAX = mapOf("name" to 80, "biography" to 700, "personality" to 1500, "appearance" to 1000, "interests" to 500,
    "speechStyle" to 500, "backstory" to 3000, "scenario" to 2000, "greeting" to 1000)
private val ACTIONS = listOf("write" to "Написать с нуля", "improve" to "Улучшить мой текст", "expand" to "Сделать подробнее",
    "shorten" to "Сделать короче", "variants" to "Предложить три варианта")

@Composable
private fun EditorSection(title: String, icon: ImageVector? = null, trailing: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = NovaDesign.Surface, border = BorderStroke(1.dp, NovaDesign.Border), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon?.let { Icon(it, null, tint = NovaDesign.Lavender, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(10.dp)) }
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                trailing?.invoke()
            }
            content()
        }
    }
}

/** A text field with a ✨ menu of AI actions, the suggestion card under it, and one-tap undo. */
@Composable
private fun AssistField(model: NovaViewModel, key: String, minLines: Int = 1, label: String = FIELD_LABELS.getValue(key)) {
    val max = FIELD_MAX.getValue(key)
    var menu by remember { mutableStateOf(false) }
    val busy = model.assistBusy == key
    OutlinedTextField(value = model.draftField(key), onValueChange = { model.setDraftField(key, it.take(max)) },
        label = { Text(label) }, minLines = minLines, maxLines = if (minLines > 1) 8 else 1, singleLine = minLines == 1,
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        supportingText = if (minLines > 1) ({ androidx.compose.material3.Text("${model.draftField(key).length}/$max", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End) }) else null,
        trailingIcon = {
            Box {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else IconButton(onClick = { menu = true }, enabled = model.assistBusy == null) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = tr("AI-помощник: ") + tr(label), tint = NovaDesign.Lavender)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    ACTIONS.filter { (a, _) -> key != "name" || a == "write" || a == "variants" }.forEach { (action, title) ->
                        val needsText = action in setOf("improve", "expand", "shorten")
                        DropdownMenuItem(text = { Text(title) }, enabled = !needsText || model.draftField(key).isNotBlank(),
                            onClick = { menu = false; model.assistField(key, action) })
                    }
                }
            }
        })
    model.fieldSuggestion?.takeIf { it.field == key }?.let { s -> SuggestionCard(model, key, s) }
    if (model.fieldUndo.containsKey(key)) TextButton(onClick = { model.undoField(key) }, contentPadding = PaddingValues(0.dp)) { Text("↶ Вернуть прежний текст") }
}

@Composable
private fun SuggestionCard(model: NovaViewModel, key: String, s: NovaViewModel.FieldSuggestion) {
    var picked by remember(s) { mutableStateOf(0) }
    var edited by remember(s, picked) { mutableStateOf(s.variants[picked]) }
    Surface(shape = RoundedCornerShape(18.dp), color = Color(0xFF231B33), border = BorderStroke(1.dp, NovaDesign.Lavender.copy(alpha = 0.6f))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("✨ ПРЕДЛОЖЕНИЕ AI", style = MaterialTheme.typography.labelSmall, color = NovaDesign.Rose, letterSpacing = 1.5.sp)
            if (s.variants.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                s.variants.indices.forEach { i -> FilterChip(selected = picked == i, onClick = { picked = i }, label = { Text("Вариант ${i + 1}") }) }
            }
            // The suggestion is editable here; nothing reaches the card until "Принять".
            OutlinedTextField(value = edited, onValueChange = { edited = it.take(FIELD_MAX.getValue(key)) }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Можно поправить перед тем, как принять") }, maxLines = 8)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { model.acceptField(key, edited) }, enabled = edited.isNotBlank()) { Text("Принять") }
                TextButton(onClick = { model.assistField(key, s.action) }, enabled = model.assistBusy == null) { Text("Другой вариант") }
                TextButton(onClick = { model.fieldSuggestion = null }) { Text("Отмена") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CharacterEditor(model: NovaViewModel) {
    var ideaOpen by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var readyOpen by remember { mutableStateOf(false) }
    var consentUri by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<android.net.Uri?>(null) }
    var cameraUri by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<android.net.Uri?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var moreTraits by remember { mutableStateOf(false) }
    var customTrait by remember { mutableStateOf("") }
    var customScene by remember { mutableStateOf(false) }
    // Autosave: the unfinished character survives closing the app.
    LaunchedEffect(model.draftSignature) { kotlinx.coroutines.delay(500); model.persistDraft() }
    DisposableEffect(owner, model) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) model.persistDraft()
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            model.persistDraft()
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        if (taken) consentUri = cameraUri
        cameraUri = null
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) consentUri = uri }

    Text(if (model.editingCharacterId == null) "Создать персонажа" else "Редактировать персонажа", style = MaterialTheme.typography.headlineMedium)
    Text("Уникальный AI-персонаж для общения, историй и ролевых сцен. Черновик сохраняется на телефоне автоматически.",
        color = NovaDesign.Muted, style = MaterialTheme.typography.bodySmall)
    model.lastCreated?.let { done ->
        NovaCard {
            NovaEyebrow("ПЕРСОНАЖ ГОТОВ")
            androidx.compose.material3.Text(done.name, style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { model.lastCreated = null; model.startChat(done) }, enabled = !model.loading) { Text("Начать чат") }
                TextButton(onClick = { model.lastCreated = null }) { Text("Создать ещё") }
            }
        }
    }

    // 1. One sentence -> a whole card.
    Surface(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.background(Brush.linearGradient(listOf(Color(0xFF5B3A8E), Color(0xFF2A1F40)))).padding(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Не знаешь, с чего начать?", style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text("Опиши идею одной фразой — AI предложит имя, характер, историю, сценарий и первое сообщение. Ты всё проверишь перед сохранением.",
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFFE6DAFF))
                Button(onClick = { ideaOpen = true }, enabled = model.assistBusy == null, modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = NovaDesign.Lavender, contentColor = Color(0xFF1B1430))) {
                    if (model.assistBusy == "card") { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color(0xFF1B1430)); Spacer(Modifier.width(10.dp)); Text("AI придумывает персонажа…") }
                    else Text("✨ Создать персонажа с AI", fontWeight = FontWeight.SemiBold)
                }
                if (model.cardUndo != null) TextButton(onClick = { model.undoCard() }) { Text("↶ Вернуть карточку как было", color = Color.White) }
            }
        }
    }

    // 2. Portrait.
    EditorSection("Фото персонажа", Icons.Default.Image) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 104.dp, height = 132.dp).clip(RoundedCornerShape(18.dp))) {
                NovaPortrait(null, model.draftName.ifBlank { "?" }, Modifier.fillMaxSize(), model.draftAvatar.ifBlank { null })
                if (model.avatarBusy) Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !model.avatarBusy, modifier = Modifier.fillMaxWidth()) { Text("Из галереи") }
                OutlinedButton(onClick = {
                    runCatching {
                        val dir = java.io.File(context.cacheDir, "portrait-camera").apply { mkdirs() }
                        val file = java.io.File(dir, "portrait.jpg")
                        if (file.exists()) file.delete()
                        check(file.createNewFile())
                        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
                        cameraUri = uri
                        camera.launch(uri)
                    }.onFailure { model.showNotice("Камера недоступна. Выбери фото из галереи.") }
                }, enabled = !model.avatarBusy, modifier = Modifier.fillMaxWidth()) { Text("Сделать фото") }
                OutlinedButton(onClick = { model.avatarFromAi() }, enabled = !model.avatarBusy, modifier = Modifier.fillMaxWidth()) { Text("Портрет с AI") }
                OutlinedButton(onClick = { readyOpen = true }, enabled = !model.avatarBusy, modifier = Modifier.fillMaxWidth()) { Text("Готовые") }
            }
        }
        Text("Портрет с AI рисуется по описанию внешности (тратит одну картинку AI Арт). Своё фото не отправляется AI-сервисам.",
            style = MaterialTheme.typography.labelSmall, color = NovaDesign.Muted)
    }

    // 3. Basics: name, age and gender are always the user's choice.
    EditorSection("Основная информация", Icons.Default.Face) {
        AssistField(model, "name")
        Text("Возраст и пол выбираешь ты — AI их не угадывает и не определяет по фото.", style = MaterialTheme.typography.labelSmall, color = NovaDesign.Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            var ageMenu by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { ageMenu = true }) { Text(model.draftAge?.let { "Возраст: $it" } ?: "Возраст *") }
                DropdownMenu(expanded = ageMenu, onDismissRequest = { ageMenu = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                    (18..80).forEach { a -> DropdownMenuItem(text = { androidx.compose.material3.Text("$a") }, onClick = { model.draftAge = a; ageMenu = false }) }
                }
            }
            Text("только 18+", style = MaterialTheme.typography.labelSmall, color = NovaDesign.Muted)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("female" to "Женский", "male" to "Мужской", "other" to "Другой").forEach { (g, label) ->
                FilterChip(selected = model.draftGender == g, onClick = { model.draftGender = g }, label = { Text(label) })
            }
        }
        if (model.cardNeeds.isNotEmpty() && (model.draftAge == null || model.draftGender == null))
            Text("Выбери возраст и пол — без них персонажа не сохранить.", color = NovaDesign.Rose, style = MaterialTheme.typography.bodySmall)
    }

    // 4. Traits.
    EditorSection("Характер", Icons.Default.AutoAwesome, trailing = { Text("до 6 черт", style = MaterialTheme.typography.labelSmall, color = NovaDesign.Muted) }) {
        val words = TRAITS.map { traitWord(it, model.draftGender) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            (if (moreTraits) words else words.take(7)).forEach { w ->
                val on = model.draftTraits.any { it.equals(w, true) }
                FilterChip(selected = on, onClick = { model.draftTraits = if (on) model.draftTraits.filterNot { it.equals(w, true) } else if (model.draftTraits.size < 6) model.draftTraits + w else model.draftTraits },
                    label = { Text(w) })
            }
            model.draftTraits.filter { t -> words.none { it.equals(t, true) } }.forEach { t ->
                FilterChip(selected = true, onClick = { model.draftTraits = model.draftTraits - t }, label = { androidx.compose.material3.Text(t) })
            }
            AssistChip(onClick = { moreTraits = !moreTraits }, label = { Text(if (moreTraits) "Меньше" else "Ещё…") })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = customTrait, onValueChange = { customTrait = it.take(30) }, label = { Text("Своя черта") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(onClick = { if (customTrait.isNotBlank() && model.draftTraits.size < 6) model.draftTraits = model.draftTraits + customTrait.trim(); customTrait = "" },
                enabled = customTrait.isNotBlank()) { Text("Добавить") }
        }
    }

    // 5. Description and the scene type.
    EditorSection("Описание персонажа") { AssistField(model, "biography", minLines = 3, label = "Кто этот персонаж") }
    EditorSection("Сценарий общения") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SCENES.forEach { sc -> FilterChip(selected = model.draftCategory == sc, onClick = { model.draftCategory = sc; customScene = false }, label = { Text(sc) }) }
            FilterChip(selected = customScene || (model.draftCategory !in SCENES && model.draftCategory != "fantasy"), onClick = { customScene = true }, label = { Text("+ Свой") })
        }
        if (customScene) OutlinedTextField(value = model.draftCategory.takeIf { it !in SCENES && it != "fantasy" } ?: "", onValueChange = { model.draftCategory = it.take(50) },
            label = { Text("Свой жанр") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        AssistField(model, "scenario", minLines = 2, label = "Где и как начинается ваша история")
        AssistField(model, "greeting", minLines = 2, label = "Первое сообщение *")
    }

    // 6. Everything else.
    Surface(onClick = { advanced = !advanced }, shape = RoundedCornerShape(24.dp), color = NovaDesign.Surface, border = BorderStroke(1.dp, NovaDesign.Border), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Дополнительные настройки", style = MaterialTheme.typography.titleMedium)
                Text("Внешность, характер подробно, манера речи, интересы, предыстория, теги", style = MaterialTheme.typography.labelSmall, color = NovaDesign.Muted)
            }
            androidx.compose.material3.Text(if (advanced) "⌃" else "⌄", style = MaterialTheme.typography.titleLarge, color = NovaDesign.Muted)
        }
    }
    if (advanced) EditorSection("Подробности") {
        AssistField(model, "appearance", minLines = 2)
        AssistField(model, "personality", minLines = 2)
        AssistField(model, "speechStyle", minLines = 2)
        AssistField(model, "interests", minLines = 2)
        AssistField(model, "backstory", minLines = 3)
        OutlinedTextField(value = model.draftTags, onValueChange = { model.draftTags = it.take(300) }, label = { Text("Теги через запятую") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = model.draftLanguage == "ru", onClick = { model.draftLanguage = "ru" }, label = { Text("Русский") })
            FilterChip(selected = model.draftLanguage == "en", onClick = { model.draftLanguage = "en" }, label = { Text("English") })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = model.draftMature, onCheckedChange = { model.draftMature = it })
            Text("Для взрослой аудитории 18+ (романтика без откровенного контента). Проходит модерацию.", style = MaterialTheme.typography.bodySmall)
        }
    }

    val missing = model.missingForSave()
    Button(onClick = { model.createCharacter() }, enabled = !model.loading && model.assistBusy == null && !model.avatarBusy,
        modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(28.dp)) {
        Text(if (model.editingCharacterId == null) "✨ Создать персонажа" else "Сохранить изменения", fontWeight = FontWeight.SemiBold)
    }
    if (missing.isNotEmpty()) androidx.compose.material3.Text(tr("Осталось заполнить: ") + missing.joinToString(", ") { tr(it) }, style = MaterialTheme.typography.labelSmall, color = NovaDesign.Muted)
    Text("После сохранения персонаж личный. Чтобы он появился в каталоге, отправь его на модерацию.", style = MaterialTheme.typography.labelSmall, color = NovaDesign.Muted)
    if (model.draftHasContent) TextButton(onClick = { model.clearDraft() }) { Text("Очистить черновик") }

    if (ideaOpen) IdeaDialog(model) { ideaOpen = false }
    model.cardSuggestion?.let { CardPreviewDialog(model, it) }
    if (readyOpen) ReadyAvatarsDialog(model) { readyOpen = false }
    consentUri?.let { uri ->
        PortraitCropDialog(uri, context.contentResolver, onDismiss = { consentUri = null }) { bitmap, crop ->
            consentUri = null
            model.uploadCroppedPortrait(bitmap, crop)
        }
    }
}

@Composable
private fun IdeaDialog(model: NovaViewModel, onClose: () -> Unit) {
    var idea by remember { mutableStateOf(model.lastIdea) }
    AlertDialog(onDismissRequest = onClose, title = { Text("✨ Персонаж из одной фразы") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = idea, onValueChange = { idea = it.take(500) }, label = { Text("Твоя идея") }, minLines = 3, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Весёлая рыжеволосая путешественница, которая любит приключения") })
                Text("AI учтёт то, что уже заполнено: имя, возраст, пол и черты характера он не меняет.", style = MaterialTheme.typography.labelSmall, color = NovaDesign.Muted)
            }
        },
        confirmButton = { Button(onClick = { onClose(); model.assistFull(idea) }, enabled = idea.trim().length >= 3) { Text("Придумать") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Отмена") } })
}

/** The suggested card, field by field; marks what would replace text the user already wrote. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardPreviewDialog(model: NovaViewModel, c: AssistCard) {
    androidx.compose.ui.window.Dialog(onDismissRequest = { model.cardSuggestion = null }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = NovaDesign.Ink, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    NovaEyebrow("ПРЕДЛОЖЕНИЕ AI")
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        NovaPortrait(null, c.name, Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)), model.draftAvatar.ifBlank { null })
                        Column {
                            androidx.compose.material3.Text(c.name, style = MaterialTheme.typography.headlineSmall)
                            androidx.compose.material3.Text(listOfNotNull(c.category, model.draftAge?.let { "$it" }).joinToString(" · "), color = NovaDesign.Muted, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    c.traits?.takeIf { it.isNotEmpty() }?.let { FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { it.forEach { t -> AssistChip(onClick = {}, label = { androidx.compose.material3.Text(t) }) } } }
                    listOf("biography" to c.biography, "personality" to c.personality, "appearance" to c.appearance, "speechStyle" to c.speechStyle,
                        "interests" to c.interests, "backstory" to c.backstory, "scenario" to c.scenario, "greeting" to c.greeting).forEach { (k, v) ->
                        Surface(shape = RoundedCornerShape(16.dp), color = NovaDesign.Surface, border = BorderStroke(1.dp, NovaDesign.Border), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row { Text(FIELD_LABELS.getValue(k), style = MaterialTheme.typography.labelMedium, color = NovaDesign.Lavender, modifier = Modifier.weight(1f))
                                    if (model.draftField(k).isNotBlank() && model.draftField(k) != v) Text("заменит твой текст", style = MaterialTheme.typography.labelSmall, color = NovaDesign.Rose) }
                                androidx.compose.material3.Text(v, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    c.tags?.takeIf { it.isNotEmpty() }?.let { androidx.compose.material3.Text(it.joinToString("  ") { t -> "#$t" }, color = NovaDesign.Muted) }
                    if (model.cardNeeds.isNotEmpty()) Text("После применения выбери возраст и пол — AI их не выбирает.", color = NovaDesign.Rose, style = MaterialTheme.typography.bodySmall)
                }
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { model.applyCard() }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Применить ко всей карточке") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { model.cardSuggestion = null; model.assistFull(model.lastIdea) }, enabled = model.assistBusy == null, modifier = Modifier.weight(1f)) {
                            if (model.assistBusy == "card") CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Другой вариант") }
                        TextButton(onClick = { model.cardSuggestion = null }, modifier = Modifier.weight(1f)) { Text("Отмена") }
                    }
                }
            }
        }
    }
}

/** Original portraits that ship with the app (all adults, made for NOVA). */
@Composable
private fun ReadyAvatarsDialog(model: NovaViewModel, onClose: () -> Unit) {
    val urls = (101..112).map { StudioCast.assetBase + "/$it.webp" } + (201..212).map { StudioCast.assetBase + "/$it.webp" }
    AlertDialog(onDismissRequest = onClose, title = { Text("Готовые аватары") }, confirmButton = { TextButton(onClick = onClose) { Text("Закрыть") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                urls.chunked(3).forEachIndexed { row, chunk ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        chunk.forEachIndexed { i, url ->
                            Surface(onClick = { model.draftAvatar = url; onClose() }, shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(2.dp, if (model.draftAvatar == url) NovaDesign.Lavender else NovaDesign.Border), modifier = Modifier.weight(1f).aspectRatio(0.8f)) {
                                NovaPortrait(null, "Аватар ${row * 3 + i + 1}", Modifier.fillMaxSize(), url)
                            }
                        }
                    }
                }
            }
        })
}
