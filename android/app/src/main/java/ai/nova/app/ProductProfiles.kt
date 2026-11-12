package ai.nova.app

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun CharacterProfileSheet(model: NovaViewModel) {
    val c = model.profileCharacter ?: return
    val entry = model.catalog.firstOrNull { it.id == c.id }
    val publication = c.publication ?: entry?.publication
    val owner = entry?.ownerId ?: c.ownerId
    val favorite = c.isFavorite ?: model.favorites.any { it.id == c.id }
    var portraitOpen by remember(c.id) { mutableStateOf(false) }
    if (portraitOpen) Dialog(onDismissRequest = { portraitOpen = false },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                NovaPortrait(c.id, c.name, Modifier.fillMaxSize(), c.avatarUrl ?: entry?.avatarUrl,
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                FilledTonalIconButton(onClick = { portraitOpen = false }, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть портрет")
                }
            }
        }
    }
    Dialog(onDismissRequest = { model.profileCharacter = null },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = NovaDesign.Ink) {
            Column(Modifier.navigationBarsPadding().imePadding().verticalScroll(rememberScrollState())) {
                Box(Modifier.fillMaxWidth().height(360.dp)) {
                    NovaPortrait(c.id, c.name, Modifier.fillMaxSize(), c.avatarUrl ?: entry?.avatarUrl)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x66100B18), Color.Transparent, Color(0xFA0C0B14)))))
                    TextButton(onClick = { model.profileCharacter = null }, modifier = Modifier.statusBarsPadding().padding(8.dp)) { Text("Закрыть профиль", color = Color.White) }
                    FilledTonalIconButton(onClick = { portraitOpen = true },
                        modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
                        Icon(Icons.Default.Fullscreen, contentDescription = "Открыть портрет")
                    }
                    Column(Modifier.align(Alignment.BottomStart).padding(22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NovaEyebrow(c.category); if (c.mature || entry?.mature == true) MatureBadge()
                        }
                        Text(c.name, style = MaterialTheme.typography.headlineLarge, color = Color.White)
                        val summary = listOfNotNull(c.age?.toString()?.let { "Возраст: $it" },
                            c.traits.orEmpty().take(2).joinToString(" · ").ifBlank { null }).joinToString("  •  ")
                        if (summary.isNotBlank()) Text(summary, style = MaterialTheme.typography.bodySmall, color = Color(0xFFE2D8EC))
                    }
                }
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Button(onClick = { model.profileCharacter = null; model.startChat(entry ?: c) }, enabled = !model.loading,
                        modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Начать AI-чат") }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { if (favorite) model.removeFavorite(c.id) else model.addFavorite(c.id) }, enabled = !model.loading) {
                            Text(if (favorite) "Убрать из избранного" else "В избранное")
                        }
                        publication?.let { pub ->
                            OutlinedButton(onClick = { model.like(pub.id) }, enabled = !model.loading && !model.isPublicationLiked(pub.id)) {
                                Text("♥ ${pub.likesCount}")
                            }
                        }
                    }
                    NovaCard {
                        Text("Первое сообщение", style = MaterialTheme.typography.titleMedium)
                        Text("«${c.greeting}»", style = MaterialTheme.typography.bodyLarge)
                    }
                    listOf("Биография" to c.biography, "Характер" to c.personality, "Внешность" to c.appearance, "Интересы" to c.interests,
                        "Предыстория" to c.backstory, "Манера речи" to c.speechStyle, "Сценарий" to c.scenario)
                        .forEach { (label, value) -> if (!value.isNullOrBlank()) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(label, style = MaterialTheme.typography.titleMedium, color = NovaDesign.Lavender); Text(value, style = MaterialTheme.typography.bodyMedium)
                        } }
                    HorizontalDivider(color = NovaDesign.Border)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = c.id in model.groupIds, onCheckedChange = { checked ->
                            model.groupIds = if (!checked) model.groupIds - c.id else if (model.groupIds.size < 4) model.groupIds + c.id else model.groupIds
                        })
                        Text("Участник групповой истории (до 4)", style = MaterialTheme.typography.bodyMedium)
                    }
                    // Author scenarios: the same character in different worlds, each as a separate story.
                    LaunchedEffect(c.id) { model.loadScenarios(c.id) }
                    val mine = model.owned.any { it.id == c.id }
                    if (model.scenarios.isNotEmpty() || mine) Text("Сценарии", style = MaterialTheme.typography.titleMedium)
                    model.scenarios.forEach { sc ->
                        NovaCard {
                            androidx.compose.material3.Text(sc.title, style = MaterialTheme.typography.titleSmall)
                            androidx.compose.material3.Text(sc.scenario, style = MaterialTheme.typography.bodySmall, color = NovaDesign.Muted, maxLines = 4)
                            Row {
                                TextButton(onClick = { model.startChat(c, sc) }, enabled = !model.loading) { Text("Начать этот сценарий") }
                                if (mine) TextButton(onClick = { model.deleteScenario(c.id, sc.id) }, enabled = !model.loading) { Text("Удалить") }
                            }
                        }
                    }
                    if (mine && model.scenarios.size < 8) {
                        OutlinedTextField(value = model.scenarioTitle, onValueChange = { model.scenarioTitle = it.take(80) }, label = { Text("Название сценария") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = model.scenarioText, onValueChange = { model.scenarioText = it.take(2000) }, label = { Text("Мир и завязка сценария") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                        OutlinedButton(onClick = { model.addScenario(c.id) }, enabled = !model.loading) { Text("Добавить сценарий") }
                    }
                    if (publication != null) owner?.let { creator ->
                        OutlinedButton(onClick = { model.openAuthor(creator) }, enabled = !model.loading, modifier = Modifier.fillMaxWidth()) { Text("Страница автора") }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (publication != null && !c.mature) { val ctx = LocalContext.current
                            TextButton(onClick = { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, c.name + " — NOVA AI\n" + model.shareLink(c.id)) }, c.name)) }) { Text("Поделиться ссылкой") } }
                        TextButton(onClick = { model.cloneCharacter(c.id) }, enabled = !model.loading) { Text("Создать свою версию") }
                        TextButton(onClick = { model.openArt(listOfNotNull(c.name, c.appearance?.takeIf { it.isNotBlank() } ?: c.biography).joinToString(", ")) }) { Text("Нарисовать арт") }
                    }
                    publication?.let { pub ->
                        HorizontalDivider(color = NovaDesign.Border)
                        TextButton(onClick = { model.openComments(pub.id) }, enabled = !model.loading, contentPadding = PaddingValues(0.dp)) { Text("Обсуждение") }
                        if (model.selectedPublication == pub.id) {
                            if (model.comments.isEmpty()) Text("Пока нет комментариев.", color = NovaDesign.Muted, style = MaterialTheme.typography.bodySmall)
                            model.comments.forEach { cm ->
                                NovaCard {
                                    Text(cm.user?.profile?.displayName ?: "Участник", style = MaterialTheme.typography.labelLarge)
                                    if (cm.parentId != null) {
                                        val name = cm.parent?.user?.profile?.displayName
                                        Text(name?.let { "В ответ на комментарий: $it" } ?: "Ответ на комментарий",
                                            color = NovaDesign.Lavender, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text(cm.content + if (cm.status == "PENDING_REVIEW") " · На модерации" else "",
                                        style = MaterialTheme.typography.bodyMedium)
                                    if (model.commentRepliesSupported && cm.parentId == null && cm.status == "PUBLISHED") {
                                        TextButton(onClick = { model.commentReplyTo = cm }, enabled = !model.loading) { Text("Ответить") }
                                    }
                                }
                            }
                            model.commentReplyTo?.let { parent ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(parent.content, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        color = NovaDesign.Lavender, style = MaterialTheme.typography.bodySmall)
                                    IconButton(onClick = { model.commentReplyTo = null }) {
                                        Icon(Icons.Default.Close, contentDescription = "Отменить ответ")
                                    }
                                }
                            }
                            OutlinedTextField(value = model.commentInput, onValueChange = { model.commentInput = it.take(1000) },
                                label = { Text(if (model.commentReplyTo == null) "Комментарий" else "Ответ на комментарий") },
                                modifier = Modifier.fillMaxWidth(), maxLines = 4)
                            Button(onClick = { model.sendComment() }, enabled = !model.loading && model.commentInput.isNotBlank()) { Text(if (model.commentReplyTo == null) "Отправить комментарий" else "Отправить ответ") }
                        }
                    }
                    if (publication != null) ModerationActions(model, "PUBLICATION", publication.id, owner?.takeUnless { mine })
                    StatusMessage(model)
                }
            }
        }
    }
}


