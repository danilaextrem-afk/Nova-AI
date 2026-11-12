package ai.nova.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.ScrollState
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

private val Purple = NovaDesign.Lavender
private val Cyan = NovaDesign.Rose
private val Background = NovaDesign.Ink
private val CardSurface = NovaDesign.Surface
private val NovaColors = darkColorScheme(
    primary = Purple, secondary = Cyan, background = Background,
    surface = CardSurface, onPrimary = Color(0xFF170C32), onBackground = Color(0xFFF5F1FF),
    onSurface = Color(0xFFF5F1FF)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NovaLang.load(this)
        val linked = intent?.data?.takeIf { it.pathSegments.size == 2 && it.pathSegments[0] == "c" }?.lastPathSegment
            ?.takeIf { Regex("[0-9a-fA-F-]{36}").matches(it) }
        setContent {
            NovaTheme {
                val model: NovaViewModel = viewModel()
                LaunchedEffect(linked) { if (linked != null) model.pendingLinkCharacterId = linked }
                CompositionLocalProvider(LocalPortraitAccount provides model) { NovaApp(model) }
            }
        }
    }
}

@Composable
private fun NovaApp(model: NovaViewModel) {
    if (model.accessToken == null) { LoginScreen(model); return }
    val inChat = model.activeTab == NovaTab.CHATS && model.activeChat != null
    Scaffold(
        containerColor = Background,
        topBar = {
            if (!inChat) Surface(color = Background) {
                Row(
                    modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    NovaWordmark(compact = true)
                    TokenPill(model)
                }
            }
        },
        bottomBar = {
            if (!inChat) NavigationBar(containerColor = CardSurface, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp).clip(RoundedCornerShape(24.dp))) {
                // Five destinations; the wallet lives behind the balance pill in the top bar.
                listOf(NovaTab.HOME, NovaTab.CATALOG, NovaTab.CHATS, NovaTab.CREATE, NovaTab.ACCOUNT).forEach { item ->
                    NavigationBarItem(
                        selected = model.activeTab == item,
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = Color(0xFF3C2C53),
                            selectedIconColor = NovaDesign.Lavender,
                            selectedTextColor = NovaDesign.Lavender,
                            unselectedIconColor = NovaDesign.Muted,
                            unselectedTextColor = NovaDesign.Muted
                        ),
                        onClick = { model.changeTab(item) },
                        icon = {
                            Icon(
                                imageVector = when (item) {
                                    NovaTab.HOME -> Icons.Default.Home
                                    NovaTab.CATALOG -> Icons.Default.Explore
                                    NovaTab.CHATS -> Icons.Default.ChatBubbleOutline
                                    NovaTab.CREATE -> Icons.Default.AddCircle
                                    else -> Icons.Default.AccountCircle
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
                                else -> "Профиль"
                            }, maxLines = 1, fontSize = 11.sp)
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        BackHandler(enabled = !inChat && model.activeTab in setOf(NovaTab.WALLET, NovaTab.ART)) {
            model.changeTab(model.productReturnTab)
        }
        if (inChat) {
            Column(Modifier.fillMaxSize().padding(innerPadding).imePadding()) {
                StatusMessage(model)
                NovaConversation(model)
            }
        } else Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                // A new tab opens at its top, not at the previous tab's scroll position.
                .verticalScroll(remember(model.activeTab) { ScrollState(0) })
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StatusMessage(model, productUi = model.activeTab in setOf(NovaTab.HOME, NovaTab.CATALOG, NovaTab.WALLET, NovaTab.ACCOUNT))
            when (model.activeTab) {
                NovaTab.HOME -> HomeScreen(model)
                NovaTab.CATALOG -> CatalogScreen(model)
                NovaTab.CHATS -> ChatsScreen(model)
                NovaTab.CREATE -> CreateScreen(model)
                NovaTab.WALLET -> WalletScreen(model)
                NovaTab.ACCOUNT -> AccountScreen(model)
                NovaTab.ART -> ArtScreen(model)
            }
            Spacer(Modifier.height(8.dp))
            // Shown from any tab: profile and author pages, shared links (…/c/<id>), the personal feed.
            CharacterProfileSheet(model)
            AuthorDialog(model)
            LaunchedEffect(model.pendingLinkCharacterId, model.accessToken, model.loading) { model.openPendingLink() }
            LaunchedEffect(model.accessToken) { if (model.accessToken != null) model.refreshDiscover() }
        }
    }
}

@Composable
private fun TokenPill(model: NovaViewModel) {
    Surface(onClick = { model.changeTab(NovaTab.WALLET) }, shape = RoundedCornerShape(50), color = Color(0xFF2A2236),
        border = BorderStroke(1.dp, if (model.activeTab == NovaTab.WALLET) NovaDesign.Lavender else NovaDesign.Border)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(model.wallet?.let { "◈ ${it.total}" } ?: "◈", color = NovaDesign.Rose, fontWeight = FontWeight.SemiBold)
            Text("Токены", style = MaterialTheme.typography.labelMedium, color = NovaDesign.Muted)
        }
    }
}

/** RU / EN interface switch; the choice is remembered on this phone. */
@Composable
internal fun LanguageSwitch() {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Язык приложения", style = MaterialTheme.typography.labelMedium, color = NovaDesign.Muted)
        FilterChip(selected = NovaLang.code == "ru", onClick = { NovaLang.set(context, "ru") }, label = { Text("Русский") })
        FilterChip(selected = NovaLang.code == "en", onClick = { NovaLang.set(context, "en") }, label = { Text("English") })
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun LoginScreen(model: NovaViewModel) {
    val keyboard = LocalSoftwareKeyboardController.current
    var showPassword by remember { mutableStateOf(false) }
    // A free server sleeps after inactivity; tell people why the first sign-in takes a while.
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(model.loading) { slow = false; if (model.loading) { kotlinx.coroutines.delay(6000); slow = true } }
    Column(
        modifier = Modifier
            .fillMaxSize()
            // System bars and keyboard are outside the scroll viewport, so nothing scrolls under them.
            .imePadding().statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        LanguageSwitch()
        NovaWordmark()
        NovaEditorialHero("Ближе, чем\nвоображение.", "Персонажи. Разговоры. Твоя история.", eyebrow = "ДОБРО ПОЖАЛОВАТЬ")
        NovaCard {
            Text("Твоя история начинается здесь", style = MaterialTheme.typography.titleLarge)
            if (BuildConfig.NOVA_API_BASE_URL.isBlank()) {
                // Only builds without an embedded server address need manual connection settings.
                OutlinedTextField(value = model.serverUrl, onValueChange = { model.serverUrl = it },
                    label = { Text("Адрес сервиса") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
            OutlinedTextField(
                value = model.email, onValueChange = { model.email = it.trim(); model.emailError = null },
                label = { Text("Email") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                isError = model.emailError != null, supportingText = model.emailError?.let { e -> { Text(e) } },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Email)
            )
            OutlinedTextField(
                value = model.password, onValueChange = { model.password = it; model.passwordError = null },
                label = { Text("Пароль (от 10 символов)") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                isError = model.passwordError != null,
                supportingText = { Text(model.passwordError ?: "Символов: ${model.password.length} из минимум 10",
                    color = if (model.passwordError != null) MaterialTheme.colorScheme.error else NovaDesign.Muted) },
                visualTransformation = if (showPassword) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showPassword) "Скрыть пароль" else "Показать пароль")
                    }
                }
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = model.adultConfirmed, onCheckedChange = { model.adultConfirmed = it; model.adultError = null })
                Text("Мне исполнилось 18 лет. Персонажи вымышленные.", style = MaterialTheme.typography.bodySmall)
            }
            model.adultError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button(onClick = { keyboard?.hide(); model.signIn(false) }, modifier = Modifier.fillMaxWidth().height(52.dp), enabled = !model.loading) {
                if (model.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Войти")
            }
            OutlinedButton(onClick = { keyboard?.hide(); model.signIn(true) }, modifier = Modifier.fillMaxWidth().height(52.dp), enabled = !model.loading) {
                Text("Создать аккаунт")
            }
            // Errors appear right under the buttons and are scrolled into view, so they are never off-screen.
            val errorSpot = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
            LaunchedEffect(model.error, model.emailError, model.passwordError, model.adultError) {
                if (model.error != null || model.adultError != null) { kotlinx.coroutines.delay(150); errorSpot.bringIntoView() }
            }
            Column(Modifier.bringIntoViewRequester(errorSpot), verticalArrangement = Arrangement.spacedBy(8.dp)) { StatusMessage(model) }
            if (slow && model.loading) Text("Сервер просыпается после простоя — это может занять до минуты. Не закрывай приложение.",
                style = MaterialTheme.typography.bodySmall, color = NovaDesign.Rose)
            Text("Сообщения обрабатывают сервер NOVA и внешние AI-провайдеры. Не указывай конфиденциальные данные.",
                style = MaterialTheme.typography.bodySmall, color = NovaDesign.Muted)
        }
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
    NovaEyebrow("NOVA ORIGINALS")
    Text("Знакомься с персонажами",style=MaterialTheme.typography.headlineMedium)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        showcaseNames.forEachIndexed { index,name ->
            val id="00000000-0000-4000-8000-"+(index+101).toString().padStart(12,'0')
            Card(Modifier.width(164.dp),shape=RoundedCornerShape(22.dp),border=BorderStroke(1.dp,NovaDesign.Border)) {
                NovaPortrait(id,name,Modifier.fillMaxWidth().height(218.dp))
                Text(name,modifier=Modifier.padding(12.dp),maxLines=2,style=MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
internal fun StatusMessage(model: NovaViewModel, productUi: Boolean = false) {
    if (model.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)))
    model.error?.let { message ->
        Surface(color = Color(0xFF3A1F2A), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Color(0xFF6B3446)), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (productUi) productErrorMessage(message) else message, color = Color(0xFFFFC2CF), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { model.dismissMessages() }) { Text("OK") }
            }
        }
    }
    model.notification?.let { message ->
        LaunchedEffect(message) { kotlinx.coroutines.delay(4500); model.dismissNotice(message) }
        Surface(color = Color(0xFF242036), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, NovaDesign.Border), modifier = Modifier.fillMaxWidth()) {
            Text(message, color = NovaDesign.Lavender, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        }
    }
}

@Composable
fun NovaCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(26.dp),
        border = BorderStroke(1.dp,NovaDesign.Border),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.background(NovaDesign.Glow).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@Composable
internal fun SectionHeader(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        NovaEyebrow("NOVA / ТВОЁ ПРОСТРАНСТВО")
        Text(title, style=MaterialTheme.typography.headlineMedium)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Color.LightGray)
    }
}

