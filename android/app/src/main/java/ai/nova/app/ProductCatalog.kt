package ai.nova.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun CatalogScreen(model: NovaViewModel) {
    Text("Каталог персонажей", style = MaterialTheme.typography.headlineMedium)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(CatalogView.PUBLIC to "Все", CatalogView.FAVORITES to "Моё избранное",
            CatalogView.FOLLOWING to "Мои подписки").forEach { (view, label) ->
            FilterChip(selected = model.catalogView == view, enabled = !model.loading,
                onClick = { model.selectCatalogView(view) }, label = { Text(label) })
        }
    }
    if (model.catalogView == CatalogView.PUBLIC) CatalogFilters(model)
    if (model.groupIds.isNotEmpty()) Surface(color = Color(0xFF2A2236), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Text("Групповая история: ещё ${model.groupIds.size} участн. Начни чат с главным героем — они присоединятся.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(14.dp))
    }
    if (model.catalogFailed) NovaCard {
        Text("Не удалось загрузить персонажей", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = model::refreshCatalog, enabled = !model.loading) { Text("Повторить") }
    }
    if (model.catalog.isEmpty() && model.loading) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    } else if (model.catalog.isEmpty() && !model.catalogFailed) {
        val (title, subtitle) = when (model.catalogView) {
            CatalogView.FAVORITES -> "В избранном пока пусто" to "Сохрани персонажа из его профиля или подборки."
            CatalogView.FOLLOWING -> "Пока нет публикаций" to "Подпишись на автора, чтобы видеть его персонажей здесь."
            CatalogView.PUBLIC -> "Персонажи не найдены" to "Попробуй другой запрос или сбрось фильтры."
        }
        NovaCard {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = NovaDesign.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
    model.catalog.forEach { character -> key(character.id) { CharacterCard(model, character) } }
    if (model.catalogNextCursor != null) OutlinedButton(onClick = model::loadMoreCatalog,
        enabled = !model.loading, modifier = Modifier.fillMaxWidth()) { Text("Показать ещё") }
}

@Composable
private fun CatalogFilters(model: NovaViewModel) {
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = {
        keyboard?.hide()
        model.refreshCatalog()
    }
    if (model.showMature) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !model.matureSection, enabled = !model.loading,
            onClick = { model.selectMatureSection(false) }, label = { Text("Для всех") })
        FilterChip(selected = model.matureSection, enabled = !model.loading,
            onClick = { model.selectMatureSection(true) }, label = { Text("18+ Mature") })
    }
    OutlinedTextField(
        value = model.catalogQuery, onValueChange = { model.catalogQuery = it.take(80) },
        placeholder = { Text("Имя, характер или история") }, singleLine = true, shape = RoundedCornerShape(20.dp),
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = { TextButton(onClick = submit, enabled = !model.loading) { Text("Найти") } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { if (!model.loading) submit() }),
        modifier = Modifier.fillMaxWidth()
    )
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("recent" to "Новые", "popular" to "Популярные", "name" to "По имени").forEach { (sort, label) ->
            FilterChip(selected = model.catalogSort == sort, enabled = !model.loading, onClick = {
                model.catalogSort = sort
                submit()
            }, label = { Text(label) })
        }
    }
    var filtersExpanded by remember { mutableStateOf(false) }
    TextButton(onClick = { filtersExpanded = !filtersExpanded }) {
        Text(if (filtersExpanded) "Скрыть фильтры" else "Категория и тег")
    }
    if (filtersExpanded) NovaCard {
        val categories = model.shelves.filter { it.key.startsWith("genre:") }.map { it.title }.distinct()
        if (categories.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            categories.forEach { category ->
                FilterChip(selected = model.catalogCategory == category, enabled = !model.loading,
                    onClick = { model.catalogCategory = if (model.catalogCategory == category) "" else category },
                    label = { Text(category) })
            }
        }
        OutlinedTextField(value = model.catalogCategory, onValueChange = { model.catalogCategory = it.take(50) },
            label = { Text("Категория") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(value = model.catalogTag, onValueChange = { model.catalogTag = it.take(40) },
            label = { Text("Тег") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Button(onClick = submit, enabled = !model.loading, modifier = Modifier.fillMaxWidth()) { Text("Найти персонажей") }
        TextButton(onClick = {
            model.catalogQuery = ""
            model.catalogCategory = ""
            model.catalogTag = ""
            model.catalogSort = "recent"
            submit()
        }, enabled = !model.loading) { Text("Сбросить фильтры") }
    }
    if (model.matureSection && model.showMature) {
        Text("Взрослые вымышленные персонажи и романтические истории без откровенного контента.",
            color = NovaDesign.Muted, style = MaterialTheme.typography.bodySmall)
        MatureStudioSection(model)
    }
}

@Composable
private fun CharacterCard(model: NovaViewModel, character: NovaCharacter) {
    Card(onClick = { model.showCharacterProfile(character.id) }, shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, NovaDesign.Border),
        colors = CardDefaults.cardColors(containerColor = NovaDesign.Surface), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            NovaPortrait(character.id, character.name, Modifier.width(108.dp).height(178.dp).clip(RoundedCornerShape(18.dp)), character.avatarUrl)
            Column(Modifier.weight(1f).heightIn(min = 178.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NovaEyebrow(character.category)
                    if (character.mature) MatureBadge()
                }
                Text(character.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(character.biography?.takeIf { it.isNotBlank() } ?: character.greeting, style = MaterialTheme.typography.bodySmall,
                    color = NovaDesign.Muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                // Stacked, never side by side: on narrow phones a squeezed button wrapped letter by letter.
                Button(onClick = { model.startChat(character) }, enabled = !model.loading, modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Text("Начать AI-чат", fontSize = 13.sp, maxLines = 1)
                }
                TextButton(onClick = { model.showCharacterProfile(character.id) }, enabled = !model.loading,
                    modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) { Text("Полный профиль", fontSize = 12.sp, maxLines = 1) }
            }
        }
    }
}
