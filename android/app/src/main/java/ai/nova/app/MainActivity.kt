package ai.nova.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.Image
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import coil.compose.AsyncImage
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

private val Purple = Color(0xFFAE87FF)
private val Cyan = Color(0xFF49E3D1)
private val Background = Color(0xFF0D1019)
private val CardSurface = Color(0xFF1A1E2A)
private val NovaColors = darkColorScheme(
    primary = Purple, secondary = Cyan, background = Background,
    surface = CardSurface, onPrimary = Color(0xFF170C32), onBackground = Color(0xFFF5F1FF),
    onSurface = Color(0xFFF5F1FF)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = NovaColors) {
                val model: NovaViewModel = viewModel()
                NovaApp(model)
            }
        }
    }
}

@Composable
private fun NovaApp(model: NovaViewModel) {
    if (model.accessToken == null) {
        LoginScreen(model)
    } else {
        Scaffold(
            containerColor = Background,
            topBar = {
                Surface(color = Background) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("NOVA", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Purple)
                        Text("AI", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Cyan)
                    }
                }
            },
            bottomBar = {
                NavigationBar(containerColor = CardSurface) {
                    val items = listOf(
                        NovaTab.HOME, NovaTab.CATALOG, NovaTab.CHATS,
                        NovaTab.CREATE, NovaTab.WALLET, NovaTab.ACCOUNT
                    )
                    items.forEach { item ->
                        NavigationBarItem(
                            selected = model.activeTab == item,
                            onClick = { model.changeTab(item) },
                            icon = {
                                Icon(
                                    imageVector = when (item) {
                                        NovaTab.HOME -> Icons.Default.Home
                                        NovaTab.CATALOG -> Icons.Default.Explore
                                        NovaTab.CHATS -> Icons.Default.ChatBubbleOutline
                                        NovaTab.CREATE -> Icons.Default.AddCircle
                                        NovaTab.WALLET -> Icons.Default.AccountBalanceWallet
                                        NovaTab.ACCOUNT -> Icons.Default.AccountCircle
                                    },
                                    contentDescription = null
                                )
                            },
                            label = {
                                Text(when (item) {
                                    NovaTab.HOME -> "Главная"
                                    NovaTab.CATALOG -> "Каталог"
                                    NovaTab.CHATS -> "Чаты"
                                    NovaTab.CREATE -> "Создать"
                                    NovaTab.WALLET -> "Токены"
                                    NovaTab.ACCOUNT -> "Профиль"
                                }, maxLines = 1)
                            }
                        )
                    }
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StatusMessage(model)
                when (model.activeTab) {
                    NovaTab.HOME -> HomeScreen(model)
                    NovaTab.CATALOG -> CatalogScreen(model)
                    NovaTab.CHATS -> ChatsScreen(model)
                    NovaTab.CREATE -> CreateScreen(model)
                    NovaTab.WALLET -> WalletScreen(model)
                    NovaTab.ACCOUNT -> AccountScreen(model)
                }
            }
        }
    }
}

