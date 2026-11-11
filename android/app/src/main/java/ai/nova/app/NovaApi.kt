package ai.nova.app

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Query
import retrofit2.http.DELETE
import retrofit2.http.Path

data class Credentials(val email: String, val password: String)
data class AuthTokens(val accessToken: String, val refreshToken: String)
data class CharacterDraft(
    val name: String,
    val greeting: String,
    val category: String,
    val tags: List<String> = emptyList(),
    val language: String = "ru"
)
data class NovaCharacter(
    val id: String,
    val name: String,
    val greeting: String,
    val category: String,
    val tags: List<String> = emptyList(),
    val avatarUrl: String? = null,
    val biography: String? = null
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
    val characterName: String? = null
)
data class NovaChatsResponse(val items: List<NovaChat> = emptyList())
data class NovaCreateChatBody(val characterId: String)
data class NovaChatResponse(val chat: NovaChat)
data class NovaChatMessage(
    val id: String, val role: String, val content: String,
    val createdAt: String? = null
)
data class NovaChatMessagesResponse(val items: List<NovaChatMessage> = emptyList())
data class NovaChatSendBody(val content: String, val requestId: String, val tier: String = "free")
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

interface NovaApi {
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
        @Path("id") id: String
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
