package ai.nova.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity

private fun reasonLabel(reason: String?) = when (reason) { "TASTE" -> "Похоже на твои истории"; "NEW" -> "Новинка"; "POPULAR" -> "Популярное"; else -> "Из каталога" }
private fun shelfTitle(key: String, title: String) = when (key) { "new" -> "Новинки"; "popular" -> "Популярные"; else -> title }

/** "For you" deck: swipe right (or tap the heart) to keep a character, left to skip. Buttons mirror the gestures for accessibility. */
@Composable
fun RecommendationDeck(model: NovaViewModel) {
    val deck = model.recommended
    if (deck.isEmpty()) return
    NovaEyebrow("ПОДБОРКА ДЛЯ ТЕБЯ")
    val c = deck.first()
    var drag by remember(c.id) { mutableStateOf(0f) }
    val swipeThreshold = with(LocalDensity.current) { 72.dp.toPx() }
    Card(shape = RoundedCornerShape(26.dp), border = BorderStroke(1.dp, NovaDesign.Border),
        colors = CardDefaults.cardColors(containerColor = NovaDesign.Surface),
        modifier = Modifier.fillMaxWidth().graphicsLayer { translationX = drag; rotationZ = drag / 60f }
            .pointerInput(c.id, model.loading, swipeThreshold) {
                if (model.loading) return@pointerInput
                detectHorizontalDragGestures(onDragEnd = {
                    if (drag > swipeThreshold) model.swipeRecommendation(c, true) else if (drag < -swipeThreshold) model.swipeRecommendation(c, false)
                    drag = 0f
                }, onDragCancel = { drag = 0f }) { _, amount -> drag += amount }
            }) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            NovaPortrait(c.id, c.name, Modifier.size(width = 104.dp, height = 150.dp).clip(RoundedCornerShape(18.dp)), c.avatarUrl)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(reasonLabel(c.reason), style = MaterialTheme.typography.labelSmall, color = NovaDesign.Rose)
                androidx.compose.material3.Text(c.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                androidx.compose.material3.Text(c.category + (c.tags.take(2).joinToString("") { " · #$it" }), style = MaterialTheme.typography.labelMedium, color = NovaDesign.Muted, maxLines = 1)
                androidx.compose.material3.Text(c.biography?.takeIf { it.isNotBlank() } ?: c.greeting, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 12.dp)) {
            OutlinedButton(onClick = { model.showCharacterProfile(c.id) }, enabled = !model.loading,
                modifier = Modifier.fillMaxWidth()) { Text("О персонаже") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { model.swipeRecommendation(c, false) }, enabled = !model.loading,
                    modifier = Modifier.weight(1f)) { Text("Пропустить") }
                Button(onClick = { model.swipeRecommendation(c, true) }, enabled = !model.loading,
                    modifier = Modifier.weight(1f)) { Text("♥ Нравится") }
            }
        }
    }
    Text("Свайп вправо сохраняет персонажа, влево — пропускает.", style = MaterialTheme.typography.labelSmall, color = NovaDesign.Muted)
}

@Composable
fun DiscoverShelves(model: NovaViewModel) {
    model.shelves.filter { it.items.isNotEmpty() }.forEach { shelf ->
        Text(shelfTitle(shelf.key, shelf.title), style = MaterialTheme.typography.titleLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            shelf.items.forEach { c ->
                Surface(onClick = { if (!model.loading) model.showCharacterProfile(c.id) }, shape = RoundedCornerShape(18.dp), color = NovaDesign.Surface,
                    border = BorderStroke(1.dp, NovaDesign.Border), modifier = Modifier.width(128.dp)) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        NovaPortrait(c.id, c.name, Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(14.dp)), c.avatarUrl)
                        androidx.compose.material3.Text(c.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        androidx.compose.material3.Text("♥ ${c.publication?.likesCount ?: 0} · ${c.category}", style = MaterialTheme.typography.labelSmall, color = NovaDesign.Muted, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Public author page: published characters, followers and likes. */
@Composable
fun AuthorDialog(model: NovaViewModel) {
    val a = model.authorPage ?: return
    AlertDialog(onDismissRequest = { model.authorPage = null }, confirmButton = { TextButton(onClick = { model.authorPage = null }) { Text("Закрыть") } },
        title = { androidx.compose.material3.Text(a.displayName) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NovaEyebrow("АВТОР ПЕРСОНАЖЕЙ")
                a.bio?.takeIf { it.isNotBlank() }?.let { androidx.compose.material3.Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("Подписчиков: ${a.followers} · лайков: ${a.likes} · персонажей: ${a.characters.orEmpty().size}", color = NovaDesign.Muted, style = MaterialTheme.typography.labelMedium)
                OutlinedButton(onClick = { model.follow(a.id, !a.isFollowing); model.authorPage = a.copy(isFollowing = !a.isFollowing, followers = a.followers + if (a.isFollowing) -1 else 1) },
                    enabled = !model.loading) { Text(if (a.isFollowing) "Отписаться" else "Подписаться на автора") }
                a.characters.orEmpty().forEach { c ->
                    Surface(onClick = { model.authorPage = null; model.showCharacterProfile(c.id) }, shape = RoundedCornerShape(14.dp), color = NovaDesign.Surface,
                        border = BorderStroke(1.dp, NovaDesign.Border), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            NovaPortrait(c.id, c.name, Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)), c.avatarUrl)
                            Column(Modifier.weight(1f)) {
                                androidx.compose.material3.Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                androidx.compose.material3.Text("♥ ${c.publication?.likesCount ?: 0} · ${c.category}", style = MaterialTheme.typography.labelSmall, color = NovaDesign.Muted)
                            }
                        }
                    }
                }
            }
        })
}

/** Author analytics on the Create tab: counts only. */
@Composable
fun CreatorStatsCard(model: NovaViewModel) {
    val s = model.creatorStats ?: return
    val totals = s.totals ?: return
    val items = s.items.orEmpty()
    if (items.isEmpty()) return
    NovaCard {
        NovaEyebrow("СТАТИСТИКА АВТОРА")
        Text("Подписчиков: ${s.followers} · лайков: ${totals.likes} · историй: ${totals.stories} · в избранном: ${totals.favorites}", style = MaterialTheme.typography.bodyMedium)
        items.take(12).forEach { i ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Text(i.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                androidx.compose.material3.Text("♥ ${i.likes} · ✎ ${i.stories} · ★ ${i.favorites} · 💬 ${i.comments}", style = MaterialTheme.typography.labelMedium, color = NovaDesign.Muted)
            }
            Text(when (i.status) { "PUBLISHED" -> "Опубликован"; "PENDING_REVIEW" -> "На модерации"; "REJECTED" -> "Отклонён модерацией"; "HIDDEN" -> "Скрыт"; else -> "Личный черновик" },
                style = MaterialTheme.typography.labelSmall, color = NovaDesign.Rose)
        }
        Text("♥ лайки · ✎ истории других людей · ★ в избранном · 💬 комментарии. Тексты чужих переписок автору недоступны.", style = MaterialTheme.typography.labelSmall, color = NovaDesign.Muted)
    }
}
