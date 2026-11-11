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

enum class NovaTab { HOME, CATALOG, CHATS, CREATE, WALLET, ACCOUNT }

class NovaViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SessionStore(application)
    private val gson = Gson()
    private val refreshLock = Any()
    var adultConfirmed by mutableStateOf(false)
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
    var draftName by mutableStateOf("")
    var draftGreeting by mutableStateOf("")
    var draftCategory by mutableStateOf("fantasy")

    init {
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
                        if (next == null) null else {
                            store.put("access", next.accessToken); store.put("refresh", next.refreshToken)
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
    fun stopReply() { streamJob?.cancel() }

    private class StreamError(val status: Int, val code: String?) : Exception(code)
    /** Reads SSE from the stream route and returns the final "done" payload. */
    private suspend fun streamReply(api: NovaApi, chatId: String, body: NovaChatSendBody): NovaChatSendResponse {
        streamingText = ""
        val response = api.sendChatMessageStream(authorization(), chatId, body)
        return withContext(Dispatchers.IO) {
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
        }
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
                if (streamingText != null) notification = "Ответ остановлен. Токены не списаны, сообщение сохранено в поле ввода."
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
                streamingText = null
            }
        }
    }

    fun signIn(createAccount: Boolean) {
        execute {
            val api = service()
            if (createAccount && !adultConfirmed) throw IllegalArgumentException("Подтверди, что тебе исполнилось 18 лет")
            val credentials = Credentials(email.trim(), password, adultConfirmed)
            val result = if (createAccount) api.register(credentials) else api.login(credentials)
            accessToken = result.accessToken
            store.put("access", result.accessToken); store.put("refresh", result.refreshToken)
            store.put("server", serverUrl); store.put("email", email)
            password = ""
            activeTab = NovaTab.HOME
            catalog = api.catalog(authorization()).items
            notification = "Подключено к NOVA AI"
        }
    }

    fun changeTab(tab: NovaTab) {
        activeTab = tab
        when (tab) {
            NovaTab.CATALOG -> refreshCatalog()
            NovaTab.CHATS -> refreshChats()
            NovaTab.CREATE -> refreshOwned()
            NovaTab.WALLET -> refreshWallet()
            NovaTab.ACCOUNT -> refreshNotifications()
            else -> Unit
        }
    }

    fun refreshCatalog() {
        execute {
            catalog = service().catalog(
                bearer = authorization(),
                query = catalogQuery.trim().takeIf { it.isNotEmpty() },
                category = catalogCategory.trim().takeIf { it.isNotEmpty() },
                tag = catalogTag.trim().takeIf { it.isNotEmpty() },
                sort = catalogSort
            ).items
        }
    }

    fun refreshChats() {
        execute {
            val api = service()
            chats = api.chats(authorization()).items
            aiStatus = api.aiStatus(authorization())
        }
    }

    fun startChat(character: NovaCharacter) {
        execute {
            val api = service()
            val created = api.createChat(authorization(), NovaCreateChatBody(character.id, groupIds.filter { it != character.id }.take(4), storyScenario, playerPersona))
            activeChat = created.chat
            store.put("chat", gson.toJson(created.chat))
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
            aiStatus = service().aiStatus(authorization())
            playerPersona = chat.persona; storyScenario = chat.scenario
            responseStyle = chat.responseStyle; storyTone = chat.tone
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
        execute { owned = service().myCharacters(authorization()).items }
    }

    fun createCharacter() {
        if (draftName.isBlank() || draftGreeting.isBlank()) {
            error = "Заполни имя и приветствие персонажа"
            return
        }
        execute {
            val draft = CharacterDraft(name=draftName.trim(), greeting=draftGreeting.trim(), category=draftCategory.trim().ifBlank { "Приключения" },
                tags=draftTags.split(',').map { it.trim() }.filter { it.isNotBlank() }.take(10), language=draftLanguage,
                biography=draftBiography, personality=draftPersonality, appearance=draftAppearance, interests=draftInterests,
                backstory=draftBackstory, speechStyle=draftSpeechStyle, scenario=draftScenario, avatarUrl=draftAvatar.takeIf { it.isNotBlank() })
            val editing = editingCharacterId
            if (editing == null) service().createCharacter(authorization(), draft) else service().editCharacter(authorization(), editing, draft)
            editingCharacterId = null
            owned = service().myCharacters(authorization()).items
            draftName = ""
            draftGreeting = ""
            notification = "Персонаж создан и сохранён"
        }
    }

    fun addFavorite(id: String) {
        execute {
            service().addFavorite(authorization(), id)
            notification = "Персонаж добавлен в избранное"
        }
    }

    fun refreshWallet() {
        execute {
            val api = service()
            wallet = api.wallet(authorization())
            transactions = api.transactions(authorization()).items
            products = api.products(authorization()); ads = api.adStatus(authorization())
        }
    }

    private fun clearAccount() {
        store.clear()
        memories=emptyList();favorites=emptyList();notifications=emptyList();products=null;ads=null;exportText=null;groupIds=emptySet()
        pendingChatRequestId = null
        pendingChatFingerprint = null
        accessToken = null
        email = ""
        password = ""
        catalog = emptyList()
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
    fun logout() { execute { runCatching { service().logout(authorization()) }; clearAccount() } }
    fun loadOlder() { val chat = activeChat ?: return; val before = nextBefore ?: return; execute {
        val page=service().chatMessages(authorization(),chat.id,before); chatMessages=page.items+chatMessages; nextBefore=page.nextBefore
    } }
    fun loadFavorites() { execute { favorites=service().favorites(authorization()).items.map { it.character }; catalog=favorites } }
    fun removeFavorite(id: String) { execute { service().removeFavorite(authorization(),id); favorites=favorites.filter { it.id!=id }; catalog=catalog.filter { it.id!=id } } }
    fun editCharacter(character: NovaCharacter) { execute {
        val c=service().character(authorization(),character.id).character
        editingCharacterId=c.id; draftName=c.name; draftGreeting=c.greeting; draftCategory=c.category
        draftBiography=c.biography.orEmpty(); draftPersonality=c.personality.orEmpty(); draftAppearance=c.appearance.orEmpty()
        draftInterests=c.interests.orEmpty(); draftBackstory=c.backstory.orEmpty(); draftSpeechStyle=c.speechStyle.orEmpty()
        draftScenario=c.scenario.orEmpty(); draftAvatar=c.avatarUrl.orEmpty(); draftTags=c.tags.joinToString(", "); draftLanguage=c.language
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
        val requestId = java.util.UUID.randomUUID().toString()
        execute {
            val api = service()
            api.sendChatMessage(authorization(), current.id,
                NovaChatSendBody(null, requestId, tier, if (tier == "premium") aiStatus?.premiumPriceTokens else null, message.id))
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

}
