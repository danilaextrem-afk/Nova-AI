package ai.nova.app

import android.content.Context
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit

/**
 * Interface language. The app is written in Russian; English is a translation layer applied to every
 * on-screen [Text] in this package (this function shadows material3.Text for String labels).
 * Characters, chats and other user content are never rewritten, except exact UI phrases.
 */
object NovaLang {
    var code by mutableStateOf("ru")
        private set
    fun load(context: Context) { code = context.getSharedPreferences("nova_ui", Context.MODE_PRIVATE).getString("lang", "ru") ?: "ru" }
    fun set(context: Context, value: String) {
        code = if (value == "en") "en" else "ru"
        context.getSharedPreferences("nova_ui", Context.MODE_PRIVATE).edit().putString("lang", code).apply()
    }
}

fun tr(text: String): String {
    if (NovaLang.code != "en" || text.isEmpty()) return text
    EN[text]?.let { return it }
    for ((re, f) in EN_PATTERNS) re.matchEntire(text)?.let { return f(it.groupValues) }
    for ((ru, en) in EN_PREFIXES) if (text.startsWith(ru)) return en + text.substring(ru.length)
    return text
}

@Composable
fun Text(
    text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified, fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null, fontWeight: FontWeight? = null, fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified, textDecoration: TextDecoration? = null, textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified, overflow: TextOverflow = TextOverflow.Clip, softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE, minLines: Int = 1, onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
) = androidx.compose.material3.Text(text = tr(text), modifier = modifier, color = color, fontSize = fontSize, fontStyle = fontStyle,
    fontWeight = fontWeight, fontFamily = fontFamily, letterSpacing = letterSpacing, textDecoration = textDecoration, textAlign = textAlign,
    lineHeight = lineHeight, overflow = overflow, softWrap = softWrap, maxLines = maxLines, minLines = minLines,
    onTextLayout = onTextLayout, style = style)

private val EN_PREFIXES = listOf(
    "Куплено: " to "Purchased: ", "Реклама: " to "Ads: ", "Бонусы: " to "Bonus: ", "Тембр: " to "Pitch: ", "Скорость: " to "Speed: ",
    "Твой код друга принят: " to "Your friend's code was accepted: ", "Не удалось войти: HTTP " to "Sign-in failed: HTTP ",
    "Не удалось применить код: HTTP " to "Could not apply the code: HTTP ", "Ошибка сервера: HTTP " to "Server error: HTTP ",
    "Забрать +" to "Claim +",
)