@Composable
private fun ChatsScreen(model: NovaViewModel) {
    SectionHeader("Мои чаты", "Твои истории сохраняются в аккаунте")
    OutlinedTextField(value = model.chatSearch, onValueChange = { model.chatSearch = it }, placeholder = { Text("Поиск в переписках") },
        singleLine = true, shape = RoundedCornerShape(20.dp), leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = { TextButton(onClick = { model.refreshChats() }, enabled = !model.loading) { Text("Найти") } },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { model.refreshChats() }),
        modifier = Modifier.fillMaxWidth())
    if (model.aiStatus?.configured == false) Surface(color = Color(0xFF3A1F2A), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Text("Общение временно недоступно. История сохранена.", color = Color(0xFFFFC2CF), modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall)
    }
    if (model.chats.isEmpty() && !model.loading) NovaCard {
        Text("Пока нет переписок", style = MaterialTheme.typography.titleLarge)
        Text("Открой каталог, выбери героя и напиши первое сообщение.", color = NovaDesign.Muted)
        Button(onClick = { model.changeTab(NovaTab.CATALOG) }) { Text("Открыть персонажей") }
    }
    model.chats.forEach { chat ->
        Card(onClick = { if (!model.loading) model.openChat(chat) }, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, NovaDesign.Border),
            colors = CardDefaults.cardColors(containerColor = CardSurface), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                NovaPortrait(chat.characterId, chat.characterName ?: chat.title ?: "NOVA", Modifier.size(58.dp).clip(CircleShape), chat.characterAvatarUrl ?: model.chatPortraitUrl(chat.characterId))
                Column(Modifier.weight(1f)) {
                    Text((if (!chat.participantIds.isNullOrEmpty()) chat.title else null) ?: chat.characterName ?: chat.title ?: "AI-персонаж", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if ((chat.participantIds ?: emptyList()).isNotEmpty()) "Групповая история" else "Продолжить разговор",
                        style = MaterialTheme.typography.bodySmall, color = NovaDesign.Muted)
                }
                Text("›", style = MaterialTheme.typography.headlineMedium, color = NovaDesign.Muted)
            }
        }
    }
}

@Composable
private fun CreateScreen(model: NovaViewModel) {
    CharacterEditor(model)
    CreatorStatsCard(model)
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
            OutlinedButton(onClick={model.showCharacterProfile(character.id)},enabled=!model.loading){Text("Профиль и сценарии")}
            OutlinedButton(onClick={model.hideCharacter(character.id)},enabled=!model.loading){Text("Скрыть из каталога")}
            OutlinedButton(onClick={model.publishCharacter(character.id)},enabled=!model.loading){Text("Отправить на модерацию")}
        }
    }
}

@Composable
fun MatureBadge() {
    Surface(color = Color(0xFF493043), shape = RoundedCornerShape(8.dp)) {
        Text("18+", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}
