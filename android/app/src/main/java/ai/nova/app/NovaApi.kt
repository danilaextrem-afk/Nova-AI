package ai.nova.app

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Query
import retrofit2.http.DELETE
import retrofit2.http.Path

data class Credentials(val email: String, val password: String, val adultConfirmed: Boolean = false)
data class AuthTokens(val accessToken: String, val refreshToken: String)
data class CharacterDraft(
    val name: String,
    val greeting: String,
    val category: String,
    val tags: List<String> = emptyList(),
    val language: String = "ru",
    val appearance: String = "", val biography: String = "", val personality: String = "",
    val interests: String = "", val backstory: String = "", val speechStyle: String = "",
    val scenario: String = "", val avatarUrl: String? = null
)
data class NovaCharacter(
    val id: String,
    val name: String,
    val greeting: String,
    val category: String,
    val tags: List<String> = emptyList(),
    val avatarUrl: String? = null,
    val biography: String? = null, val appearance: String? = null, val personality: String? = null,
    val interests: String? = null, val backstory: String? = null, val speechStyle: String? = null,
    val scenario: String? = null, val language: String = "ru", val ownerId: String? = null
)
data class CharacterPage(val items: List<NovaCharacter> = emptyList(), val nextCursor: String? = null)
data class CreatedCharacter(val character: NovaCharacter)
data class TokenWallet(
    val purchased: Int = 0,
    val adReward: Int = 0,
    val bonus: Int = 0,
    val total: Int = 0
)
data class TokenTransaction(
    val id: String,
    val amount: Int,
    val source: String,
    val type: String,
    val createdAt: String
)
data class TransactionPage(val items: List<TokenTransaction> = emptyList())
data class FavoriteResult(val favorite: Boolean)

data class NovaChat(
    val id: String,
    val characterId: String,
    val title: String? = null,
    val characterName: String? = null, val persona: String = "", val scenario: String = "",
    val responseStyle: String = "balanced", val tone: String = "friendly", val participantIds: List<String> = emptyList()
)
data class NovaChatsResponse(val items: List<NovaChat> = emptyList())
data class NovaCreateChatBody(val characterId: String, val participantIds: List<String> = emptyList(), val scenario: String = "", val persona: String = "")
data class NovaChatResponse(val chat: NovaChat)
data class NovaChatMessage(
    val id: String, val role: String, val content: String,
    val createdAt: String? = null, val sequence: Int = 0, val pinned: Boolean = false,
    val alternatives: List<String> = emptyList()
)
data class NovaChatMessagesResponse(val items: List<NovaChatMessage> = emptyList(), val nextBefore: Int? = null)
// Exactly one of content / regenerateMessageId is sent; Gson omits nulls.
data class NovaChatSendBody(val content: String?, val requestId: String, val tier: String = "free", val expectedPriceTokens: Int? = null, val regenerateMessageId: String? = null)
data class NovaChatSendResponse(
    val assistantMessage: NovaChatMessage?,
    val userMessage: NovaChatMessage?,
    val duplicate: Boolean = false,
    val tier: String? = null,
    val tokensCharged: Int = 0
)
data class NovaAiStatus(
    val provider: String, val model: String,
    val configured: Boolean,
    val freeUnlimited: Boolean = true,
    val premiumConfigured: Boolean = false,
    val premiumProvider: String? = null,
    val premiumModel: String? = null,
    val premiumPriceTokens: Int = 5,
    val tokenBalance: Int = 0
)