private val EN_PATTERNS: List<Pair<Regex, (List<String>) -> String>> = listOf(
    Regex("Бесплатно сегодня: (\\d+) из (\\d+)\\. Картинки сохраняются только на твоём телефоне\\.") to { g -> "Free today: ${g[1]} of ${g[2]}. Pictures are stored only on your phone." },
    Regex("Бесплатные картинки на сегодня закончились\\. Следующая — (\\d+) ◈\\.") to { g -> "No free pictures left today. The next one costs ${g[1]} ◈." },
    Regex("Нарисовать · (\\d+) ◈") to { g -> "Draw · ${g[1]} ◈" },
    Regex("Отправить · (\\d+) ◈") to { g -> "Send · ${g[1]} ◈" },
    Regex("Другой ответ · (\\d+) ◈") to { g -> "Another reply · ${g[1]} ◈" },
    Regex("Premium · (\\d+) ◈") to { g -> "Premium · ${g[1]} ◈" },
    Regex("Premium: (\\d+) ◈ спишутся только за успешный ответ") to { g -> "Premium: ${g[1]} ◈ charged only for a successful reply" },
    Regex("Premium: более мощная модель, длинные ответы и увеличенный контекст\\. (\\d+) токенов только за успешный ответ\\. Баланс: (\\d+) ◈\\.") to
        { g -> "Premium: a stronger model, longer replies and more context. ${g[1]} tokens only for a successful reply. Balance: ${g[2]} ◈." },
    Regex("Версия (\\d+) · прежние сохранены") to { g -> "Version ${g[1]} · earlier ones kept" },
    Regex("Найдено: (\\d+)") to { g -> "Found: ${g[1]}" },
    Regex("Готово · списано (\\d+) ◈") to { g -> "Done · ${g[1]} ◈ spent" },
    Regex("Готово! Бесплатных на сегодня: (\\d+)") to { g -> "Done! Free left today: ${g[1]}" },
    Regex("\\+(\\d+) NOVA Tokens — ежедневный бонус") to { g -> "+${g[1]} NOVA Tokens — daily bonus" },
    Regex("Групповая история: ещё (\\d+) участн\\. Начни чат с главным героем — они присоединятся\\.") to
        { g -> "Group story: ${g[1]} more. Start the chat with the main character — they will join." },
    Regex("(\\d+) лет · (.+)") to { g -> "${g[1]} y.o. · ${g[2]}" },
    Regex("18\\+ · (\\d+) лет · Вымышленная героиня") to { g -> "18+ · ${g[1]} y.o. · Fictional heroine" },
    Regex("Вам обоим по (\\d+) ◈, когда друг отправит (\\d+) сообщения в чате\\.") to { g -> "You both get ${g[1]} ◈ when your friend sends ${g[2]} chat messages." },
    Regex("Приглашено: (\\d+) · бонус получен за: (\\d+)") to { g -> "Invited: ${g[1]} · bonus received for: ${g[2]}" },
    Regex("Код принят\\. Бонус придёт обоим после (\\d+) сообщений в чате") to { g -> "Code accepted. You both get the bonus after ${g[1]} chat messages" },
    Regex("(\\d+) из (\\d+) сообщений") to { g -> "${g[1]} of ${g[2]} messages" },
    Regex("Пароль слишком короткий: нужно не меньше 10 символов \\(сейчас (\\d+)\\)") to { g -> "Password too short: at least 10 characters needed (now ${g[1]})" },
    Regex("Символов: (\\d+) из минимум 10") to { g -> "Characters: ${g[1]} of at least 10" },
    Regex("Не удалось создать аккаунт \\(код (\\d+)\\)\\. Попробуй ещё раз\\.") to { g -> "Could not create the account (code ${g[1]}). Try again." },
    Regex("Не удалось войти \\(код (\\d+)\\)\\. Попробуй ещё раз\\.") to { g -> "Could not sign in (code ${g[1]}). Try again." },
    Regex("Групповая сцена · персонажей: (\\d+)") to { g -> "Group scene · characters: ${g[1]}" },
    Regex("(\\d+) Токены") to { g -> "${g[1]} Tokens" },
    Regex("◈ (\\d+) Токены") to { g -> "◈ ${g[1]} Tokens" },
)

