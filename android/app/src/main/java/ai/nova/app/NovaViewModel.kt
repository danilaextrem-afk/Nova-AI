package ai.nova.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.google.gson.Gson
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

enum class NovaTab { HOME, CATALOG, CHATS, CREATE, WALLET, ACCOUNT, ART }
internal enum class CatalogView { PUBLIC, FAVORITES, FOLLOWING }

private val EMAIL_RE = Regex("^(?!\\.)(?!.*\\.\\.)[A-Za-z0-9_'+.-]*[A-Za-z0-9_+-]@([A-Za-z0-9][A-Za-z0-9-]*\\.)+[A-Za-z]{2,}$")

class NovaViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SessionStore(application)
    private val gson = Gson()
    private val refreshLock = Any()
    var adultConfirmed by mutableStateOf(false)
    var comments by mutableStateOf<List<SocialComment>>(emptyList())
    var commentInput by mutableStateOf("")
    var selectedPublication by mutableStateOf<String?>(null)
    var favorites by mutableStateOf<List<NovaCharacter>>(emptyList())
    var memories by mutableStateOf<List<MemoryItem>>(emptyList())
    var notifications by mutableStateOf<List<NovaNotification>>(emptyList())
    var memoryInput by mutableStateOf("")
    var editingMemoryId by mutableStateOf<String?>(null)
    var playerPersona by mutableStateOf("")
    var storyScenario by mutableStateOf("")
    var responseStyle by mutableStateOf("balanced")
    var storyTone by mutableStateOf("friendly")
    var groupIds by mutableStateOf<Set<String>>(emptySet())
    var nextBefore by mutableStateOf<Int?>(null)
    var draftBiography by mutableStateOf("")
    var draftPersonality by mutableStateOf("")
    var draftAppearance by mutableStateOf("")
    var draftInterests by mutableStateOf("")
    var draftBackstory by mutableStateOf("")
    var draftSpeechStyle by mutableStateOf("")
    var draftScenario by mutableStateOf("")
    var draftAvatar by mutableStateOf("")
    var draftTags by mutableStateOf("")
    var draftLanguage by mutableStateOf("ru")
    var editingCharacterId by mutableStateOf<String?>(null)
    var products by mutableStateOf<ProductPage?>(null)
    var ads by mutableStateOf<AdStatus?>(null)
    var exportText by mutableStateOf<String?>(null)
    var supportText by mutableStateOf("")
    // AI Art: generated pictures live only on this phone (app storage), never on the server.
    var artStatus by mutableStateOf<ArtStatus?>(null)
    var artPrompt by mutableStateOf("")
    var artStyle by mutableStateOf("anime")
    var artLatest by mutableStateOf<java.io.File?>(null)
    var artGallery by mutableStateOf<List<java.io.File>>(emptyList())
    private val artDir get() = java.io.File(getApplication<Application>().filesDir, "nova-art").apply { mkdirs() }

    var serverUrl by mutableStateOf(store.get("server") ?: BuildConfig.NOVA_API_BASE_URL)
    var email by mutableStateOf(store.get("email") ?: "")
    var password by mutableStateOf("")
    var accessToken by mutableStateOf<String?>(store.get("access"))
        private set
    var activeTab by mutableStateOf(NovaTab.HOME)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var notification by mutableStateOf<String?>(null)
        private set
    var catalog by mutableStateOf<List<NovaCharacter>>(emptyList())
        private set
    var chats by mutableStateOf<List<NovaChat>>(emptyList())
        private set
    var activeChat by mutableStateOf<NovaChat?>(store.get("chat")?.let { runCatching { gson.fromJson(it, NovaChat::class.java) }.getOrNull() })
        private set
    var chatMessages by mutableStateOf<List<NovaChatMessage>>(emptyList())
        private set
    var chatInput by mutableStateOf(store.get("draft") ?: "")
    fun updateChatInput(value: String) { chatInput = value; store.put("draft", value) }
    var premiumChatSelected by mutableStateOf(false)
    private var pendingChatFingerprint: String? = store.get("pendingFingerprint")
    private var pendingChatRequestId: String? = store.get("pendingId")
    var aiStatus by mutableStateOf<NovaAiStatus?>(null)
        private set
    var owned by mutableStateOf<List<NovaCharacter>>(emptyList())
        private set
    var wallet by mutableStateOf<TokenWallet?>(null)
        private set
    var transactions by mutableStateOf<List<TokenTransaction>>(emptyList())
        private set
    var catalogQuery by mutableStateOf("")
    var catalogCategory by mutableStateOf("")
    var catalogTag by mutableStateOf("")
    var catalogSort by mutableStateOf("recent")
    /** 18+ section: opt-in after confirmation; general and 18+ characters are never mixed in one list. */
    var showMature by mutableStateOf(false)
        private set
    var matureSection by mutableStateOf(false)
    var draftMature by mutableStateOf(false)
    var draftAge by mutableStateOf<Int?>(null)
    var draftGender by mutableStateOf<String?>(null)
    var draftTraits by mutableStateOf<List<String>>(emptyList())
    fun selectMatureSection(on: Boolean) {
        matureSection = on && showMature
        selectCatalogView(CatalogView.PUBLIC)
    }
    fun updateShowMature(on: Boolean) {
        execute {
            showMature = service().contentSettings(authorization(), ContentSettingsBody(on, if (on) true else null)).showMature
            if (!showMature) matureSection = false
            notification = if (showMature) "Раздел 18+ включён" else "Раздел 18+ скрыт"
        }
    }
    fun loadContentSettings() { execute { showMature = runCatching { service().me(authorization()).profile?.showMature == true }.getOrDefault(false) } }
    var draftName by mutableStateOf("")
    var draftGreeting by mutableStateOf("")
    var draftCategory by mutableStateOf("fantasy")

    init {
        restoreDraft()
        if (accessToken != null) {
            activeChat?.let { openChat(it) } ?: refreshCatalog()
        }
    }
    private fun service(): NovaApi {
        val url = serverUrl.trim().trimEnd('/') + "/"
        val parsed = java.net.URI(url)
        require((parsed.scheme == "https" || (BuildConfig.DEBUG && parsed.scheme=="http" && parsed.host in setOf("127.0.0.1","10.0.2.2","localhost"))) && parsed.host != null && parsed.userInfo == null && parsed.query == null && parsed.fragment == null && !url.contains("example.invalid")) {
            "Сервер NOVA ещё не подключён. Нужен HTTPS-адрес сервиса."
        }
        val builder = Retrofit.Builder().baseUrl(url).addConverterFactory(GsonConverterFactory.create())
        val refreshApi = builder.build().create(NovaApi::class.java)
        val client = OkHttpClient.Builder().readTimeout(100, TimeUnit.SECONDS).callTimeout(110, TimeUnit.SECONDS)
            .authenticator { _, response ->
                if (response.priorResponse != null) null else synchronized(refreshLock) {
                    val current = store.get("access")
                    if (current != null && response.request.header("Authorization") != "Bearer " + current) {
                        response.request.newBuilder().header("Authorization", "Bearer " + current).build()
                    } else {
                        val refresh = store.get("refresh")
                        val next = refresh?.let { runCatching { refreshApi.refreshSync(RefreshBody(it)).execute().body() }.getOrNull() }
                        if (next == null || store.get("refresh") != refresh) null else {
                            store.put("access", next.accessToken)
                            store.put("refresh", next.refreshToken)
                            viewModelScope.launch {
                                if (accessToken != null && store.get("access") == next.accessToken) {
                                    accessToken = next.accessToken
                                }
                            }
                            response.request.newBuilder().header("Authorization", "Bearer " + next.accessToken).build()
                        }
                    }
                }
            }.build()
        return builder.client(client).build().create(NovaApi::class.java)
    }
    private fun authorization(): String = "Bearer " + requireNotNull(store.get("access") ?: accessToken) { "Сначала войди в аккаунт" }

    /** Text of the reply while it is being streamed; null when nothing is streaming. */
    var streamingText by mutableStateOf<String?>(null)
        private set
    private var streamJob: Job? = null
    /** Stops the reply being written. Unsaved, uncharged; the draft and request ID stay for a retry. */
    private var activeStreamBody: okhttp3.ResponseBody? = null
    fun stopReply() {
        streamJob?.cancel()
        val body = activeStreamBody
        viewModelScope.launch(Dispatchers.IO) { body?.close() }
    }

    private class StreamError(val status: Int, val code: String?) : Exception(code)
    /** Reads SSE from the stream route and returns the final "done" payload. */
    private suspend fun streamReply(api: NovaApi, chatId: String, body: NovaChatSendBody): NovaChatSendResponse {
        streamingText = ""
        val response = api.sendChatMessageStream(authorization(), chatId, body)
        activeStreamBody = response
        return try { withContext(Dispatchers.IO) {
            response.use { rb ->
                val source = rb.source(); var event = ""; var result: NovaChatSendResponse? = null
                while (result == null) {
                    val line = source.readUtf8Line() ?: break
                    when {
                        line.startsWith("event:") -> event = line.substring(6).trim()
                        line.startsWith("data:") -> {
                            val data = line.substring(5).trim()
                            when (event) {
                                "delta" -> { val piece = gson.fromJson(data, com.google.gson.JsonObject::class.java).get("text")?.asString.orEmpty()
                                    withContext(Dispatchers.Main) { streamingText = (streamingText ?: "") + piece } }
                                "done" -> result = gson.fromJson(data, NovaChatSendResponse::class.java)
                                "error" -> { val o = gson.fromJson(data, com.google.gson.JsonObject::class.java)
                                    throw StreamError(o.get("status")?.asInt ?: 502, o.get("error")?.asString) }
                            }
                        }
                    }
                }
                result ?: throw StreamError(502, "STREAM_ENDED")
            }
        } } finally { activeStreamBody = null }
    }

    private fun httpErrorText(code: Int, error: String? = null): String = when {
        error == "AI_STREAM_INTERRUPTED" -> "Связь с AI прервалась во время ответа. Ничего не списано — отправь ещё раз."
        code == 402 -> "Недостаточно NOVA Tokens для Premium-ответа"
        code == 409 -> "Запрос ещё обрабатывается или цена изменилась. Обнови чат и повтори."
        code == 429 -> "AI занят или достиг лимита провайдера. Попробуй позже."
        code == 503 -> "AI-провайдер ещё не подключён или временно недоступен"
        else -> "Ошибка сервера: HTTP $code"
    }

    private fun execute(block: suspend () -> Unit): Job? {
        if (loading) return null
        loading = true
        return viewModelScope.launch {
            loading = true
            error = null
            notification = null
            try {
                block()
            } catch (e: CancellationException) {
                if (streamingText != null) notification = "Остановка запрошена. Черновик сохранён. Обнови историю: ответ мог завершиться до остановки."
                throw e
            } catch (e: StreamError) {
                error = httpErrorText(e.status, e.code)
                if (e.status == 401) { accessToken = null; error = "Сессия закончилась. Войди заново." }
            } catch (e: HttpException) {
                error = httpErrorText(e.code())
                if (e.code() == 401) {
                    accessToken = null
                    error = "Сессия закончилась. Войди заново."
                }
            } catch (e: Exception) {
                error = e.localizedMessage ?: "Не удалось выполнить запрос"
            } finally {
                loading = false
                pendingTabRefresh?.let { tab -> pendingTabRefresh = null; if (activeTab == tab) viewModelScope.launch { changeTab(tab) } }
                streamingText = null
            }
        }
    }

    // Field errors shown under the login form fields; cleared as soon as the person edits the field.
    var emailError by mutableStateOf<String?>(null)
    var passwordError by mutableStateOf<String?>(null)
    var adultError by mutableStateOf<String?>(null)

    /** Same rules as the server (it still validates): checked here so mistakes are explained before any request. */
    fun validateCredentials(createAccount: Boolean): Boolean {
        val mail = email.trim()
        emailError = when {
            mail.isEmpty() -> "Введи email"
            mail.length > 254 || !EMAIL_RE.matches(mail) -> "Проверь email: он должен выглядеть как name@example.com, латиницей и без пробелов"
            else -> null
        }
        passwordError = when {
            password.isEmpty() -> "Введи пароль"
            password.length < 10 -> "Пароль слишком короткий: нужно не меньше 10 символов (сейчас ${password.length})"
            password.length > 128 -> "Пароль слишком длинный: максимум 128 символов"
            else -> null
        }
        adultError = if (createAccount && !adultConfirmed) "Отметь, что тебе исполнилось 18 лет — без этого аккаунт создать нельзя" else null
        return emailError == null && passwordError == null && adultError == null
    }

    private fun serverErrorCode(e: HttpException): String? = runCatching {
        gson.fromJson(e.response()?.errorBody()?.string(), Map::class.java)["error"] as? String
    }.getOrNull()

    private fun authErrorText(createAccount: Boolean, status: Int, code: String?): String = when {
        code == "EMAIL_INVALID" -> "Сервер не принял email. Проверь адрес: name@example.com, латиницей и без пробелов."
        code == "PASSWORD_TOO_SHORT" -> if (createAccount) "Пароль слишком короткий: нужно не меньше 10 символов."
            else "Пароль слишком короткий: в NOVA пароли не короче 10 символов. Проверь, тот ли пароль ты вводишь."
        code == "PASSWORD_TOO_LONG" -> "Пароль слишком длинный: максимум 128 символов."
        code == "ADULT_CONFIRMATION_REQUIRED" -> "Отметь, что тебе исполнилось 18 лет — без этого аккаунт создать нельзя."
        status == 400 -> if (createAccount) "Не получилось создать аккаунт: проверь email и пароль." else "Не получилось войти: проверь email и пароль."
        status == 401 -> "Неверный email или пароль. Если аккаунта ещё нет — нажми «Создать аккаунт»."
        status == 409 -> "Этот email уже зарегистрирован — нажми «Войти»."
        code == "TOO_MANY_LOGIN_ATTEMPTS" -> "Слишком много неудачных попыток входа. Подожди 15 минут и попробуй снова."
        status == 429 -> "Слишком много попыток подряд. Подожди минуту и попробуй снова."
        status == 403 -> "Этот аккаунт заблокирован. Напиши в поддержку."
        status >= 500 -> "Сервер NOVA временно не отвечает. Попробуй ещё раз через минуту."
        else -> (if (createAccount) "Не удалось создать аккаунт" else "Не удалось войти") + " (код $status). Попробуй ещё раз."
    }

    fun signIn(createAccount: Boolean) {
        if (loading) return
        error = null
        if (!validateCredentials(createAccount)) return // Explained under the fields themselves.
        execute {
            val api = service()
            val credentials = Credentials(email.trim(), password, adultConfirmed && createAccount)
            val result = try { if (createAccount) api.register(credentials) else api.login(credentials) } catch (e: HttpException) {
                val code = serverErrorCode(e)
                when (code) { "EMAIL_INVALID" -> emailError = "Проверь email"; "PASSWORD_TOO_SHORT", "PASSWORD_TOO_LONG" -> passwordError = "Проверь пароль" }
                throw IllegalStateException(authErrorText(createAccount, e.code(), code))
            } catch (e: java.io.IOException) {
                throw IllegalStateException("Нет связи с сервером NOVA. Проверь интернет и попробуй снова — первый вход после перерыва может занять до минуты.")
            }
            accessToken = result.accessToken
            store.put("access", result.accessToken); store.put("refresh", result.refreshToken)
            store.put("server", serverUrl); store.put("email", email.trim())
            password = ""
            activeTab = NovaTab.HOME
            catalog = api.catalog(authorization()).items
            notification = if (createAccount) "Аккаунт создан. Добро пожаловать в NOVA AI!" else "Вход выполнен"
        }
    }

    /** A tab opened while another request runs is refreshed right after it, never silently skipped. */
    private var pendingTabRefresh: NovaTab? = null
    var productReturnTab = NovaTab.HOME
        private set
    fun changeTab(tab: NovaTab) {
        if (tab in setOf(NovaTab.WALLET, NovaTab.ART) && activeTab !in setOf(NovaTab.WALLET, NovaTab.ART)) {
            productReturnTab = activeTab
        }
        activeTab = tab
        if (loading) { pendingTabRefresh = tab; return }
        when (tab) {
            NovaTab.HOME -> refreshDiscover()
            NovaTab.CATALOG -> refreshCatalog()
            NovaTab.CHATS -> refreshChats()
            NovaTab.CREATE -> refreshOwned()
            NovaTab.ART -> refreshArt()
            NovaTab.WALLET -> refreshWallet()
            NovaTab.ACCOUNT -> refreshNotifications()
            else -> Unit
        }
    }

    internal var catalogView by mutableStateOf(CatalogView.PUBLIC)
        private set
    var catalogFailed by mutableStateOf(false)
        private set

    internal fun selectCatalogView(view: CatalogView) {
        if (catalogView != view) catalog = emptyList()
        catalogView = view
        refreshCatalog()
    }

    fun refreshCatalog() {
        execute {
            catalogFailed = false
            try {
                val api = service()
                val bearer = authorization()
                catalog = when (catalogView) {
                    CatalogView.PUBLIC -> api.catalog(
                        bearer = bearer,
                        query = catalogQuery.trim().ifEmpty { null },
                        category = catalogCategory.trim().ifEmpty { null },
                        tag = catalogTag.trim().ifEmpty { null },
                        sort = catalogSort,
                        section = if (matureSection && showMature) "mature" else "general"
                    ).items
                    CatalogView.FAVORITES -> api.favorites(bearer).items.map { it.character }.also { favorites = it }
                    CatalogView.FOLLOWING -> api.following(bearer).items
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: HttpException) {
                catalogFailed = true
                if (e.code() == 401) throw e
            } catch (_: Exception) {
                catalogFailed = true
            }
        }
    }

    var chatSearch by mutableStateOf("")
    /** Message being edited (own messages only) and its draft text. */
    var editingMessageId by mutableStateOf<String?>(null)
    var editingMessageText by mutableStateOf("")
    var inChatQuery by mutableStateOf("")
    var inChatResults by mutableStateOf<List<NovaChatMessage>?>(null)
        private set
    fun startEditMessage(m: NovaChatMessage) { if (m.role == "user") { editingMessageId = m.id; editingMessageText = m.content } }
    fun saveEditedMessage() {
        val chat = activeChat ?: return; val id = editingMessageId ?: return; val text = editingMessageText.trim()
        if (text.isEmpty() || text.length > 4000) { error = "Сообщение должно быть от 1 до 4000 символов"; return }
        execute {
            service().editMessage(authorization(), chat.id, id, EditMessageBody(text))
            editingMessageId = null
            chatMessages = service().chatMessages(authorization(), chat.id).items
            notification = "Сообщение изменено. Нажми «Другой ответ», чтобы персонаж ответил на новый текст."
        }
    }
    fun deleteMessage(m: NovaChatMessage) {
        val chat = activeChat ?: return
        execute {
            service().deleteMessage(authorization(), chat.id, m.id)
            chatMessages = service().chatMessages(authorization(), chat.id).items
            notification = if (m.role == "user") "Сообщение и ответ на него удалены" else "Ответ удалён"
        }
    }
    fun searchInChat() {
        val chat = activeChat ?: return; val q = inChatQuery.trim()
        if (q.isEmpty()) { inChatResults = null; return }
        execute { inChatResults = service().chatMessages(authorization(), chat.id, null, q.take(100)).items }
    }
    var profileCharacter by mutableStateOf<NovaCharacter?>(null)
    fun showCharacterProfile(id:String) {execute {profileCharacter=service().character(authorization(),id).character} }
    fun hideCharacter(id:String) {execute {service().hideCharacter(authorization(),id);owned=service().myCharacters(authorization()).items;notification="Персонаж скрыт из каталога"} }
    fun refreshChats() {
        execute {
            val api = service()
            chats = api.chats(authorization(),chatSearch.trim().takeIf{it.isNotEmpty()}).items
            aiStatus = api.aiStatus(authorization())
        }
    }

    fun portraitAuthorization(url: String): String? {
        val image = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        val server = runCatching { java.net.URI(serverUrl) }.getOrNull() ?: return null
        val sameOrigin = image.scheme == server.scheme && image.host != null &&
            image.host.equals(server.host, ignoreCase = true) && image.port == server.port
        if (!sameOrigin || image.userInfo != null || image.query != null ||
            !Regex("/media/[0-9a-f-]{36}\\.jpg").matches(image.path.orEmpty())) return null
        if (image.scheme != "https" && !(BuildConfig.DEBUG && image.host in setOf("127.0.0.1", "10.0.2.2", "localhost"))) return null
        return (store.get("access") ?: accessToken)?.let { "Bearer $it" }
    }

    fun chatPortraitUrl(characterId:String):String? =
        catalog.firstOrNull{it.id==characterId}?.avatarUrl ?: owned.firstOrNull{it.id==characterId}?.avatarUrl ?: store.get("portrait-$characterId")
    fun startStudioChat(hero:StudioHero) {
        if(!showMature || !matureSection){error="Включи раздел 18+ в настройках профиля";return}
        execute {
            val api=service()
            val character=api.createCharacter(authorization(),hero.draft()).character
            store.put("portrait-${character.id}",hero.portraitUrl)
            val created=api.createChat(authorization(),NovaCreateChatBody(character.id))
            activeChat=created.chat.copy(characterName=hero.name)
            store.put("chat",gson.toJson(activeChat));chatMessages=emptyList();chatInput="";nextBefore=null;memories=emptyList();inChatResults=null;activeTab=NovaTab.CHATS
            chats=api.chats(authorization()).items;aiStatus=api.aiStatus(authorization())
        }
    }
    fun startChat(character: NovaCharacter, scenario: CharacterScenario? = null) {
        execute {
            val api = service()
            val created = api.createChat(authorization(), NovaCreateChatBody(character.id, groupIds.filter { it != character.id }.take(4), if (scenario == null) storyScenario else "", playerPersona, scenario?.id))
            activeChat = created.chat.copy(characterAvatarUrl = created.chat.characterAvatarUrl ?: character.avatarUrl)
            chatGreeting = created.greeting?.takeIf { it.isNotBlank() } ?: character.greeting
            profileCharacter = null; characterMemories = emptyList(); memories = emptyList()
            store.put("chat", gson.toJson(activeChat))
            chatMessages = emptyList()
            chatInput = ""
            activeTab = NovaTab.CHATS
            chats = api.chats(authorization()).items
            aiStatus = api.aiStatus(authorization())
        }
    }

    fun openChat(chat: NovaChat) {
        execute {
            activeChat = chat
            store.put("chat", gson.toJson(chat))
            val page = service().chatMessages(authorization(), chat.id)
            chatMessages = page.items; nextBefore = page.nextBefore
            memories = service().memories(authorization(), chat.id).items
            chatGreeting = null
            characterMemories = runCatching { service().characterMemories(authorization(), chat.characterId).items }.getOrDefault(emptyList())
            aiStatus = service().aiStatus(authorization())
            playerPersona = chat.persona.orEmpty(); storyScenario = chat.scenario.orEmpty()
            responseStyle = chat.responseStyle ?: "balanced"; storyTone = chat.tone ?: "friendly"
            activeTab = NovaTab.CHATS
        }
    }

    fun closeChat() {
        activeChat = null
        store.put("chat", "null")
        chatMessages = emptyList()
        refreshChats()
    }

    fun sendChatMessage() {
        val current = activeChat ?: return
        val text = chatInput.trim()
        if (text.isBlank()) {
            error = "Напиши сообщение"
            return
        }
        if (text.length > 4000) {
            error = "Сообщение слишком длинное"
            return
        }
        val tier = if (premiumChatSelected) "premium" else "free"
        val fingerprint = current.id + ":" + tier + ":" + text
        if (pendingChatFingerprint != fingerprint || pendingChatRequestId == null) {
            pendingChatFingerprint = fingerprint
            pendingChatRequestId = java.util.UUID.randomUUID().toString()
            store.put("pendingId", pendingChatRequestId!!); store.put("pendingFingerprint", fingerprint)
            store.put("draft", text)
        }
        val requestId = checkNotNull(pendingChatRequestId)
        streamJob = execute {
            val api = service()
            try {
                val body = NovaChatSendBody(text, requestId, tier, if (tier == "premium") aiStatus?.premiumPriceTokens else null)
                // Streaming first; a server without the stream route (404) gets the classic JSON call.
                val result = try { streamReply(api, current.id, body) } catch (e: HttpException) {
                    if (e.code() != 404) throw e
                    streamingText = null
                    api.sendChatMessage(authorization(), current.id, body)
                }
                if (result.assistantMessage != null) {
                    pendingChatRequestId = null
                    pendingChatFingerprint = null
                    updateChatInput("")
                    store.put("pendingId", ""); store.put("pendingFingerprint", "")
                    chatMessages = api.chatMessages(authorization(), current.id).items
                    chats = api.chats(authorization()).items
                    aiStatus = api.aiStatus(authorization())
                } else {
                    error = "Ответ ещё обрабатывается. Повтори отправку."
                }
            } catch (e: HttpException) {
                // Preserve the ID on HTTP errors too: a proxy may fail after a committed response.
                if (e.code() == 409) aiStatus = runCatching { api.aiStatus(authorization()) }.getOrNull()
                throw e
            }
            // For network timeouts we deliberately retain requestId to avoid double charges
            // when retrying an earlier Premium request that may have succeeded server-side.
        }
    }

    fun deleteActiveChat() {
        val current = activeChat ?: return
        execute {
            val api = service()
            api.deleteChat(authorization(), current.id)
            activeChat = null
            chatMessages = emptyList()
            chats = api.chats(authorization()).items
            notification = "Переписка удалена"
        }
    }

    fun refreshOwned() {
        execute { owned = service().myCharacters(authorization()).items
            creatorStats = runCatching { service().creatorStats(authorization()) }.getOrNull()?.takeIf { it.totals != null && it.items != null } }
    }
    // Discovery: personal deck, themed shelves, public author pages, share links.
    var recommended by mutableStateOf<List<NovaCharacter>>(emptyList())
    var shelves by mutableStateOf<List<Shelf>>(emptyList())
    var authorPage by mutableStateOf<AuthorPage?>(null)
    var creatorStats by mutableStateOf<CreatorStats?>(null)
    var pendingLinkCharacterId by mutableStateOf<String?>(null)
    var recentStories by mutableStateOf<List<NovaChat>>(emptyList())
        private set
    var discoverLoading by mutableStateOf(false)
        private set
    var discoverFailed by mutableStateOf(false)
        private set
    private var discoverJob: Job? = null
    private val seenRecommendations = mutableSetOf<String>()

    fun refreshDiscover() {
        if (accessToken == null || discoverJob?.isActive == true) return
        discoverJob = viewModelScope.launch {
            discoverLoading = true
            discoverFailed = false
            try {
                val api = service()
                val bearer = authorization()
                loadDiscoverPart { recommended = api.recommended(bearer).items.filterNot { it.id in seenRecommendations } }
                loadDiscoverPart { shelves = api.shelves(bearer).collections.orEmpty() }
                loadDiscoverPart { recentStories = api.chats(bearer, null).items }
            } finally {
                discoverLoading = false
            }
        }
    }

    private suspend fun loadDiscoverPart(load: suspend () -> Unit) {
        try {
            load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            discoverFailed = true
        }
    }

    fun swipeRecommendation(character: NovaCharacter, liked: Boolean) {
        if (!liked) {
            dismissRecommendation(character.id)
            return
        }
        execute {
            service().addFavorite(authorization(), character.id)
            favorites = (favorites + character).distinctBy { it.id }
            dismissRecommendation(character.id)
            notification = "Добавлено в избранное"
        }
    }

    private fun dismissRecommendation(id: String) {
        seenRecommendations.add(id)
        recommended = recommended.filterNot { it.id == id }
    }
    fun openAuthor(id: String) { execute { authorPage = service().author(authorization(), id) } }
    fun shareLink(characterId: String): String = serverUrl.trim().trimEnd('/') + "/c/" + characterId
    fun openPendingLink() { val id = pendingLinkCharacterId ?: return; if (accessToken == null || loading) return; pendingLinkCharacterId = null; showCharacterProfile(id) }

    // Editor draft: saved on the phone while typing, restored after the app is closed or killed.
    private fun draftMap(): Map<String, String> = mapOf("name" to draftName, "greeting" to draftGreeting, "category" to draftCategory,
        "biography" to draftBiography, "personality" to draftPersonality, "appearance" to draftAppearance, "interests" to draftInterests,
        "backstory" to draftBackstory, "speechStyle" to draftSpeechStyle, "scenario" to draftScenario, "avatar" to draftAvatar,
        "tags" to draftTags, "language" to draftLanguage, "mature" to draftMature.toString(), "editing" to editingCharacterId.orEmpty(),
        "age" to (draftAge?.toString() ?: ""), "gender" to draftGender.orEmpty(), "traits" to draftTraits.joinToString("\u0002"))
    val draftSignature: String get() = draftMap().values.joinToString("\u0001")
    val draftHasContent: Boolean get() = listOf(draftName, draftGreeting, draftBiography, draftPersonality, draftAppearance, draftInterests,
        draftBackstory, draftSpeechStyle, draftScenario, draftTags, draftAvatar).any { it.isNotBlank() } || draftTraits.isNotEmpty() || draftAge != null
    fun persistDraft() { if (accessToken != null) store.put("characterDraft", if (draftHasContent) gson.toJson(draftMap()) else "") }
    private fun restoreDraft() {
        val saved = runCatching { gson.fromJson(store.get("characterDraft"), Map::class.java) }.getOrNull() ?: return
        fun v(k: String) = saved[k] as? String ?: ""
        draftName = v("name"); draftGreeting = v("greeting"); draftCategory = v("category").ifBlank { "fantasy" }
        draftBiography = v("biography"); draftPersonality = v("personality"); draftAppearance = v("appearance"); draftInterests = v("interests")
        draftBackstory = v("backstory"); draftSpeechStyle = v("speechStyle"); draftScenario = v("scenario"); draftAvatar = v("avatar")
        draftTags = v("tags"); draftLanguage = v("language").ifBlank { "ru" }; draftMature = v("mature") == "true"
        editingCharacterId = v("editing").ifBlank { null }
        draftAge = v("age").toIntOrNull()?.takeIf { it >= 18 }; draftGender = v("gender").ifBlank { null }
        draftTraits = v("traits").split('\u0002').filter { it.isNotBlank() }
    }
    fun clearDraft() {
        draftName = ""; draftGreeting = ""; draftCategory = "fantasy"; draftBiography = ""; draftPersonality = ""; draftAppearance = ""
        draftInterests = ""; draftBackstory = ""; draftSpeechStyle = ""; draftScenario = ""; draftAvatar = ""; draftTags = ""
        draftLanguage = "ru"; draftMature = false; editingCharacterId = null; draftAge = null; draftGender = null; draftTraits = emptyList()
        fieldUndo = emptyMap(); cardUndo = null; cardSuggestion = null; fieldSuggestion = null
        store.put("characterDraft", "")
    }
    /** Which required parts are still missing, in the order they appear in the editor. */
    fun missingForSave(): List<String> = listOfNotNull(
        "имя".takeIf { draftName.isBlank() }, "возраст".takeIf { draftAge == null }, "пол".takeIf { draftGender == null },
        "первое сообщение".takeIf { draftGreeting.isBlank() })
    var lastCreated by mutableStateOf<NovaCharacter?>(null)
    fun createCharacter() {
        val missing = missingForSave()
        if (missing.isNotEmpty()) { error = "Чтобы сохранить, заполни: " + missing.joinToString(", "); return }
        execute {
            val draft = CharacterDraft(name=draftName.trim(), greeting=draftGreeting.trim(), category=draftCategory.trim().ifBlank { "Приключения" }.take(50),
                tags=draftTags.split(',').map { it.trim().removePrefix("#").take(40) }.filter { it.isNotBlank() }.take(10), language=draftLanguage,
                biography=draftBiography.take(3000), personality=draftPersonality.take(1500), appearance=draftAppearance.take(1000), interests=draftInterests.take(500),
                backstory=draftBackstory.take(3000), speechStyle=draftSpeechStyle.take(500), scenario=draftScenario.take(2000),
                avatarUrl=draftAvatar.takeIf { it.startsWith("https://") }, mature=draftMature,
                age=draftAge, gender=draftGender, traits=draftTraits.map { it.take(30) }.take(12))
            val editing = editingCharacterId
            val saved = if (editing == null) service().createCharacter(authorization(), draft).character else { service().editCharacter(authorization(), editing, draft); null }
            editingCharacterId = null
            owned = service().myCharacters(authorization()).items
            lastCreated = saved ?: owned.firstOrNull { it.id == editing }
            clearDraft()
            notification = if (editing == null) "Персонаж создан и сохранён" else "Изменения сохранены"
        }
    }

    // ---- Smart creator: AI suggestions never overwrite the user's text without an explicit tap. ----
    data class FieldSuggestion(val field: String, val action: String, val variants: List<String>)
    var cardSuggestion by mutableStateOf<AssistCard?>(null)
    var cardNeeds by mutableStateOf<List<String>>(emptyList())
    var fieldSuggestion by mutableStateOf<FieldSuggestion?>(null)
    var assistBusy by mutableStateOf<String?>(null) // "card" or a field key while the AI works
    var lastIdea by mutableStateOf("")
    var fieldUndo by mutableStateOf<Map<String, String>>(emptyMap()) // field -> text before the last accepted suggestion
    var cardUndo by mutableStateOf<Map<String, String>?>(null)        // whole card before "apply to the whole card"
    val cardFields = listOf("name", "biography", "personality", "interests", "appearance", "speechStyle", "backstory", "scenario", "greeting")
    fun draftField(key: String): String = when (key) {
        "name" -> draftName; "biography" -> draftBiography; "personality" -> draftPersonality; "interests" -> draftInterests
        "appearance" -> draftAppearance; "speechStyle" -> draftSpeechStyle; "backstory" -> draftBackstory; "scenario" -> draftScenario
        "greeting" -> draftGreeting; "tags" -> draftTags; "category" -> draftCategory; "traits" -> draftTraits.joinToString("\u0002"); else -> "" }
    fun setDraftField(key: String, value: String) { when (key) {
        "name" -> draftName = value; "biography" -> draftBiography = value; "personality" -> draftPersonality = value; "interests" -> draftInterests = value
        "appearance" -> draftAppearance = value; "speechStyle" -> draftSpeechStyle = value; "backstory" -> draftBackstory = value
        "scenario" -> draftScenario = value; "greeting" -> draftGreeting = value; "tags" -> draftTags = value; "category" -> draftCategory = value
        "traits" -> draftTraits = value.split('\u0002').filter { it.isNotBlank() } } }
    private fun currentCard() = AssistCurrent(name = draftName.trim().ifBlank { null }, age = draftAge, gender = draftGender,
        traits = draftTraits.takeIf { it.isNotEmpty() }, biography = draftBiography.ifBlank { null }, personality = draftPersonality.ifBlank { null },
        interests = draftInterests.ifBlank { null }, appearance = draftAppearance.ifBlank { null }, speechStyle = draftSpeechStyle.ifBlank { null },
        backstory = draftBackstory.ifBlank { null }, scenario = draftScenario.ifBlank { null }, greeting = draftGreeting.ifBlank { null },
        category = draftCategory.ifBlank { null }?.takeIf { it != "fantasy" }, tags = draftTags.split(',').map { it.trim() }.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() })
    private fun assistErrorText(e: HttpException): String {
        val code = runCatching { gson.fromJson(e.response()?.errorBody()?.string(), Map::class.java)["error"] as? String }.getOrNull()
        return when (code) {
            "CREATOR_REQUEST_REJECTED" -> "AI не создаёт несовершеннолетних и откровенных персонажей. Измени описание."
            "CREATOR_RESULT_REJECTED" -> "AI предложил неподходящий текст. Нажми «Ещё вариант»."
            "CREATOR_DAILY_LIMIT" -> "На сегодня подсказки AI закончились. Завтра будут снова — а поля можно заполнить и вручную."
            "FIELD_EMPTY" -> "В поле пока нет текста. Выбери «Написать с нуля»."
            "AI_BAD_FORMAT" -> "AI ответил неразборчиво. Попробуй ещё раз."
            "INVALID_ASSIST_INPUT" -> "Опиши идею хотя бы парой слов (до 500 символов)."
            else -> if (e.code() == 429) "AI сейчас занят. Подожди минуту и попробуй снова." else "AI временно недоступен. Твой текст не изменён — попробуй чуть позже."
        }
    }
    private fun assistCall(target: String, request: AssistRequest, onResult: (AssistResponse) -> Unit) {
        if (assistBusy != null) return
        assistBusy = target; error = null
        viewModelScope.launch {
            try { onResult(service().assist(authorization(), request)) }
            catch (e: HttpException) { error = assistErrorText(e); if (e.code() == 401) accessToken = null }
            catch (e: java.io.IOException) { error = "Нет связи с сервером NOVA. Твой текст не изменён." }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = "AI временно недоступен. Твой текст не изменён." }
            finally { assistBusy = null }
        }
    }
    fun assistFull(idea: String) {
        val text = idea.trim()
        if (text.length < 3) { error = "Опиши идею хотя бы парой слов"; return }
        lastIdea = text
        assistCall("card", AssistRequest("full", idea = text, current = currentCard(), language = NovaLang.code)) { r ->
            cardSuggestion = r.card; cardNeeds = r.needs.orEmpty() }
    }
    fun assistField(field: String, action: String) {
        assistCall(field, AssistRequest("field", field = field, action = action, current = currentCard(), language = NovaLang.code)) { r ->
            fieldSuggestion = r.variants?.takeIf { it.isNotEmpty() }?.let { FieldSuggestion(field, action, it) } }
    }
    /** "Accept" (possibly after editing the suggested text): the previous text is kept for one-tap undo. */
    fun acceptField(field: String, text: String) {
        fieldUndo = fieldUndo + (field to draftField(field)); setDraftField(field, text.trim()); fieldSuggestion = null
    }
    fun undoField(field: String) { fieldUndo[field]?.let { setDraftField(field, it) }; fieldUndo = fieldUndo - field }
    fun applyCard() {
        val c = cardSuggestion ?: return
        cardUndo = (cardFields + listOf("tags", "category", "traits")).associateWith { draftField(it) }
        for (k in cardFields) { val v = when (k) { "name" -> c.name; "biography" -> c.biography; "personality" -> c.personality; "interests" -> c.interests
            "appearance" -> c.appearance; "speechStyle" -> c.speechStyle; "backstory" -> c.backstory; "scenario" -> c.scenario; else -> c.greeting }
            if (v.isNotBlank()) setDraftField(k, v) }
        c.tags?.takeIf { it.isNotEmpty() }?.let { draftTags = it.joinToString(", ") }
        c.category?.takeIf { it.isNotBlank() }?.let { draftCategory = it }
        if (draftTraits.isEmpty()) c.traits?.let { draftTraits = it.take(6) }
        cardSuggestion = null; fieldUndo = emptyMap()
        notification = if (cardNeeds.isNotEmpty()) "Карточка заполнена. Выбери возраст и пол — AI их не выбирает за тебя." else "Карточка заполнена. Проверь и поправь любые поля."
    }
    fun undoCard() { cardUndo?.forEach { (k, v) -> setDraftField(k, v) }; cardUndo = null }

    // ---- Character portrait: own photo (with consent), AI portrait, or a ready avatar. ----
    var avatarBusy by mutableStateOf(false)
    /** Shrinks to at most 512 px JPEG on the phone; only that small file is uploaded, never to an AI provider. */
    private fun toAvatarJpeg(bitmap: android.graphics.Bitmap): ByteArray {
        val scale = minOf(1f, 512f / maxOf(bitmap.width, bitmap.height))
        val sized = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
        var quality = 88
        while (true) {
            val out = java.io.ByteArrayOutputStream(); sized.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() <= 190_000 || quality <= 40) return out.toByteArray()
            quality -= 12
        }
    }
    suspend fun uploadAvatarBitmap(bitmap: android.graphics.Bitmap, source: String): String {
        val jpeg = withContext(Dispatchers.Default) { toAvatarJpeg(bitmap) }
        val result = service().uploadAvatar(authorization(), AvatarUpload(android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP), source, true))
        draftAvatar = result.url
        persistDraft()
        return result.url
    }
    fun uploadCroppedPortrait(bitmap: android.graphics.Bitmap, crop: androidx.compose.ui.unit.IntRect) {
        if (avatarBusy) return
        avatarBusy = true
        error = null
        viewModelScope.launch {
            try {
                val portrait = withContext(Dispatchers.Default) {
                    android.graphics.Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.width, crop.height)
                }
                uploadAvatarBitmap(portrait, "upload")
                persistDraft()
                notification = "Фото персонажа сохранено"
            } catch (e: HttpException) {
                error = when (e.code()) {
                    413 -> "Фото слишком большое. Выбери другое."
                    409 -> "Достигнут лимит портретов. Освободи место и попробуй снова."
                    else -> "Не удалось сохранить фото. Попробуй ещё раз."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error = "Не удалось сохранить фото. Проверь подключение и попробуй снова."
            } finally {
                avatarBusy = false
            }
        }
    }

    /** Uses the AI Art allowance (3 free a day) with a prompt built from the card's appearance. */
    fun avatarFromAi() {
        if (avatarBusy) return
        val looks = listOfNotNull(draftName.trim().ifBlank { null }, draftAge?.let { "$it лет" },
            when (draftGender) { "female" -> "женщина"; "male" -> "мужчина"; else -> null }, draftAppearance.trim().ifBlank { null }).joinToString(", ")
        if (draftAppearance.isBlank()) { error = "Сначала опиши внешность (или заполни карточку с AI) — по ней рисуется портрет."; return }
        avatarBusy = true; error = null
        viewModelScope.launch {
            try {
                val art = service().createArt(authorization(), ArtRequest(("Портрет взрослого персонажа: $looks").take(600), "portrait", java.util.UUID.randomUUID().toString()))
                val bytes = android.util.Base64.decode(requireNotNull(art.image), android.util.Base64.DEFAULT)
                val bitmap = withContext(Dispatchers.Default) { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } ?: throw IllegalStateException()
                uploadAvatarBitmap(bitmap, "ai")
                notification = if (art.chargedTokens > 0) "Портрет готов · списано ${art.chargedTokens} ◈" else "Портрет готов! Бесплатных картинок сегодня: ${art.freeLeft}"
            } catch (e: HttpException) {
                val code = runCatching { gson.fromJson(e.response()?.errorBody()?.string(), Map::class.java)["error"] as? String }.getOrNull()
                error = when (code) {
                    "ART_PROMPT_REJECTED" -> "По такому описанию портрет нарисовать нельзя. Измени внешность в карточке."
                    "INSUFFICIENT_TOKENS" -> "Бесплатные картинки на сегодня закончились, а токенов не хватает. Выбери фото или готовый аватар."
                    else -> "Портрет сейчас не нарисовать. Попробуй позже или выбери фото."
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = "Портрет сейчас не нарисовать. Попробуй позже или выбери фото." }
            finally { avatarBusy = false }
        }
    }

    fun addFavorite(id: String) {
        execute {
            service().addFavorite(authorization(), id)
            notification = "Персонаж добавлен в избранное"
        }
    }

    var dailyBonus by mutableStateOf<DailyBonusStatus?>(null)
        private set
    var referral by mutableStateOf<ReferralStatus?>(null)
        private set
    var referralInput by mutableStateOf("")

    fun refreshWallet() {
        execute {
            val api = service()
            wallet = api.wallet(authorization())
            transactions = api.transactions(authorization()).items
            products = api.products(authorization()); ads = api.adStatus(authorization())
            // Older servers have no reward routes: the wallet must still load.
            dailyBonus = runCatching { api.dailyBonus(authorization()) }.getOrNull()
            referral = runCatching { api.referrals(authorization()) }.getOrNull()
        }
    }
    fun openArt(prompt: String? = null) {
        prompt?.let { artPrompt = it.take(600) }
        profileCharacter = null
        changeTab(NovaTab.ART)
    }
    fun refreshArt() {
        artGallery = artDir.listFiles { f -> f.extension == "jpg" }?.sortedByDescending { it.name }.orEmpty()
        execute { artStatus = service().artStatus(authorization()) }
    }
    fun generateArt() {
        val prompt = artPrompt.trim()
        if (prompt.length < 3) { error = "Опиши картинку хотя бы парой слов"; return }
        execute {
            val api = service()
            val result = try { api.createArt(authorization(), ArtRequest(prompt, artStyle, java.util.UUID.randomUUID().toString())) }
            catch (e: HttpException) {
                val code = runCatching { gson.fromJson(e.response()?.errorBody()?.string(), Map::class.java)["error"] as? String }.getOrNull()
                throw IllegalStateException(when (code) {
                    "ART_PROMPT_REJECTED" -> "Такую картинку нарисовать нельзя: без обнажённой натуры, детей, реальных людей и жестокости. Измени описание."
                    "INSUFFICIENT_TOKENS" -> "Бесплатные картинки на сегодня закончились, а токенов не хватает. Забери ежедневный бонус или пригласи друга."
                    "ART_DAILY_LIMIT" -> "Сегодня художник нарисовал слишком много. Попробуй завтра."
                    else -> if (e.code() == 429) "Слишком часто. Подожди минуту и попробуй снова." else "Рисование временно недоступно. Токены не списаны — попробуй чуть позже."
                })
            }
            val bytes = android.util.Base64.decode(requireNotNull(result.image) { "Пустая картинка" }, android.util.Base64.DEFAULT)
            val file = withContext(Dispatchers.IO) {
                java.io.File(artDir, "art-" + System.currentTimeMillis() + ".jpg").also { it.writeBytes(bytes) }
            }
            artLatest = file
            artGallery = (listOf(file) + artGallery).take(40)
            artDir.listFiles()?.sortedByDescending { it.name }?.drop(40)?.forEach { it.delete() }
            artStatus = artStatus?.copy(freeLeft = result.freeLeft)
            notification = if (result.chargedTokens > 0) "Готово · списано ${result.chargedTokens} ◈" else "Готово! Бесплатных на сегодня: ${result.freeLeft}"
            if (result.chargedTokens > 0) runCatching { wallet = api.wallet(authorization()) }
        }
    }
    fun deleteArt(file: java.io.File) {
        file.delete(); artGallery = artGallery - file; if (artLatest == file) artLatest = null
    }

    fun claimDailyBonus() {
        execute {
            val api = service()
            val result = api.claimDailyBonus(authorization())
            notification = if (result.granted) "+" + result.amount + " NOVA Tokens — ежедневный бонус" else "Бонус за сегодня уже получен"
            dailyBonus = api.dailyBonus(authorization()); wallet = api.wallet(authorization())
            transactions = api.transactions(authorization()).items
        }
    }
    fun applyReferralCode() {
        val code = referralInput.trim().uppercase()
        if (!Regex("^[A-Z0-9]{8}$").matches(code)) { error = "Код приглашения — 8 букв и цифр"; return }
        execute {
            val api = service()
            try {
                val r = api.applyReferral(authorization(), ApplyReferralBody(code))
                referralInput = ""
                notification = if (r.rewarded) "Бонус за приглашение начислен" else "Код принят. Бонус придёт обоим после " + r.requiredTurns + " сообщений в чате"
            } catch (e: HttpException) {
                val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull().orEmpty()
                error = when {
                    "OWN_CODE" in body -> "Нельзя применить свой собственный код"
                    "ALREADY_APPLIED" in body -> "Код приглашения уже применён"
                    "APPLY_WINDOW_CLOSED" in body -> "Код можно применить только в первые 7 дней после регистрации"
                    "CIRCULAR_REFERRAL" in body -> "Этот друг уже зарегистрировался по твоему коду"
                    e.code() == 404 -> "Такого кода нет"
                    else -> "Не удалось применить код: HTTP " + e.code()
                }
                return@execute
            }
            referral = api.referrals(authorization()); wallet = api.wallet(authorization())
        }
    }

    private fun clearAccount() {
        store.clear()
        discoverJob?.cancel()
        discoverJob = null
        seenRecommendations.clear()
        recommended = emptyList()
        shelves = emptyList()
        recentStories = emptyList()
        discoverFailed = false
        authorPage = null
        profileCharacter = null
        dailyBonus = null
        referral = null
        productReturnTab = NovaTab.HOME
        memories=emptyList();favorites=emptyList();notifications=emptyList();products=null;ads=null;exportText=null;groupIds=emptySet()
        pendingChatRequestId = null
        pendingChatFingerprint = null
        accessToken = null
        email = ""
        password = ""
        catalog = emptyList()
        catalogView = CatalogView.PUBLIC
        catalogFailed = false
        catalogQuery = ""
        catalogCategory = ""
        catalogTag = ""
        catalogSort = "recent"
        owned = emptyList()
        chats = emptyList()
        activeChat = null
        chatMessages = emptyList()
        chatInput = ""
        premiumChatSelected = false
        aiStatus = null
        wallet = null
        transactions = emptyList()
        activeTab = NovaTab.HOME
        notification = null
        error = null
    }
    /** Sign-out never waits for (or is blocked by) another request: the phone forgets the session at once,
     *  and the server session is revoked in the background. */
    fun logout() {
        val bearer = runCatching { authorization() }.getOrNull()
        val api = runCatching { service() }.getOrNull()
        clearAccount(); loading = false
        if (bearer != null && api != null) viewModelScope.launch { runCatching { api.logout(bearer) } }
    }
    fun loadOlder() { val chat = activeChat ?: return; val before = nextBefore ?: return; execute {
        val page=service().chatMessages(authorization(),chat.id,before); chatMessages=page.items+chatMessages; nextBefore=page.nextBefore
    } }
    fun removeFavorite(id: String) { execute { service().removeFavorite(authorization(),id); favorites=favorites.filter { it.id!=id }; catalog=catalog.filter { it.id!=id } } }
    fun editCharacter(character: NovaCharacter) { execute {
        val c=service().character(authorization(),character.id).character
        editingCharacterId=c.id; draftName=c.name; draftGreeting=c.greeting; draftCategory=c.category
        draftBiography=c.biography.orEmpty(); draftPersonality=c.personality.orEmpty(); draftAppearance=c.appearance.orEmpty()
        draftInterests=c.interests.orEmpty(); draftBackstory=c.backstory.orEmpty(); draftSpeechStyle=c.speechStyle.orEmpty()
        draftScenario=c.scenario.orEmpty(); draftAvatar=c.avatarUrl.orEmpty(); draftTags=c.tags.joinToString(", "); draftLanguage=c.language; draftMature=c.mature
        draftAge=c.age; draftGender=c.gender; draftTraits=c.traits.orEmpty(); fieldUndo=emptyMap(); cardUndo=null
        activeTab=NovaTab.CREATE
    } }
    fun publishCharacter(id: String) { execute { service().publish(authorization(),id); notification="Персонаж отправлен на модерацию" } }
    fun cloneCharacter(id: String) { execute { service().clone(authorization(),id); owned=service().myCharacters(authorization()).items; activeTab=NovaTab.CREATE } }
    fun saveMemory() { val chat=activeChat?:return; execute {
        val body=MemoryBody(memoryInput)
        val editing=editingMemoryId
        if(editing==null) service().addMemory(authorization(),chat.id,body) else service().editMemory(authorization(),chat.id,editing,body)
        memories=service().memories(authorization(),chat.id).items; memoryInput=""; editingMemoryId=null
    } }
    fun pinMemory(memory:MemoryItem) { val chat=activeChat?:return; execute {
        service().editMemory(authorization(),chat.id,memory.id,MemoryBody(memory.content,if(memory.kind=="SUMMARY") "FACT" else memory.kind,!memory.pinned))
        memories=service().memories(authorization(),chat.id).items
    } }
    fun deleteMemory(id:String) {val chat=activeChat?:return; execute {service().deleteMemory(authorization(),chat.id,id);memories=service().memories(authorization(),chat.id).items} }
    fun branch(message:NovaChatMessage) {val chat=activeChat?:return;execute {
        val created=service().branch(authorization(),chat.id,BranchBody(message.id)).chat
        activeChat=created;store.put("chat",gson.toJson(created));chatMessages=service().chatMessages(authorization(),created.id).items
        memories=service().memories(authorization(),created.id).items;notification="Создана отдельная ветка истории"
    } }
    // Group cast, lasting character memory and author scenarios.
    var chatGreeting by mutableStateOf<String?>(null)
    var characterMemories by mutableStateOf<List<CharacterMemoryItem>>(emptyList())
    var characterMemoryInput by mutableStateOf("")
    var scenarios by mutableStateOf<List<CharacterScenario>>(emptyList())
    var scenarioTitle by mutableStateOf("")
    var scenarioText by mutableStateOf("")
    fun characterName(id: String): String = (catalog + owned + favorites).firstOrNull { it.id == id }?.name ?: "Персонаж"
    /** Characters that can be invited into the open chat (everything the user currently sees). */
    fun castCandidates(): List<NovaCharacter> = (catalog + owned + favorites).distinctBy { it.id }.filter { it.id != activeChat?.characterId }
    fun toggleCastMember(id: String) { val chat = activeChat ?: return
        val now = chat.participantIds.orEmpty()
        val next = if (id in now) now - id else if (now.size < 4) now + id else { error = "В сцене может быть до 5 персонажей"; return }
        execute {
            service().saveCast(authorization(), chat.id, ChatCastBody(next))
            activeChat = chat.copy(participantIds = next); store.put("chat", gson.toJson(activeChat))
            notification = if (id in now) "Персонаж вышел из сцены" else "Персонаж присоединился к сцене"
        } }
    fun addCharacterMemory() { val chat = activeChat ?: return; val text = characterMemoryInput.trim(); if (text.isEmpty()) return
        execute { service().addCharacterMemory(authorization(), chat.characterId, CharacterMemoryBody(text))
            characterMemories = service().characterMemories(authorization(), chat.characterId).items; characterMemoryInput = "" } }
    fun deleteCharacterMemory(id: String) { val chat = activeChat ?: return
        execute { service().deleteCharacterMemory(authorization(), chat.characterId, id)
            characterMemories = service().characterMemories(authorization(), chat.characterId).items } }
    fun loadScenarios(characterId: String) { scenarios = emptyList()
        viewModelScope.launch { scenarios = runCatching { service().scenarios(authorization(), characterId).items }.getOrDefault(emptyList()) } }
    fun addScenario(characterId: String) { val title = scenarioTitle.trim(); val text = scenarioText.trim()
        if (title.isEmpty() || text.isEmpty()) { error = "Заполни название и описание сценария"; return }
        execute { service().addScenario(authorization(), characterId, ScenarioBody(title, text))
            scenarios = service().scenarios(authorization(), characterId).items; scenarioTitle = ""; scenarioText = ""; notification = "Сценарий добавлен" } }
    fun deleteScenario(characterId: String, id: String) {
        execute { service().deleteScenario(authorization(), characterId, id); scenarios = service().scenarios(authorization(), characterId).items } }
    fun saveStorySettings() {val chat=activeChat?:return;execute {
        service().saveSettings(authorization(),chat.id,ChatSettings(playerPersona,storyScenario,responseStyle,storyTone))
        activeChat=chat.copy(persona=playerPersona,scenario=storyScenario,responseStyle=responseStyle,tone=storyTone)
        store.put("chat",gson.toJson(activeChat));notification="Настройки истории сохранены"
    } }
    fun refreshNotifications() {execute {notifications=service().notifications(authorization()).items} }
    fun readNotification(id:String) {execute {service().readNotification(authorization(),id);notifications=service().notifications(authorization()).items} }
    fun reportMessage(id:String) {execute {service().report(authorization(),ReportBody("MESSAGE",id,"Жалоба пользователя на ответ AI"));notification="Жалоба отправлена"} }
    /** New version of the latest AI reply. Free in free mode; Premium is charged once, only on success. */
    fun regenerate(message: NovaChatMessage) {
        val current = activeChat ?: return
        if (message.role != "assistant" || chatMessages.lastOrNull()?.id != message.id) return
        val tier = if (premiumChatSelected) "premium" else "free"
        val key = "${current.id}:${message.id}:$tier"
        val pending = store.get("pendingRegenerate")?.split("|", limit=2)
        val requestId = if (pending?.size == 2 && pending[0] == key) pending[1] else java.util.UUID.randomUUID().toString()
        store.put("pendingRegenerate", "$key|$requestId")
        execute {
            val api = service()
            api.sendChatMessage(authorization(), current.id,
                NovaChatSendBody(null, requestId, tier, if (tier == "premium") aiStatus?.premiumPriceTokens else null, message.id))
            store.put("pendingRegenerate", "")
            chatMessages = api.chatMessages(authorization(), current.id).items
            aiStatus = api.aiStatus(authorization())
            notification = if (tier == "premium") "Новый Premium-ответ готов" else "Новый ответ готов"
        }
    }
    fun sendSupport() {execute {service().report(authorization(),ReportBody("SUPPORT","support",supportText));supportText="";notification="Обращение сохранено"} }
    fun exportAccount() {execute {exportText=service().export(authorization()).string()} }
    fun deleteAccount() {execute {service().deleteAccount(authorization(),mapOf("confirmation" to "DELETE"));clearAccount()} }
    suspend fun verifyPurchase(sku:String,token:String):Boolean = service().verifyPurchase(authorization(),VerifyPurchaseBody(sku,token)).status=="VERIFIED"
    fun showNotice(text:String) {notification=text}
    fun dismissMessages() { error = null; notification = null }
    /** Auto-hide only the notice that was shown, never a newer one. */
    fun dismissNotice(text: String) { if (notification == text) notification = null }

    fun follow(id:String,enabled:Boolean) {execute {if(enabled)service().follow(authorization(),id)else service().unfollow(authorization(),id);notification=if(enabled)"Подписка сохранена" else "Подписка отменена"} }
    fun like(id:String) {execute {service().like(authorization(),id);notification="Отметка сохранена"} }
    fun openComments(id:String) {execute {selectedPublication=id;comments=service().comments(authorization(),id).items} }
    fun sendComment() {val id=selectedPublication?:return;execute {service().comment(authorization(),id,mapOf("content" to commentInput));commentInput="";comments=service().comments(authorization(),id).items;notification="Комментарий отправлен на модерацию"} }
    fun fictionalScene(kind:String) {updateChatInput("Напиши $kind нашего персонажа. Начни с пометки «Вымышленная сцена». Это художественное произведение, а не реальные мысли или данные человека.");sendChatMessage()}

}