@Composable
internal fun ModerationActions(model: NovaViewModel, type: String, target: String, blockId: String?) {
    var reportOpen by remember(target) { mutableStateOf(false) }
    var blockOpen by remember(blockId) { mutableStateOf(false) }
    var reason by remember(target) { mutableStateOf("") }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { reportOpen = true }, enabled = !model.loading) { Text("Пожаловаться") }
        if (blockId != null) TextButton(onClick = { blockOpen = true }, enabled = !model.loading) { Text("Заблокировать автора") }
    }
    if (reportOpen) AlertDialog(
        onDismissRequest = { reportOpen = false },
        title = { Text("Сообщить о нарушении") },
        text = {
            OutlinedTextField(value = reason, onValueChange = { reason = it.take(500) },
                label = { Text("Причина жалобы") }, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            TextButton(onClick = {
                model.reportContent(type, target, reason.trim())
                reportOpen = false
                reason = ""
            }, enabled = reason.trim().length >= 5 && !model.loading) { Text("Отправить жалобу") }
        },
        dismissButton = { TextButton(onClick = { reportOpen = false }) { Text("Отмена") } }
    )
    if (blockOpen && blockId != null) AlertDialog(
        onDismissRequest = { blockOpen = false },
        title = { Text("Заблокировать автора?") },
        text = { Text("Его публикации перестанут появляться в твоём каталоге.") },
        confirmButton = {
            TextButton(onClick = {
                model.blockCreator(blockId)
                blockOpen = false
            }, enabled = !model.loading) { Text("Заблокировать") }
        },
        dismissButton = { TextButton(onClick = { blockOpen = false }) { Text("Отмена") } }
    )
}

