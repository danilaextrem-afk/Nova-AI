package ai.nova.app

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT

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

interface NovaApi {
    @POST("v1/auth/login")
    suspend fun login(@Body body: Credentials): AuthTokens

    @POST("v1/auth/register")
    suspend fun register(@Body body: Credentials): AuthTokens

    @GET("v1/characters/catalog")
    suspend fun catalog(@Header("Authorization") bearer: String): CharacterPage

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

    @GET("v1/wallet")
    suspend fun wallet(@Header("Authorization") bearer: String): TokenWallet

    @GET("v1/wallet/transactions")
    suspend fun transactions(@Header("Authorization") bearer: String): TransactionPage
}