@Composable
private fun LoginScreen(model: NovaViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 26.dp, vertical = 54.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text("NOVA", fontWeight = FontWeight.Black, fontSize = 46.sp, color = Purple)
        Text("AI", fontWeight = FontWeight.Bold, fontSize = 34.sp, color = Cyan)
        Text("Вселенные начинаются с идеи.", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Создавай персонажей, публикуй истории и исследуй новые миры.",
            color = Color.LightGray
        )
        Spacer(Modifier.height(8.dp))
        NovaCard {
            Text("Подключение к серверу", color = Cyan, fontWeight = FontWeight.SemiBold)
            Text(
                "Для входа нужен подключённый сервис NOVA AI.",
                style = MaterialTheme.typography.bodySmall, color = Color.LightGray
            )
            OutlinedTextField(
                value = model.serverUrl,
                onValueChange = { model.serverUrl = it },
                label = { Text("Адрес сервиса") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = model.email,
                onValueChange = { model.email = it },
                label = { Text("Email") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = model.password,
                onValueChange = { model.password = it },
                label = { Text("Пароль (от 10 символов)") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true
            )
            Row(verticalAlignment=Alignment.CenterVertically) {
                Checkbox(checked=model.adultConfirmed,onCheckedChange={model.adultConfirmed=it})
                Text("Мне исполнилось 18 лет. Персонажи вымышленные.",style=MaterialTheme.typography.bodySmall)
            }
            Text("Сообщения отправляются серверу NOVA и настроенным AI-провайдерам. Не указывай конфиденциальные данные.",style=MaterialTheme.typography.bodySmall)
            Button(
                onClick = { model.signIn(false) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !model.loading
            ) { Text("Войти") }
            OutlinedButton(
                onClick = { model.signIn(true) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !model.loading
            ) { Text("Создать аккаунт") }
        }
        StatusMessage(model)
        GuestCastPreview()
    }
}

private val showcaseNames = listOf(
    "Аврора Вейл", "Лея Винтер", "Мира Соль", "Ника Блэйз",
    "София Рейн", "Элина Фрост", "Кай Номад", "Леон Арден",
    "Арсен Вольт", "Даниэль Рэй", "Рэн Блэквуд", "Тео Картер"
)

@Composable
private fun GuestCastPreview() {
    val context = LocalContext.current
    Text("Знакомься с персонажами", fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Text(
        "Бесплатный просмотр героев. Для настоящего AI-чата потребуется подключённый сервер.",
        color = Color.LightGray, style = MaterialTheme.typography.bodySmall
    )
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        showcaseNames.forEachIndexed { index, name ->
            val id = "00000000-0000-4000-8000-" +
                (index + 101).toString().padStart(12, '0')
            val image = remember(index, context) { NovaCastPortraits.find(context, id) }
            Card(
                modifier = Modifier.width(128.dp),
                colors = CardDefaults.cardColors(containerColor = CardSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(9.dp)) {
                    if (image != null) {
                        Image(
                            bitmap = image,
                            contentDescription = "Портрет персонажа " + name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(110.dp).clip(RoundedCornerShape(12.dp))
                        )
                    }
                    Text(
                        name, maxLines = 2,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusMessage(model: NovaViewModel) {
    if (model.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    model.error?.let { message ->
        Text(message, color = Color(0xFFFFA6A6), style = MaterialTheme.typography.bodyMedium)
    }
    model.notification?.let { message ->
        Text(message, color = Cyan, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun NovaCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Color.LightGray)
    }
}

@Composable
private fun HomeScreen(model: NovaViewModel) {
    SectionHeader("Твоя вселенная", "AI-персонажи и новые истории в одном месте")
    NovaCard {
        Text("NOVA AI", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Purple)
        Text("Открой каталог персонажей или создай своего героя.")
        Button(
            onClick = { model.changeTab(NovaTab.CATALOG) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Исследовать каталог") }
        OutlinedButton(
            onClick = { model.changeTab(NovaTab.CREATE) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Создать персонажа") }
        OutlinedButton(
            onClick = { model.changeTab(NovaTab.CHATS) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Мои переписки") }
    }
    NovaCard {
        Text("Что уже доступно", color = Cyan, fontWeight = FontWeight.Bold)
        Text("Регистрация, вход, создание персонажей, каталог и кошелёк через API.")
        Text(
            "AI-чат через серверный провайдер доступен после настройки API. Генерация изображений и покупки токенов пока разрабатываются.",
            style = MaterialTheme.typography.bodySmall, color = Color.LightGray
        )
    }
}

@Composable
private fun CatalogScreen(model: NovaViewModel) {
    SectionHeader("Каталог", "Персонажи, прошедшие модерацию")
    if (model.showMature) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !model.matureSection, onClick = { model.selectMatureSection(false) }, label = { Text("Для всех") })
        FilterChip(selected = model.matureSection, onClick = { model.selectMatureSection(true) }, label = { Text("18+ Mature") })
    }
    if (model.matureSection) Text("Раздел 18+: романтика и взрослые драмы с совершеннолетними вымышленными персонажами. Откровенный контент запрещён.",
        color = Color(0xFFFFB4A9), style = MaterialTheme.typography.bodySmall)
    NovaCard {
        OutlinedTextField(
            value = model.catalogQuery,
            onValueChange = { model.catalogQuery = it },
            label = { Text("Имя, биография или характер") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = model.catalogCategory,
            onValueChange = { model.catalogCategory = it },
            label = { Text("Категория (необязательно)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = model.catalogTag,
            onValueChange = { model.catalogTag = it },
            label = { Text("Тег (необязательно)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Text("Сортировка", color = Cyan, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = model.catalogSort == "recent",
                onClick = { model.catalogSort = "recent" },
                label = { Text("Новые") }
            )
            FilterChip(
                selected = model.catalogSort == "name",
                onClick = { model.catalogSort = "name" },
                label = { Text("По имени") }
            )
        }
        Button(
            onClick = { model.refreshCatalog() },
            enabled = !model.loading,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Найти персонажей") }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick={model.loadFavorites()},enabled=!model.loading){Text("Моё избранное")}
        OutlinedButton(onClick={model.refreshCatalog()},enabled=!model.loading){Text("Все персонажи")}
        OutlinedButton(onClick={model.loadFollowing()},enabled=!model.loading){Text("Мои подписки")}
        OutlinedButton(onClick={model.catalogSort="popular";model.refreshCatalog()},enabled=!model.loading){Text("Популярные")}
    }
    Text("Для группового сюжета выбери до 4 дополнительных участников, затем начни чат с главным героем.",style=MaterialTheme.typography.bodySmall)
    if (model.catalog.isEmpty()) {
        NovaCard { Text("Опубликованных персонажей пока нет.") }
    }
    model.profileCharacter?.let { c ->
        AlertDialog(onDismissRequest={model.profileCharacter=null},title={Text(c.name)},
            text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
                Text("Оригинальный вымышленный взрослый персонаж",color=Cyan)
                listOf("Биография" to c.biography,"Характер" to c.personality,"Внешность" to c.appearance,
                    "Интересы" to c.interests,"Предыстория" to c.backstory,"Манера речи" to c.speechStyle,"Сценарий" to c.scenario)
                    .forEach{(label,value)->if(!value.isNullOrBlank()){Text(label,fontWeight=FontWeight.Bold,color=Purple);Text(value)}}
                Text(c.greeting)
            }},confirmButton={TextButton(onClick={model.profileCharacter=null}){Text("Закрыть профиль")}})
    }
    val context = LocalContext.current
    model.catalog.forEach { character ->
        val bundledPortrait = remember(character.id, context) {
            NovaCastPortraits.find(context, character.id)
        }
        NovaCard {
            if (!character.avatarUrl.isNullOrBlank()) {
                AsyncImage(
                    model = character.avatarUrl,
                    contentDescription = "Портрет персонажа " + character.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(240.dp)
                        .clip(RoundedCornerShape(14.dp))
                )
            } else if (bundledPortrait != null) {
                Image(
                    bitmap = bundledPortrait,
                    contentDescription = "Портрет персонажа " + character.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(16.dp))
                )
            } else {
                Text(
                    "NOVA STUDIO · " + character.category.uppercase(),
                    color = Cyan,
                    style = MaterialTheme.typography.labelLarge
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(character.name, color = Purple, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                if (character.mature) MatureBadge()
            }
            Text(character.category.uppercase(), color = Cyan, fontSize = 12.sp)
            TextButton(onClick={model.showCharacterProfile(character.id)},enabled=!model.loading){Text("Полный профиль")}
            character.ownerId?.let {creator->Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick={model.follow(creator,true)},enabled=!model.loading){Text("Подписаться на автора")}
                TextButton(onClick={model.follow(creator,false)},enabled=!model.loading){Text("Отписаться")}
            }}
            character.publication?.let {publication->
                Row {TextButton(onClick={model.like(publication.id)},enabled=!model.loading){Text("Нравится · ${publication.likesCount}")};TextButton(onClick={model.openComments(publication.id)},enabled=!model.loading){Text("Обсуждение")}}
                if(model.selectedPublication==publication.id){
                    model.comments.forEach {c->Text(c.content+(if(c.status=="PENDING_REVIEW") " · На модерации" else ""))}
                    OutlinedTextField(value=model.commentInput,onValueChange={model.commentInput=it},label={Text("Комментарий")},modifier=Modifier.fillMaxWidth())
                    Button(onClick={model.sendComment()},enabled=!model.loading&&model.commentInput.isNotBlank()){Text("Отправить комментарий")}
                }
            }
            character.biography?.takeIf { it.isNotBlank() }?.let { bio ->
                Text(bio, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
            }
            Text(character.greeting, maxLines = 4, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment=Alignment.CenterVertically) {
                Checkbox(checked=character.id in model.groupIds,onCheckedChange={checked->
                    model.groupIds=if(!checked)model.groupIds-character.id else if(model.groupIds.size<4)model.groupIds+character.id else model.groupIds
                })
                Text("Участник групповой истории")
            }
            TextButton(onClick={model.cloneCharacter(character.id)},enabled=!model.loading){Text("Создать свою версию")}
            if(model.favorites.any {it.id==character.id})TextButton(onClick={model.removeFavorite(character.id)},enabled=!model.loading){Text("Убрать из избранного")}
            Button(
                onClick = { model.startChat(character) },
                enabled = !model.loading,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Начать AI-чат") }
            OutlinedButton(onClick = { model.addFavorite(character.id) }, enabled = !model.loading) {
                Text("В избранное")
            }
        }
    }
}

@Composable
private fun ChatsScreen(model: NovaViewModel) {
    val active = model.activeChat
    if (active == null) {
        SectionHeader("Мои чаты", "Разговоры с AI-персонажами хранятся на сервере")
        OutlinedTextField(value=model.chatSearch,onValueChange={model.chatSearch=it},label={Text("Поиск в переписках")},modifier=Modifier.fillMaxWidth())
        val status = model.aiStatus
        NovaCard {
            if (status == null) {
                Text("Проверяем доступность общения")
            } else if (status.configured) {
                Text(
                    "AI: " + status.provider + " · " + status.model,
                    color = Cyan, style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Обычный чат: без лимита сообщений в день и без списания NOVA Tokens.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Premium: " + status.premiumPriceTokens + " NOVA Tokens за ответ · "
                        + if (status.premiumConfigured) (status.premiumModel ?: "Улучшенная модель")
                          else "Модель пока не подключена",
                    style = MaterialTheme.typography.bodySmall, color = Color.LightGray
                )
            } else {
                Text(
                    "Общение временно недоступно. История сохранена.",
                    color = Color(0xFFFFB79A),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            OutlinedButton(
                onClick = { model.refreshChats() },
                enabled = !model.loading,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Обновить чаты") }
        }
        if (model.chats.isEmpty()) {
            NovaCard {
                Text("Пока нет переписок. Открой каталог и выбери героя.")
                Button(onClick = { model.changeTab(NovaTab.CATALOG) }) {
                    Text("Открыть персонажей")
                }
            }
        }
        model.chats.forEach { chat ->
            NovaCard {
                Text(chat.characterName ?: chat.title ?: "AI-персонаж",
                    fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Purple)
                Button(
                    onClick = { model.openChat(chat) },
                    enabled = !model.loading,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Продолжить разговор") }
            }
        }
    } else {
        SectionHeader(active.characterName ?: active.title ?: "AI-чат", "Художественный AI-персонаж NOVA")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { model.closeChat() }, enabled = !model.loading) {
                Text("Назад")
            }
            OutlinedButton(onClick = { model.deleteActiveChat() }, enabled = !model.loading) {
                Text("Удалить чат")
            }
        }
        if (model.chatMessages.isEmpty()) {
            NovaCard {
                Text("Напиши первое сообщение — персонаж ответит через настоящий AI-провайдер.")
                Text(
                    "Твои сообщения и воспоминания доступны только в твоём аккаунте.",
                    color = Color.LightGray, style = MaterialTheme.typography.bodySmall
                )
            }
        }
        StoryControls(model)
        if(model.nextBefore!=null)OutlinedButton(onClick={model.loadOlder()},enabled=!model.loading){Text("Более ранние сообщения")}
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = model.inChatQuery, onValueChange = { model.inChatQuery = it }, singleLine = true,
                label = { Text("Поиск в этой переписке") }, modifier = Modifier.weight(1f))
            TextButton(onClick = { model.searchInChat() }, enabled = !model.loading) { Text("Найти") }
        }
        model.inChatResults?.let { found ->
            NovaCard {
                Text(if (found.isEmpty()) "Ничего не найдено" else "Найдено: " + found.size, color = Cyan)
                found.forEach { Text((if (it.role == "user") "Ты: " else "AI: ") + it.content.take(160), style = MaterialTheme.typography.bodySmall) }
            }
        }
        model.editingMessageId?.let {
            AlertDialog(onDismissRequest = { model.editingMessageId = null }, title = { Text("Изменить сообщение") },
                text = { OutlinedTextField(value = model.editingMessageText, onValueChange = { model.editingMessageText = it }, modifier = Modifier.fillMaxWidth()) },
                confirmButton = { TextButton(onClick = { model.saveEditedMessage() }, enabled = !model.loading) { Text("Сохранить") } },
                dismissButton = { TextButton(onClick = { model.editingMessageId = null }) { Text("Отмена") } })
        }
        model.chatMessages.forEach { message ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (message.role == "user") Color(0xFF29213C)
                        else CardSurface
                ),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        if (message.role == "user") "Ты" else "AI-персонаж",
                        color = if (message.role == "user") Purple else Cyan,
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(message.content, style = MaterialTheme.typography.bodyLarge)
                    if (!message.alternatives.isNullOrEmpty()) Text("Версия " + (message.alternatives.size + 1) + " · прежние сохранены", style = MaterialTheme.typography.labelSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        SpeakButton(message.content)
                        TextButton(onClick={model.branch(message)},enabled=!model.loading){Text("Ответвить историю")}
                        if(message.role=="assistant"&&model.chatMessages.lastOrNull()?.id==message.id)
                            TextButton(onClick={model.regenerate(message)},enabled=!model.loading){
                                Text(if(model.premiumChatSelected)"Другой ответ · "+(model.aiStatus?.premiumPriceTokens?:5)+" ◈" else "Другой ответ")
                            }
                        if(message.role=="assistant")TextButton(onClick={model.reportMessage(message.id)},enabled=!model.loading){Text("Пожаловаться")}
                        if(message.role=="user")TextButton(onClick={model.startEditMessage(message)},enabled=!model.loading){Text("Изменить")}
                        var confirmDelete by remember { mutableStateOf(false) }
                        TextButton(onClick={confirmDelete=true},enabled=!model.loading){Text("Удалить")}
                        if(confirmDelete)AlertDialog(onDismissRequest={confirmDelete=false},title={Text("Удалить сообщение?")},
                            text={Text(if(message.role=="user")"Сообщение и ответ персонажа на него будут удалены." else "Ответ персонажа будет удалён.")},
                            confirmButton={TextButton(onClick={confirmDelete=false;model.deleteMessage(message)}){Text("Удалить")}},
                            dismissButton={TextButton(onClick={confirmDelete=false}){Text("Отмена")}})
                    }
                }
            }
        }
        NovaCard {
            val status = model.aiStatus
            Text("Режим AI-чата", fontWeight = FontWeight.SemiBold, color = Cyan)
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                FilterChip(
                    selected = !model.premiumChatSelected,
                    onClick = { model.premiumChatSelected = false },
                    label = { Text("Обычный · бесплатно") }
                )
                FilterChip(
                    selected = model.premiumChatSelected,
                    onClick = { model.premiumChatSelected = true },
                    enabled = status?.premiumConfigured == true,
                    label = { Text("Premium · " + (status?.premiumPriceTokens ?: 5) + " ◈") }
                )
            }
            if (model.premiumChatSelected) {
                Text(
                    "Более мощная модель, длинные ответы и увеличенный контекст. "
                        + (status?.premiumPriceTokens ?: 5) + " токенов спишутся только за успешный ответ.",
                    style = MaterialTheme.typography.bodySmall, color = Color.LightGray
                )
                Text(
                    "Баланс: " + (status?.tokenBalance ?: 0) + " NOVA Tokens",
                    style = MaterialTheme.typography.labelMedium
                )
            } else {
                Text(
                    "Без ограничений по числу сообщений и без оплаты. Возможны технические задержки AI-провайдера.",
                    style = MaterialTheme.typography.bodySmall, color = Color.LightGray
                )
            }
            model.streamingText?.let { partial ->
                Text("AI-персонаж", color = Cyan, style = MaterialTheme.typography.labelMedium)
                Text(if (partial.isEmpty()) "печатает…" else partial, style = MaterialTheme.typography.bodyLarge)
                OutlinedButton(onClick = { model.stopReply() }, modifier = Modifier.fillMaxWidth()) { Text("Остановить ответ") }
            }
            OutlinedTextField(
                value = model.chatInput,
                onValueChange = { model.updateChatInput(it) },
                label = { Text("Напиши сообщение") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 5
            )
            DictationButton(model)
            Button(
                onClick = { model.sendChatMessage() },
                enabled = !model.loading && model.chatInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text(
                if (model.loading) "AI отвечает..."
                else if (model.premiumChatSelected) "Отправить · " + (model.aiStatus?.premiumPriceTokens ?: 5) + " ◈"
                else "Отправить бесплатно"
            ) }
            Text(
                "Ответ создаёт AI, а не реальный человек. Доступность зависит от лимитов провайдера.",
                style = MaterialTheme.typography.bodySmall, color = Color.LightGray
            )
        }
    }
}

@Composable
private fun CreateScreen(model: NovaViewModel) {
    SectionHeader("Создать героя", "Персонаж сохраняется в твоей библиотеке")
    NovaCard {
        OutlinedTextField(
            value = model.draftName, onValueChange = { model.draftName = it },
            label = { Text("Имя персонажа") }, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = model.draftCategory, onValueChange = { model.draftCategory = it },
            label = { Text("Категория") }, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = model.draftGreeting, onValueChange = { model.draftGreeting = it },
            label = { Text("Первое сообщение персонажа") },
            minLines = 3, modifier = Modifier.fillMaxWidth()
        )
        CreatorFields(model)
        Button(
            onClick = { model.createCharacter() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !model.loading
        ) { Text("Сохранить персонажа") }
    }
    Text("Мои персонажи", style = MaterialTheme.typography.titleLarge)
    OutlinedButton(onClick = { model.refreshOwned() }, enabled = !model.loading) {
        Text("Обновить")
    }
    model.owned.forEach { character ->
        NovaCard {
            Text(character.name, fontWeight = FontWeight.Bold, color = Purple)
            Text(character.greeting)
            Button(onClick={model.startChat(character)},enabled=!model.loading){Text("Общаться")}
            OutlinedButton(onClick={model.editCharacter(character)},enabled=!model.loading){Text("Редактировать")}
            OutlinedButton(onClick={model.hideCharacter(character.id)},enabled=!model.loading){Text("Скрыть из каталога")}
            OutlinedButton(onClick={model.publishCharacter(character.id)},enabled=!model.loading){Text("Отправить на модерацию")}
        }
    }
}

@Composable
private fun WalletScreen(model: NovaViewModel) {
    SectionHeader("NOVA Tokens", "Серверный баланс виртуальных токенов")
    NovaCard {
        Text("Баланс", color = Color.LightGray)
        Text(
            (model.wallet?.total ?: 0).toString(),
            fontSize = 46.sp, fontWeight = FontWeight.Black, color = Cyan
        )
        Text("Куплено: " + (model.wallet?.purchased ?: 0))
        Text("Реклама: " + (model.wallet?.adReward ?: 0))
        Text("Бонусы: " + (model.wallet?.bonus ?: 0))
        OutlinedButton(onClick = { model.refreshWallet() }, enabled = !model.loading) {
            Text("Обновить баланс")
        }
    }
    RewardCards(model)
    NovaCard {
        Text("История операций", fontWeight = FontWeight.Bold)
        if (model.transactions.isEmpty()) Text("Пока нет операций.")
        model.transactions.forEach { tx ->
            Text(tx.type + " · " + tx.amount + " · " + tx.source, fontSize = 12.sp)
        }
    }
    MonetizationControls(model)
    Text(
        "Токены начисляются после серверной проверки Google Play или AdMob.",
        color = Color.LightGray, style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun AccountScreen(model: NovaViewModel) {
    SectionHeader("Профиль", "Управление подключением и аккаунтом")
    NovaCard {
        Text(model.email, color = Purple)
        Text("Сервер: " + model.serverUrl, color = Color.LightGray)
        Text(
            "Сессия и неотправленный текст зашифрованы на устройстве. Чаты и память хранятся в твоём аккаунте.",
            color = Color.LightGray, style = MaterialTheme.typography.bodySmall
        )
        MatureSettings(model)
        OutlinedButton(onClick = { model.logout() }) { Text("Выйти из аккаунта") }
        OutlinedButton(onClick={model.exportAccount()},enabled=!model.loading){Text("Экспортировать мои данные")}
        val context=LocalContext.current
        model.exportText?.let { data->TextButton(onClick={context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {type="text/plain";putExtra(Intent.EXTRA_TEXT,data)},"Экспорт NOVA"))}){Text("Поделиться экспортом")} }
        var deleting by remember {mutableStateOf(false)}
        OutlinedButton(onClick={deleting=true}){Text("Удалить аккаунт")}
        if(deleting)AlertDialog(onDismissRequest={deleting=false},title={Text("Удалить аккаунт и истории?")},text={Text("Чаты, память и персонажи будут удалены. Учёт покупок сохраняется для обработки возвратов.")},confirmButton={TextButton(onClick={deleting=false;model.deleteAccount()}){Text("Удалить навсегда")}},dismissButton={TextButton(onClick={deleting=false}){Text("Отмена")}})
        Text("Уведомления",style=MaterialTheme.typography.titleMedium)
        model.notifications.forEach {n-> Text(n.title+" · "+n.body);if(n.readAt==null)TextButton(onClick={model.readNotification(n.id)}){Text("Прочитано")} }
        OutlinedTextField(value=model.supportText,onValueChange={model.supportText=it},label={Text("Обращение в поддержку")},modifier=Modifier.fillMaxWidth())
        Button(onClick={model.sendSupport()},enabled=!model.loading&&model.supportText.length>=5){Text("Отправить обращение")}
    }
}

@Composable
private fun RewardCards(model: NovaViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    model.dailyBonus?.takeIf { it.enabled }?.let { bonus ->
        NovaCard {
            Text("Ежедневный бонус", fontWeight = FontWeight.Bold, color = Cyan)
            if (bonus.claimedToday) Text("Получено сегодня. Следующий бонус — завтра.", color = Color.LightGray)
            else Button(onClick = { model.claimDailyBonus() }, enabled = !model.loading, modifier = Modifier.fillMaxWidth()) {
                Text("Забрать +" + bonus.amount + " ◈")
            }
        }
    }
    model.referral?.let { ref ->
        NovaCard {
            Text("Пригласи друга", fontWeight = FontWeight.Bold, color = Cyan)
            if (ref.bonusTokens > 0) Text("Вам обоим по " + ref.bonusTokens + " ◈, когда друг отправит " + ref.requiredTurns + " сообщения в чате.",
                style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
            ref.code?.let { code ->
                Text(code, fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                OutlinedButton(onClick = {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_TEXT, "Присоединяйся к NOVA AI! Мой код приглашения: " + code)
                    runCatching { context.startActivity(android.content.Intent.createChooser(send, "Поделиться кодом")) }
                }) { Text("Поделиться кодом") }
            }
            Text("Приглашено: " + ref.invited + " · бонус получен за: " + ref.rewarded)
            ref.myInvite?.let { mine ->
                Text(if (mine.rewarded) "Бонус за твоё приглашение получен"
                    else "Твой код друга принят: " + mine.turns + " из " + ref.requiredTurns + " сообщений", color = Color.LightGray)
            }
            if (ref.canApplyCode) {
                OutlinedTextField(value = model.referralInput, onValueChange = { model.referralInput = it.take(8) },
                    label = { Text("Код друга") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { model.applyReferralCode() }, enabled = !model.loading && model.referralInput.length == 8) {
                    Text("Применить код")
                }
            }
        }
    }
}

@Composable
fun MatureBadge() {
    Surface(color = Color(0xFFB3261E), shape = RoundedCornerShape(6.dp)) {
        Text("18+", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
private fun MatureSettings(model: NovaViewModel) {
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { model.loadContentSettings() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Раздел 18+ (Mature)", fontWeight = FontWeight.SemiBold)
            Text("Выключен по умолчанию. Романтика без откровенного контента.", style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
        }
        Switch(checked = model.showMature, enabled = !model.loading,
            onCheckedChange = { if (it) confirm = true else model.updateShowMature(false) })
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Только для взрослых") },
        text = { Text("Раздел содержит романтические и взрослые сюжеты с вымышленными совершеннолетними персонажами. Откровенный сексуальный контент запрещён. Подтверди, что тебе исполнилось 18 лет.") },
        confirmButton = { TextButton(onClick = { confirm = false; model.updateShowMature(true) }) { Text("Мне есть 18") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Отмена") } }
    )
}
