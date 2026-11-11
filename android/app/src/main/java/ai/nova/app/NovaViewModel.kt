package ai.nova.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

enum class NovaTab { HOME, CATALOG, CHATS, CREATE, WALLET, ACCOUNT }

class NovaViewModel : ViewModel() {
    var serverUrl by mutableStateOf("https://api.example.invalid/")
    var email by mutableStateOf("")
    var password by mutableStateOf("")
    var accessToken by mutableStateOf<String?>(null)
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
    var activeChat by mutableStateOf<NovaChat?>(null)
        private set
    var chatMessages by mutableStateOf<List<NovaChatMessage>>(emptyList())
        private set
    var chatInput by mutableStateOf("")
    var premiumChatSelected by mutableStateOf(false)
    private var pendingChatFingerprint: String? = null
    private var pendingChatRequestId: String? = null
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

    private fun service(): NovaApi {
        val url = serverUrl.trim().trimEnd('/') + "/"
        require(url.startsWith("https://") && !url.contains("api.example.invalid")) {
            "Укажи реальный HTTPS-адрес сервера NOVA AI"
        }
        return Retrofit.Builder()
            .baseUrl(url)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(NovaApi::class.java)
    }

    private fun authorization(): String =
        "Bearer " + requireNotNull(accessToken) { "Сначала войди в аккаунт" }

    private fun execute(block: suspend () -> Unit) {
        viewModelScope.launch {
            loading = true
            error = null
            notification = null
            try {
                block()
            } catch (e: HttpException) {
                error = when (e.code()) {
                    402 -> "Недостаточно NOVA Tokens для Premium-ответа"
                    429 -> "AI занят или достиг лимита провайдера. Попробуй позже."
                    503 -> "AI-провайдер ещё не подключён или временно недоступен"
                    else -> "Ошибка сервера: HTTP " + e.code()
                }
                if (e.code() == 401) {
                    accessToken = null
                    error = "Сессия закончилась. Войди заново."
                }
            } catch (e: Exception) {
                error = e.localizedMessage ?: "Не удалось выполнить запрос"
            } finally {
                loading = false
            }
        }
    }

    fun signIn(createAccount: Boolean) {
        execute {
            val api = service()
            val credentials = Credentials(email.trim(), password)
            val result = if (createAccount) api.register(credentials) else api.login(credentials)
            accessToken = result.accessToken
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
            val created = api.createChat(authorization(), NovaCreateChatBody(character.id))
            activeChat = created.chat
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
            chatMessages = service().chatMessages(authorization(), chat.id).items
            activeTab = NovaTab.CHATS
        }
    }

    fun closeChat() {
        pendingChatRequestId = null
        pendingChatFingerprint = null
        activeChat = null
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
        if (text.length > 1000) {
            error = "Сообщение слишком длинное"
            return
        }
        val tier = if (premiumChatSelected) "premium" else "free"
        val fingerprint = current.id + ":" + tier + ":" + text
        if (pendingChatFingerprint != fingerprint || pendingChatRequestId == null) {
            pendingChatFingerprint = fingerprint
            pendingChatRequestId = java.util.UUID.randomUUID().toString()
        }
        val requestId = checkNotNull(pendingChatRequestId)
        execute {
            val api = service()
            try {
                val result = api.sendChatMessage(
                    authorization(), current.id,
                    NovaChatSendBody(text, requestId, tier)
                )
                if (result.assistantMessage != null) {
                    pendingChatRequestId = null
                    pendingChatFingerprint = null
                    chatInput = ""
                    chatMessages = api.chatMessages(authorization(), current.id).items
                    chats = api.chats(authorization()).items
                    aiStatus = api.aiStatus(authorization())
                } else {
                    error = "Ответ ещё обрабатывается. Повтори отправку."
                }
            } catch (e: HttpException) {
                // The server explicitly rejected this request. The next attempt gets a fresh ID.
                pendingChatRequestId = null
                pendingChatFingerprint = null
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
            service().createCharacter(
                authorization(),
                CharacterDraft(
                    name = draftName.trim(),
                    greeting = draftGreeting.trim(),
                    category = draftCategory.trim().ifBlank { "fantasy" }
                )
            )
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
        }
    }

    fun logout() {
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
}
