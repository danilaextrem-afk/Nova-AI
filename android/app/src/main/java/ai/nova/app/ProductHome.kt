package ai.nova.app

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun HomeScreen(model: NovaViewModel) {
    val featured = model.recommended.firstOrNull()
        ?: model.shelves.asSequence().flatMap { it.items.asSequence() }.firstOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.fillMaxWidth().height(244.dp).clip(RoundedCornerShape(28.dp))
                .background(NovaDesign.Glow)
        ) {
            if (featured != null) {
                NovaPortrait(featured.id, featured.name, Modifier.fillMaxSize(), featured.avatarUrl)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
                    Color.Transparent, Color(0x440C0B14), Color(0xF70C0B14)
                ))))
            }
            Column(Modifier.align(Alignment.BottomStart).padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (featured != null) NovaEyebrow("В КАТАЛОГЕ")
                Text(featured?.name ?: "Найди свою историю", style = MaterialTheme.typography.headlineLarge,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                val caption = featured?.category ?: "Выбери персонажа и начни разговор."
                Text(caption, color = NovaDesign.Muted, style = MaterialTheme.typography.bodyMedium)
                if (featured != null) {
                    Button(onClick = { model.showCharacterProfile(featured.id) }, enabled = !model.loading) {
                        Text("Открыть персонажа")
                    }
                }
            }
        }
        OutlinedButton(onClick = { model.changeTab(NovaTab.CATALOG) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Explore, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Исследовать каталог")
        }
    }
    if (model.recentStories.isNotEmpty()) {
        Text("Продолжить историю", style = MaterialTheme.typography.titleLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            model.recentStories.take(6).forEach { chat ->
                val title = chat.title ?: chat.characterName ?: "История"
                Surface(onClick = { model.openChat(chat) }, enabled = !model.loading,
                    shape = RoundedCornerShape(18.dp), color = NovaDesign.Surface, modifier = Modifier.width(210.dp)) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NovaPortrait(chat.characterId, title, Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)),
                            chat.characterAvatarUrl ?: model.chatPortraitUrl(chat.characterId))
                        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    if (model.discoverLoading && model.recommended.isEmpty() && model.shelves.isEmpty()) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    if (model.discoverFailed) {
        NovaCard {
            Text("Часть подборок не загрузилась")
            OutlinedButton(onClick = model::refreshDiscover, enabled = !model.discoverLoading) { Text("Повторить загрузку") }
        }
    }
    RecommendationDeck(model)
    DiscoverShelves(model)
    NovaCard {
        Text("Твоя студия", style = MaterialTheme.typography.titleLarge)
        Text("Создай героя или нарисуй сцену из своей истории.", color = NovaDesign.Muted)
        Button(onClick = { model.changeTab(NovaTab.CREATE) }, modifier = Modifier.fillMaxWidth()) {
            Text("Создать персонажа")
        }
        OutlinedButton(onClick = { model.openArt() }, modifier = Modifier.fillMaxWidth()) { Text("Открыть AI Арт") }
    }
}

internal fun productErrorMessage(message: String): String = when {
    message.contains("HTTP 404", ignoreCase = true) -> "Этот материал больше недоступен."
    message.contains("HTTP 403", ignoreCase = true) -> "У тебя нет доступа к этому действию."
    message.contains("HTTP 429", ignoreCase = true) -> "Слишком много запросов. Попробуй чуть позже."
    message.contains("HTTP", ignoreCase = true) -> "Сейчас не удалось выполнить запрос. Попробуй ещё раз."
    listOf("timed out", "timeout", "unable to resolve", "failed to connect", "connection reset", "broken pipe")
        .any { message.contains(it, ignoreCase = true) } -> "Нет связи с NOVA. Проверь интернет и попробуй снова."
    else -> message
}