private val EN: Map<String, String> = mapOf(
    // Navigation and common actions
    "Главная" to "Home", "Каталог" to "Catalog", "Чаты" to "Chats", "Создать" to "Create", "Профиль" to "Profile", "Токены" to "Tokens",
    "Закрыть" to "Close", "Удалить" to "Delete", "Поделиться" to "Share", "Отмена" to "Cancel", "Сохранить" to "Save", "Найти" to "Search",
    "Обновить" to "Refresh", "Назад" to "Back", "Ещё" to "More", "Изменить" to "Edit", "Действия" to "Actions", "Загружаю…" to "Loading…",
    "печатает…" to "typing…",
    // Login
    "Ближе, чем\nвоображение." to "Closer than\nimagination.", "Персонажи. Разговоры. Твоя история." to "Characters. Conversations. Your story.",
    "ДОБРО ПОЖАЛОВАТЬ" to "WELCOME", "Твоя история начинается здесь" to "Your story starts here", "Адрес сервиса" to "Service address",
    "Пароль (от 10 символов)" to "Password (10+ characters)", "Скрыть пароль" to "Hide password", "Показать пароль" to "Show password",
    "Мне исполнилось 18 лет. Персонажи вымышленные." to "I am 18 or older. Characters are fictional.", "Войти" to "Sign in",
    "Создать аккаунт" to "Create account",
    "Сервер просыпается после простоя — это может занять до минуты. Не закрывай приложение." to "The server is waking up — this can take up to a minute. Keep the app open.",
    "Сообщения обрабатывают сервер NOVA и внешние AI-провайдеры. Не указывай конфиденциальные данные." to "Messages are processed by the NOVA server and external AI providers. Do not share confidential data.",
    "Знакомься с персонажами" to "Meet the characters",
    // Home
    "NOVA / ТВОЁ ПРОСТРАНСТВО" to "NOVA / YOUR SPACE", "ДЛЯ ТЕБЯ" to "FOR YOU", "У каждой встречи\nесть продолжение." to "Every meeting\nhas a sequel.",
    "Найди свою\nисторию" to "Find your\nstory", "Детективы, мечтатели и герои новых миров." to "Detectives, dreamers and heroes of new worlds.",
    "Исследовать каталог" to "Explore the catalog", "ТВОЯ ВООБРАЖАЕМАЯ ВСЕЛЕННАЯ" to "YOUR IMAGINARY UNIVERSE",
    "Кем будет следующий герой?" to "Who will your next hero be?", "Создай характер, выбери настроение и начни разговор." to "Create a personality, pick a mood and start talking.",
    "Создать персонажа" to "Create a character", "Мои переписки" to "My conversations", "НОВОЕ · AI АРТ" to "NEW · AI ART",
    "Нарисуй любимую сцену" to "Draw your favourite scene",
    "Опиши картинку словами — AI нарисует её за полминуты. Несколько штук в день бесплатно." to "Describe a picture in words — AI draws it in about half a minute. A few a day are free.",
    "Открыть AI Арт" to "Open AI Art",
    // Catalog and profile
    "Выбери героя и начни свою историю" to "Pick a character and start your story", "Для всех" to "For everyone",
    "Раздел 18+: романтика и взрослые драмы с совершеннолетними вымышленными персонажами. Откровенный контент запрещён." to "18+ section: romance and adult drama with fictional adult characters. Explicit content is not allowed.",
    "Имя, характер или история" to "Name, personality or story", "Все" to "All", "Популярные" to "Popular", "Моё избранное" to "My favourites",
    "Мои подписки" to "Following", "Скрыть фильтры" to "Hide filters", "Категория, тег и сортировка" to "Category, tag and sorting",
    "Категория" to "Category", "Тег" to "Tag", "Новые" to "Newest", "По имени" to "By name", "Найти персонажей" to "Find characters",
    "Опубликованных персонажей пока нет." to "No published characters yet.", "Попробуй другой запрос или создай своего героя." to "Try another search or create your own character.",
    "Начать AI-чат" to "Start AI chat", "Полный профиль" to "Full profile", "Закрыть профиль" to "Close profile",
    "Оригинальный вымышленный взрослый персонаж" to "Original fictional adult character", "Убрать из избранного" to "Remove from favourites",
    "В избранное" to "Add to favourites", "ПЕРВОЕ СООБЩЕНИЕ" to "FIRST MESSAGE", "Биография" to "Biography", "Характер" to "Personality",
    "Внешность" to "Appearance", "Интересы" to "Interests", "Предыстория" to "Backstory", "Манера речи" to "Speech style", "Сценарий" to "Scenario",
    "Участник групповой истории (до 4)" to "Group story member (up to 4)", "Создать свою версию" to "Make my own version",
    "Нарисовать арт" to "Draw art", "Подписаться на автора" to "Follow the author", "Отписаться" to "Unfollow", "Обсуждение" to "Discussion",
    "Пока нет комментариев." to "No comments yet.", " · На модерации" to " · Under review", "Комментарий" to "Comment",
    "Отправить комментарий" to "Post comment",
    // Chats list
    "Мои чаты" to "My chats", "Твои истории сохраняются в аккаунте" to "Your stories are saved to your account",
    "Поиск в переписках" to "Search conversations", "Общение временно недоступно. История сохранена." to "Chat is temporarily unavailable. Your history is saved.",
    "Пока нет переписок" to "No conversations yet", "Открой каталог, выбери героя и напиши первое сообщение." to "Open the catalog, pick a character and send the first message.",
    "Открыть персонажей" to "Open characters", "AI-персонаж" to "AI character", "Групповая история" to "Group story",
    "Продолжить разговор" to "Continue the conversation",
    // Create
    "Создать героя" to "Create a character", "Персонаж сохраняется в твоей библиотеке" to "The character is saved in your library",
    "Имя персонажа" to "Character name", "Первое сообщение персонажа" to "Character's first message", "Сохранить персонажа" to "Save character",
    "Мои персонажи" to "My characters", "Общаться" to "Chat", "Редактировать" to "Edit", "Скрыть из каталога" to "Hide from catalog",
    "Отправить на модерацию" to "Submit for review", "Внешность взрослого персонажа" to "Adult character's appearance",
    "Теги через запятую" to "Tags, comma-separated", "Язык: ru / en" to "Language: ru / en", "HTTPS-ссылка на свой портрет" to "HTTPS link to your portrait",
    "Для взрослой аудитории 18+ (романтика без откровенного контента). Проходит модерацию." to "For adult audiences 18+ (romance without explicit content). Reviewed by moderators.",
    // Wallet and rewards
    "Больше возможностей для твоих историй" to "More for your stories", "ТВОИ ВОЗМОЖНОСТИ" to "YOUR OPTIONS", "Баланс" to "Balance",
    "Обновить баланс" to "Refresh balance", "История операций" to "Transaction history", "Пока нет операций." to "No transactions yet.",
    "Токены начисляются после серверной проверки Google Play или AdMob." to "Tokens are credited after server verification by Google Play or AdMob.",
    "МАЛЕНЬКИЙ ПОДАРОК" to "A LITTLE GIFT", "Ежедневный бонус" to "Daily bonus", "Получено сегодня. Следующий бонус — завтра." to "Claimed today. Next bonus tomorrow.",
    "ИСТОРИИ ЛУЧШЕ ВМЕСТЕ" to "STORIES ARE BETTER TOGETHER", "Пригласи друга" to "Invite a friend", "Поделиться кодом" to "Share code",
    "Бонус за твоё приглашение получен" to "Your invite bonus has been received", "Код друга" to "Friend's code", "Применить код" to "Apply code",
    "Бонус за приглашение" to "Invite bonus", "Друг присоединился по твоему коду" to "A friend joined with your code", "Premium-ответ" to "Premium reply",
    "Награда за рекламу" to "Ad reward", "Покупка токенов" to "Token purchase", "Возврат" to "Refund", "Списание" to "Spend", "Начисление" to "Credit",
    "Покупка ожидает проверки. Используй восстановление покупок." to "Purchase pending verification. Use restore purchases.",
    "Google Play временно недоступен" to "Google Play is temporarily unavailable",
    "Покупки временно недоступны. Бесплатный чат остаётся доступным." to "Purchases are temporarily unavailable. Free chat still works.",
    "Не удалось проверить покупки. Повтори позже." to "Could not verify purchases. Try again later.",
    "Восстановить незавершённые покупки" to "Restore pending purchases", "Реклама недоступна без необходимых настроек согласия" to "Ads are unavailable without the required consent settings",
    "Просмотр завершён. Награда появится после проверки сервером." to "Done. The reward appears after server verification.",
    "Сейчас нет доступной рекламы" to "No ads available right now", "Не удалось загрузить настройки конфиденциальности рекламы" to "Could not load ad privacy settings",
    "Посмотреть рекламу за токены" to "Watch an ad for tokens", "Настройки конфиденциальности рекламы" to "Ad privacy settings",
    // Account
    "Твоё личное пространство" to "Your personal space",
    "Сессия и неотправленный текст зашифрованы на устройстве. Чаты и память хранятся в твоём аккаунте." to "Your session and unsent text are encrypted on this device. Chats and memory are stored in your account.",
    "Выйти из аккаунта" to "Sign out", "Экспортировать мои данные" to "Export my data", "Экспорт NOVA" to "NOVA export",
    "Поделиться экспортом" to "Share export", "Удалить аккаунт" to "Delete account", "Удалить аккаунт и истории?" to "Delete account and stories?",
    "Чаты, память и персонажи будут удалены. Учёт покупок сохраняется для обработки возвратов." to "Chats, memory and characters will be deleted. Purchase records are kept for refunds.",
    "Удалить навсегда" to "Delete forever", "Уведомления" to "Notifications", "Прочитано" to "Mark read", "Обращение в поддержку" to "Message to support",
    "Отправить обращение" to "Send to support", "Раздел 18+ (Mature)" to "18+ section (Mature)",
    "Выключен по умолчанию. Романтика без откровенного контента." to "Off by default. Romance without explicit content.",
    "Только для взрослых" to "Adults only",
    "Раздел содержит романтические и взрослые сюжеты с вымышленными совершеннолетними персонажами. Откровенный сексуальный контент запрещён. Подтверди, что тебе исполнилось 18 лет." to
        "This section has romantic and adult stories with fictional adult characters. Explicit sexual content is not allowed. Confirm that you are 18 or older.",
    "Мне есть 18" to "I am 18+", "Язык приложения" to "App language",
    // Curated heroines
    "Встречи\nс характером" to "Meetings\nwith character", "12 оригинальных героинь · 25–36 лет" to "12 original heroines · aged 25–36",
    "Авторская подборка. Начав историю, ты создашь личную версию героини. Публикация — только через обычную модерацию." to "A curated set. Starting a story creates your personal version of the heroine. Publishing goes through normal review.",
    "Свернуть подборку" to "Collapse", "Открыть 12 историй" to "Open 12 stories", "Романтика" to "Romance", "Творчество" to "Creativity",
    "Приключения" to "Adventure", "Первое сообщение" to "First message", "Начать историю" to "Start the story",
    // Conversation
    "Новая ветка" to "New branch", "Твоя история" to "Your story", "Вымышленный AI-персонаж" to "Fictional AI character",
    "Это разговор с AI. Не отправляй конфиденциальные данные." to "This is a conversation with AI. Do not send confidential data.",
    "Более ранние сообщения" to "Earlier messages", "Всё начинается\nс одного «привет»." to "It all starts\nwith one \"hello\".",
    "Выбери слова. Историю создадите вместе." to "Choose your words. You will write the story together.", "ТЫ" to "YOU",
    "■ Стоп" to "■ Stop", "▶ Слушать" to "▶ Listen", "Другой ответ" to "Another reply", "Ответвить историю" to "Branch the story",
    "Пожаловаться" to "Report", "Удалить сообщение?" to "Delete message?",
    "Сообщение и ответ персонажа на него будут удалены." to "The message and the character's reply will be deleted.", "Ответ будет удалён." to "The reply will be deleted.",
    "Обычный · бесплатно" to "Standard · free", "Напиши сообщение" to "Write a message", "Отправить бесплатно" to "Send for free",
    "Остановить ответ" to "Stop reply", "РЕЖИМ ОБЩЕНИЯ" to "CHAT MODE",
    "Обычный чат: без дневного лимита NOVA и без списания токенов. Возможны технические задержки провайдера." to "Standard chat: no NOVA daily limit and no token charges. Provider delays are possible.",
    "Поиск в этой переписке" to "Search this conversation",
    "Сообщения обрабатывают сервер NOVA и внешние AI-провайдеры. На бесплатном тарифе данные могут использоваться провайдером для улучшения моделей." to
        "Messages are processed by the NOVA server and external AI providers. On free plans, providers may use data to improve their models.",
    "Удалить чат" to "Delete chat", "Удалить чат?" to "Delete chat?", "История и её память будут удалены." to "The story and its memory will be deleted.",
    "Изменить сообщение" to "Edit message", "Диктовать сообщение" to "Dictate a message", "Озвучить" to "Read aloud",
    "Скрыть память и настройки" to "Hide memory and settings", "Память и настройки истории" to "Story memory and settings",
    "Художественная жизнь персонажа" to "The character's fictional life", "Дневник" to "Diary", "Событие" to "Event", "Монолог" to "Monologue", "Заметка" to "Note",
    "Твоя роль в истории" to "Your role in the story", "Имя, роль и факты о герое" to "Name, role and facts about your hero",
    "Сценарий / мир" to "Scenario / world", "Кратко" to "Brief", "Сбалансированно" to "Balanced", "Подробно" to "Detailed",
    "Дружелюбно" to "Friendly", "Драматично" to "Dramatic", "С юмором" to "Humorous", "Спокойно" to "Calm", "Сохранить настройки" to "Save settings",
    "Память этой истории" to "This story's memory",
    "Записи используются только в этой переписке. Их можно исправить или удалить." to "Notes are used only in this conversation. You can edit or delete them.",
    "Открепить" to "Unpin", "Закрепить" to "Pin", "Важный факт или событие" to "Important fact or event", "Добавить воспоминание" to "Add memory",
    "Сохранить воспоминание" to "Save memory",
    // Groups, lasting memory, scenarios
    "Участники сцены" to "Scene cast",
    "До 5 персонажей. Каждый отвечает своим голосом; за ход говорят двое (в Premium — трое): сначала те, к кому ты обратился по имени, затем те, кто дольше молчал." to
        "Up to 5 characters. Each answers in their own voice; two speak per turn (three in Premium): first those you addressed by name, then whoever has been silent longest.",
    "Убрать из сцены" to "Remove from scene", "Скрыть список" to "Hide list", "Пригласить персонажа" to "Invite a character",
    "Что персонаж помнит о тебе" to "What the character remembers about you",
    "Эти записи персонаж учитывает во всех твоих историях с ним. Их видишь только ты; автор персонажа и другие люди — нет." to
        "The character uses these notes in all your stories with them. Only you can see them; the character's author and other people cannot.",
    "Записано автоматически" to "Recorded automatically", "Забыть" to "Forget", "Что персонажу стоит помнить" to "What the character should remember",
    "Запомнить" to "Remember",
    "Записи используются только в этой переписке. Важные события, факты и отношения записываются автоматически каждые несколько ходов. Любую запись можно исправить или удалить." to
        "Notes are used only in this conversation. Important events, facts and relationships are recorded automatically every few turns. Any note can be edited or deleted.",
    "Событие · записано автоматически" to "Event · recorded automatically", "Отношения · записано автоматически" to "Relationship · recorded automatically",
    "Факт · записано автоматически" to "Fact · recorded automatically", "Факт" to "Fact", "Отношения" to "Relationship", "Сводка истории" to "Story summary",
    "Сводка истории · записано автоматически" to "Story summary · recorded automatically",
    "Сценарии" to "Scenarios", "Начать этот сценарий" to "Start this scenario", "Название сценария" to "Scenario title",
    "Мир и завязка сценария" to "World and opening of the scenario", "Добавить сценарий" to "Add scenario", "Профиль и сценарии" to "Profile and scenarios",
    "В сцене может быть до 5 персонажей" to "A scene can have up to 5 characters", "Персонаж вышел из сцены" to "The character left the scene",
    "Персонаж присоединился к сцене" to "The character joined the scene", "Заполни название и описание сценария" to "Fill in the scenario title and description",
    "Сценарий добавлен" to "Scenario added",
    // Voice
    "ГОЛОС ПЕРСОНАЖА" to "CHARACTER VOICE", "Читать ответы вслух" to "Read replies aloud", "низкий" to "low", "высокий" to "high", "средний" to "medium",
    "Прослушать голос" to "Preview voice", "Привет! Так звучит мой голос." to "Hi! This is how my voice sounds.",
    "Голосовой движок телефона ещё загружается или не установлен (Настройки → Синтез речи)." to "The phone's speech engine is loading or not installed (Settings → Text-to-speech).",
    "На устройстве нет службы распознавания речи" to "No speech recognition service on this device",
    // AI Art
    "Аниме" to "Anime", "Реализм" to "Realistic", "Фэнтези" to "Fantasy", "Портрет" to "Portrait", "Кино" to "Cinematic", "AI АРТ" to "AI ART",
    "Нарисуй сцену\nиз своей истории" to "Draw a scene\nfrom your story", "Рисование пока недоступно на сервере." to "Drawing is not available on the server yet.",
    "Что нарисовать?" to "What should I draw?", "Например: героиня у окна поезда на закате" to "For example: a heroine by a train window at sunset",
    "Нарисовать бесплатно" to "Draw for free", "Рисую… обычно 10–30 секунд" to "Drawing… usually 10–30 seconds",
    "Нельзя: обнажённая натура, дети, реальные люди, жестокость. Такие запросы отклоняются без списания токенов." to "Not allowed: nudity, children, real people, violence. Such requests are declined without charging tokens.",
    "ГОТОВО" to "DONE", "Созданная картинка" to "Generated picture", "Мои картинки" to "My pictures", "Картинка из галереи" to "Picture from gallery",
    "Картинка" to "Picture", "Сохранено в галерею (Pictures/NOVA AI)" to "Saved to gallery (Pictures/NOVA AI)", "Не удалось сохранить" to "Could not save",
    "В галерею" to "Save to gallery", "Поделиться картинкой" to "Share picture",
    "Девушка-маг с серебряными волосами в ночном неоновом городе" to "A silver-haired sorceress in a neon city at night",
    "Уютная кофейня в дождливом Токио, вид из окна" to "A cosy café in rainy Tokyo, view from the window",
    "Рыцарь в золотых доспехах на краю облачного замка" to "A knight in golden armour at the edge of a cloud castle",
    "Космический корабль над фиолетовой планетой" to "A spaceship above a purple planet",
    "Опиши картинку хотя бы парой слов" to "Describe the picture in at least a couple of words",
    "Такую картинку нарисовать нельзя: без обнажённой натуры, детей, реальных людей и жестокости. Измени описание." to "That picture can't be drawn: no nudity, children, real people or violence. Change the description.",
    "Бесплатные картинки на сегодня закончились, а токенов не хватает. Забери ежедневный бонус или пригласи друга." to "No free pictures left today and not enough tokens. Claim the daily bonus or invite a friend.",
    "Сегодня художник нарисовал слишком много. Попробуй завтра." to "The artist has drawn too much today. Try tomorrow.",
    "Слишком часто. Подожди минуту и попробуй снова." to "Too often. Wait a minute and try again.",
    "Рисование временно недоступно. Токены не списаны — попробуй чуть позже." to "Drawing is temporarily unavailable. No tokens were charged — try a bit later.",
    // Notices and errors
    "Раздел 18+ включён" to "18+ section on", "Раздел 18+ скрыт" to "18+ section hidden",
    "Сервер NOVA ещё не подключён. Нужен HTTPS-адрес сервиса." to "The NOVA server is not connected yet. An HTTPS address is required.",
    "Сначала войди в аккаунт" to "Sign in first",
    "Связь с AI прервалась во время ответа. Ничего не списано — отправь ещё раз." to "The AI connection dropped mid-reply. Nothing was charged — send again.",
    "Недостаточно NOVA Tokens для Premium-ответа" to "Not enough NOVA Tokens for a Premium reply",
    "Запрос ещё обрабатывается или цена изменилась. Обнови чат и повтори." to "The request is still processing or the price changed. Refresh the chat and retry.",
    "AI занят или достиг лимита провайдера. Попробуй позже." to "AI is busy or reached the provider limit. Try later.",
    "AI-провайдер ещё не подключён или временно недоступен" to "The AI provider is not connected or temporarily unavailable",
    "Остановка запрошена. Черновик сохранён. Обнови историю: ответ мог завершиться до остановки." to "Stop requested. Draft saved. Refresh the history: the reply may have finished before stopping.",
    "Сессия закончилась. Войди заново." to "Session expired. Please sign in again.", "Не удалось выполнить запрос" to "The request failed",
    "Подтверди, что тебе исполнилось 18 лет" to "Confirm that you are 18 or older", "Неверный email или пароль" to "Wrong email or password",
    "Этот email уже зарегистрирован — войди в аккаунт" to "This email is already registered — sign in",
    "Слишком много попыток входа. Подожди 15 минут и попробуй снова." to "Too many sign-in attempts. Wait 15 minutes and try again.",
    "Подключено к NOVA AI" to "Connected to NOVA AI", "Вход выполнен" to "Signed in",
    "Аккаунт создан. Добро пожаловать в NOVA AI!" to "Account created. Welcome to NOVA AI!", "Введи email" to "Enter your email", "Введи пароль" to "Enter your password",
    "Проверь email: он должен выглядеть как name@example.com, латиницей и без пробелов" to "Check the email: it should look like name@example.com, Latin letters, no spaces",
    "Пароль слишком длинный: максимум 128 символов" to "Password too long: 128 characters maximum",
    "Отметь, что тебе исполнилось 18 лет — без этого аккаунт создать нельзя" to "Confirm that you are 18 or older — an account can't be created without it",
    "Сервер не принял email. Проверь адрес: name@example.com, латиницей и без пробелов." to "The server did not accept the email. Check it: name@example.com, Latin letters, no spaces.",
    "Пароль слишком короткий: нужно не меньше 10 символов." to "Password too short: at least 10 characters.",
    "Пароль слишком короткий: в NOVA пароли не короче 10 символов. Проверь, тот ли пароль ты вводишь." to "Password too short: NOVA passwords are at least 10 characters. Check that you are typing the right one.",
    "Пароль слишком длинный: максимум 128 символов." to "Password too long: 128 characters maximum.",
    "Отметь, что тебе исполнилось 18 лет — без этого аккаунт создать нельзя." to "Confirm that you are 18 or older — an account can't be created without it.",
    "Не получилось создать аккаунт: проверь email и пароль." to "Could not create the account: check the email and password.",
    "Не получилось войти: проверь email и пароль." to "Could not sign in: check the email and password.",
    "Неверный email или пароль. Если аккаунта ещё нет — нажми «Создать аккаунт»." to "Wrong email or password. No account yet? Tap \"Create account\".",
    "Этот email уже зарегистрирован — нажми «Войти»." to "This email is already registered — tap \"Sign in\".",
    "Слишком много неудачных попыток входа. Подожди 15 минут и попробуй снова." to "Too many failed sign-in attempts. Wait 15 minutes and try again.",
    "Слишком много попыток подряд. Подожди минуту и попробуй снова." to "Too many attempts in a row. Wait a minute and try again.",
    "Этот аккаунт заблокирован. Напиши в поддержку." to "This account is blocked. Contact support.",
    "Сервер NOVA временно не отвечает. Попробуй ещё раз через минуту." to "The NOVA server is not responding. Try again in a minute.",
    "Нет связи с сервером NOVA. Проверь интернет и попробуй снова — первый вход после перерыва может занять до минуты." to "Can't reach the NOVA server. Check your internet and try again — the first sign-in after a break can take up to a minute.",
    "Проверь email" to "Check the email", "Проверь пароль" to "Check the password", "Сообщение должно быть от 1 до 4000 символов" to "A message must be 1 to 4000 characters",
    "Сообщение изменено. Нажми «Другой ответ», чтобы персонаж ответил на новый текст." to "Message edited. Tap \"Another reply\" so the character answers the new text.",
    "Сообщение и ответ на него удалены" to "Message and its reply deleted", "Ответ удалён" to "Reply deleted", "Персонаж скрыт из каталога" to "Character hidden from catalog",
    "Включи раздел 18+ в настройках профиля" to "Turn on the 18+ section in profile settings", "Сообщение слишком длинное" to "The message is too long",
    "Ответ ещё обрабатывается. Повтори отправку." to "The reply is still processing. Send again.", "Переписка удалена" to "Conversation deleted",
    "Заполни имя и приветствие персонажа" to "Fill in the character's name and greeting", "Персонаж создан и сохранён" to "Character created and saved",
    "Персонаж добавлен в избранное" to "Character added to favourites", "Бонус за сегодня уже получен" to "Today's bonus is already claimed",
    "Код приглашения — 8 букв и цифр" to "An invite code is 8 letters and digits", "Бонус за приглашение начислен" to "Invite bonus credited",
    "Нельзя применить свой собственный код" to "You can't apply your own code", "Код приглашения уже применён" to "An invite code is already applied",
    "Код можно применить только в первые 7 дней после регистрации" to "A code can only be applied within 7 days of sign-up",
    "Этот друг уже зарегистрировался по твоему коду" to "This friend already joined with your code", "Такого кода нет" to "No such code",
    "Персонаж отправлен на модерацию" to "Character submitted for review", "Создана отдельная ветка истории" to "A separate story branch was created",
    "Настройки истории сохранены" to "Story settings saved", "Жалоба отправлена" to "Report sent", "Новый Premium-ответ готов" to "New Premium reply ready",
    "Новый ответ готов" to "New reply ready", "Обращение сохранено" to "Message saved", "Подписка сохранена" to "Following",
    "Подписка отменена" to "Unfollowed", "Отметка сохранена" to "Saved", "Комментарий отправлен на модерацию" to "Comment sent for review",
)