data class RefreshBody(val refreshToken: String)
data class MemoryItem(val id: String, val content: String, val kind: String, val pinned: Boolean = false)
data class MemoryPage(val items: List<MemoryItem> = emptyList())
data class MemoryBody(val content: String, val kind: String = "FACT", val pinned: Boolean = false)
data class BranchBody(val messageId: String, val title: String = "Новая ветка")
data class ChatSettings(val persona: String, val scenario: String, val responseStyle: String, val tone: String)
data class FavoriteEntry(val character: NovaCharacter)
data class FavoritePage(val items: List<FavoriteEntry> = emptyList())
data class NovaNotification(val id: String, val title: String, val body: String, val readAt: String?)
data class NotificationPage(val items: List<NovaNotification> = emptyList())
data class ReportBody(val targetType: String, val targetId: String, val reason: String)
data class ProductItem(val sku: String, val tokens: Int)
data class ProductPage(val enabled: Boolean, val accountId: String, val items: List<ProductItem>)
data class VerifyPurchaseBody(val sku: String, val purchaseToken: String)
data class VerifyPurchaseResult(val status: String, val tokens: Int = 0)
data class AdStatus(val configured: Boolean, val adUnit: String?, val userId: String, val remainingToday: Int, val secondsUntilNext: Int)
interface NovaApi {
    @POST("v1/auth/refresh") fun refreshSync(@Body body: RefreshBody): retrofit2.Call<AuthTokens>
    @POST("v1/auth/logout") suspend fun logout(@Header("Authorization") bearer: String)
    @GET("v1/characters/{id}") suspend fun character(@Header("Authorization") bearer: String, @Path("id") id: String): CreatedCharacter
    @retrofit2.http.PATCH("v1/characters/{id}") suspend fun editCharacter(@Header("Authorization") bearer: String, @Path("id") id: String, @Body body: CharacterDraft)
    @POST("v1/characters/{id}/publish") suspend fun publish(@Header("Authorization") bearer: String, @Path("id") id: String)
    @POST("v1/characters/{id}/clone") suspend fun clone(@Header("Authorization") bearer: String, @Path("id") id: String): CreatedCharacter
    @GET("v1/characters/favorites") suspend fun favorites(@Header("Authorization") bearer: String): FavoritePage
    @DELETE("v1/characters/{id}/favorite") suspend fun removeFavorite(@Header("Authorization") bearer: String, @Path("id") id: String): FavoriteResult
    @GET("v1/chats/{id}/memories") suspend fun memories(@Header("Authorization") bearer: String, @Path("id") id: String): MemoryPage
    @POST("v1/chats/{id}/memories") suspend fun addMemory(@Header("Authorization") bearer: String, @Path("id") id: String, @Body body: MemoryBody)
    @retrofit2.http.PATCH("v1/chats/{id}/memories/{memoryId}") suspend fun editMemory(@Header("Authorization") bearer: String, @Path("id") id: String, @Path("memoryId") memoryId: String, @Body body: MemoryBody)
    @DELETE("v1/chats/{id}/memories/{memoryId}") suspend fun deleteMemory(@Header("Authorization") bearer: String, @Path("id") id: String, @Path("memoryId") memoryId: String)
    @POST("v1/chats/{id}/branch") suspend fun branch(@Header("Authorization") bearer: String, @Path("id") id: String, @Body body: BranchBody): NovaChatResponse
    @retrofit2.http.PATCH("v1/chats/{id}") suspend fun saveSettings(@Header("Authorization") bearer: String, @Path("id") id: String, @Body body: ChatSettings)
    @GET("v1/notifications") suspend fun notifications(@Header("Authorization") bearer: String): NotificationPage
    @PUT("v1/notifications/{id}/read") suspend fun readNotification(@Header("Authorization") bearer: String, @Path("id") id: String)
    @POST("v1/reports") suspend fun report(@Header("Authorization") bearer: String, @Body body: ReportBody)
    @GET("v1/me/export") suspend fun export(@Header("Authorization") bearer: String): okhttp3.ResponseBody
    @retrofit2.http.HTTP(method="DELETE", path="v1/me", hasBody=true) suspend fun deleteAccount(@Header("Authorization") bearer: String, @Body body: Map<String,String>)
    @GET("v1/billing/products") suspend fun products(@Header("Authorization") bearer: String): ProductPage
    @POST("v1/billing/verify") suspend fun verifyPurchase(@Header("Authorization") bearer: String, @Body body: VerifyPurchaseBody): VerifyPurchaseResult
    @GET("v1/ads/status") suspend fun adStatus(@Header("Authorization") bearer: String): AdStatus

    @POST("v1/auth/login")
    suspend fun login(@Body body: Credentials): AuthTokens

    @POST("v1/auth/register")
    suspend fun register(@Body body: Credentials): AuthTokens

    @GET("v1/characters/catalog")
    suspend fun catalog(
        @Header("Authorization") bearer: String,
        @Query("q") query: String? = null,
        @Query("category") category: String? = null,
        @Query("tag") tag: String? = null,
        @Query("sort") sort: String = "recent"
    ): CharacterPage

    @GET("v1/characters/mine")
    suspend fun myCharacters(@Header("Authorization") bearer: String): CharacterPage

    @POST("v1/characters")
    suspend fun createCharacter(
        @Header("Authorization") bearer: String,
        @Body draft: CharacterDraft
    ): CreatedCharacter

    @PUT("v1/characters/{id}/favorite")
    suspend fun addFavorite(
        @Header("Authorization") bearer: String,
        @retrofit2.http.Path("id") id: String
    ): FavoriteResult

    @GET("v1/ai/status")
    suspend fun aiStatus(@Header("Authorization") bearer: String): NovaAiStatus

    @GET("v1/chats")
    suspend fun chats(@Header("Authorization") bearer: String): NovaChatsResponse

    @POST("v1/chats")
    suspend fun createChat(
        @Header("Authorization") bearer: String,
        @Body body: NovaCreateChatBody
    ): NovaChatResponse

    @GET("v1/chats/{id}/messages")
    suspend fun chatMessages(
        @Header("Authorization") bearer: String,
        @Path("id") id: String, @Query("before") before: Int? = null
    ): NovaChatMessagesResponse

    @POST("v1/chats/{id}/messages")
    suspend fun sendChatMessage(
        @Header("Authorization") bearer: String,
        @Path("id") id: String,
        @Body body: NovaChatSendBody
    ): NovaChatSendResponse

    @DELETE("v1/chats/{id}")
    suspend fun deleteChat(
        @Header("Authorization") bearer: String,
        @Path("id") id: String
    )

    @GET("v1/wallet")
    suspend fun wallet(@Header("Authorization") bearer: String): TokenWallet

    @GET("v1/wallet/transactions")
    suspend fun transactions(@Header("Authorization") bearer: String): TransactionPage
}