@Composable
internal fun AuthorDialog(model: NovaViewModel) {
    val author = model.authorPage ?: return
    val isSelf = author.characters.orEmpty().any { character -> model.owned.any { it.id == character.id } }
    Dialog(onDismissRequest = { model.authorPage = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = NovaDesign.Ink) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                TextButton(onClick = { model.authorPage = null }) { Text("Закрыть") }
                NovaCard {
                    Surface(shape = CircleShape, color = Color(0xFF433157), modifier = Modifier.size(58.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(author.displayName.take(1).uppercase(), style = MaterialTheme.typography.titleLarge)
                        }
                    }
                    Text(author.displayName, style = MaterialTheme.typography.headlineMedium)
                    author.bio?.takeIf { it.isNotBlank() }?.let { Text(it, color = NovaDesign.Muted) }
                    Text("Подписчиков: ${author.followers} · лайков: ${author.likes} · персонажей: ${author.characters.orEmpty().size}",
                        color = NovaDesign.Muted, style = MaterialTheme.typography.bodySmall)
                    if (!isSelf) Button(onClick = { model.follow(author.id, !author.isFollowing) },
                        enabled = !model.loading, modifier = Modifier.fillMaxWidth()) {
                        Text(if (author.isFollowing) "Отписаться" else "Подписаться на автора")
                    }
                }
                Text("Публикации", style = MaterialTheme.typography.titleLarge)
                author.characters.orEmpty().forEach { character ->
                    Surface(onClick = { model.openAuthorCharacter(character.id) }, enabled = !model.loading,
                        color = NovaDesign.Surface, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            NovaPortrait(character.id, character.name, Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)),
                                character.avatarUrl)
                            Column(Modifier.weight(1f)) {
                                Text(character.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(character.category, color = NovaDesign.Muted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (!isSelf) ModerationActions(model, "USER", author.id, author.id)
                StatusMessage(model)
            }
        }
    }
}
