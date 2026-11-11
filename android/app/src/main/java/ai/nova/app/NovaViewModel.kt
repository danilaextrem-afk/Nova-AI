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

enum class NovaTab { HOME, CATALOG, CREATE, WALLET, ACCOUNT }

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
                error = "Ошибка сервера: HTTP " + e.code()
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
        accessToken = null
        email = ""
        password = ""
        catalog = emptyList()
        owned = emptyList()
        wallet = null
        transactions = emptyList()
        activeTab = NovaTab.HOME
        notification = null
        error = null
    }
}
