package ai.nova.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

private val Purple = Color(0xFFAE87FF)
private val Cyan = Color(0xFF49E3D1)
private val Background = Color(0xFF0D1019)
private val Surface = Color(0xFF1A1E2A)
private val NovaColors = darkColorScheme(
    primary = Purple, secondary = Cyan, background = Background,
    surface = Surface, onPrimary = Color(0xFF170C32), onBackground = Color(0xFFF5F1FF),
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
                NavigationBar(containerColor = Surface) {
                    val items = listOf(
                        NovaTab.HOME, NovaTab.CATALOG, NovaTab.CREATE,
                        NovaTab.WALLET, NovaTab.ACCOUNT
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
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StatusMessage(model)
                when (model.activeTab) {
                    NovaTab.HOME -> HomeScreen(model)
                    NovaTab.CATALOG -> CatalogScreen(model)
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
                "Укажи адрес развёрнутого сервера NOVA AI по HTTPS. Встроенного демо-сервера нет.",
                style = MaterialTheme.typography.bodySmall, color = Color.LightGray
            )
            OutlinedTextField(
                value = model.serverUrl,
                onValueChange = { model.serverUrl = it },
                label = { Text("Backend URL") },
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
        colors = CardDefaults.cardColors(containerColor = Surface),
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
    }
    NovaCard {
        Text("Что уже доступно", color = Cyan, fontWeight = FontWeight.Bold)
        Text("Регистрация, вход, создание персонажей, каталог и кошелёк через API.")
        Text(
            "AI-чат, генерация изображений, платежи и награды за рекламу ещё разрабатываются.",
            style = MaterialTheme.typography.bodySmall, color = Color.LightGray
        )
    }
}

@Composable
private fun CatalogScreen(model: NovaViewModel) {
    SectionHeader("Каталог", "Персонажи, прошедшие модерацию")
    OutlinedButton(onClick = { model.refreshCatalog() }, enabled = !model.loading) {
        Text("Обновить каталог")
    }
    if (model.catalog.isEmpty()) {
        NovaCard { Text("Опубликованных персонажей пока нет.") }
    }
    model.catalog.forEach { character ->
        NovaCard {
            Text(character.name, color = Purple, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(character.category.uppercase(), color = Cyan, fontSize = 12.sp)
            Text(character.greeting, maxLines = 4, overflow = TextOverflow.Ellipsis)
            Button(onClick = { model.addFavorite(character.id) }, enabled = !model.loading) {
                Text("В избранное")
            }
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
    NovaCard {
        Text("История операций", fontWeight = FontWeight.Bold)
        if (model.transactions.isEmpty()) Text("Пока нет операций.")
        model.transactions.forEach { tx ->
            Text(tx.type + " · " + tx.amount + " · " + tx.source, fontSize = 12.sp)
        }
    }
    Text(
        "Покупка токенов и награды AdMob будут доступны только после подключения и проверки Google Play.",
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
            "Доступ действует до истечения серверной сессии. Автопродление входа ещё не подключено.",
            color = Color.LightGray, style = MaterialTheme.typography.bodySmall
        )
        OutlinedButton(onClick = { model.logout() }) { Text("Выйти из аккаунта") }
    }
}
