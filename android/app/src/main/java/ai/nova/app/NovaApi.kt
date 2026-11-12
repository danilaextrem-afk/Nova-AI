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
    val scenario: String = "", val avatarUrl: String? = null,
    val mature: Boolean = false,
    val age: Int? = null, val gender: String? = null, val traits: List<String> = emptyList()
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
    val scenario: String? = null, val language: String = "ru", val ownerId: String? = null, val publication: PublicationInfo? = null,
    val mature: Boolean = false,
    val age: Int? = null, val gender: String? = null, val traits: List<String>? = null,
    val reason: String? = null // Recommendation feed: TASTE | NEW | POPULAR
)
/** Smart creator: the current card is sent as context; the server returns a suggestion and never saves it. */
data class AssistCurrent(val name: String? = null, val age: Int? = null, val gender: String? = null, val traits: List<String>? = null,
    val biography: String? = null, val personality: String? = null, val interests: String? = null, val appearance: String? = null,
    val speechStyle: String? = null, val backstory: String? = null, val scenario: String? = null, val greeting: String? = null,
    val category: String? = null, val tags: List<String>? = null)
data class AssistRequest(val mode: String, val idea: String? = null, val field: String? = null, val action: String? = null,
    val current: AssistCurrent = AssistCurrent(), val language: String = "ru")
data class AssistCard(val name: String = "", val biography: String = "", val personality: String = "", val interests: String = "",
    val appearance: String = "", val speechStyle: String = "", val backstory: String = "", val scenario: String = "", val greeting: String = "",
    val tags: List<String>? = null, val traits: List<String>? = null, val category: String? = null)
data class AssistResponse(val card: AssistCard? = null, val variants: List<String>? = null, val needs: List<String>? = null)
data class AvatarUpload(val data: String, val source: String, val rightsConfirmed: Boolean = true)
data class AvatarResult(val id: String, val url: String)
data class ArtStatus(val configured: Boolean = false, val freePerDay: Int = 0, val usedToday: Int = 0, val freeLeft: Int = 0, val priceTokens: Int = 0)
data class ArtRequest(val prompt: String, val style: String, val requestId: String)
data class ArtResult(val image: String? = null, val mime: String? = null, val prompt: String? = null, val chargedTokens: Int = 0, val freeLeft: Int = 0)
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
    // Nullable on purpose: Gson ignores Kotlin defaults, and a server/fixture that omits a field would
    // otherwise put null into a non-null property and crash later (seen in UI tests on chat.copy()).
    val characterName: String? = null, val persona: String? = null, val scenario: String? = null,
    val responseStyle: String? = null, val tone: String? = null, val participantIds: List<String>? = null,
    val characterAvatarUrl: String? = null
)
data class NovaChatsResponse(val items: List<NovaChat> = emptyList())
data class NovaCreateChatBody(val characterId: String, val participantIds: List<String> = emptyList(), val scenario: String = "", val persona: String = "", val scenarioId: String? = null)
data class NovaChatResponse(val chat: NovaChat, val greeting: String? = null)
data class Shelf(val key: String, val title: String, val items: List<NovaCharacter> = emptyList())
data class ShelvesResponse(val collections: List<Shelf>? = null)
data class AuthorPage(val id: String, val displayName: String, val bio: String? = null, val followers: Int = 0, val isFollowing: Boolean = false,
    val likes: Int = 0, val characters: List<NovaCharacter> = emptyList())
data class CreatorTotals(val characters: Int = 0, val likes: Int = 0, val stories: Int = 0, val favorites: Int = 0)
data class CreatorStatItem(val id: String, val name: String, val status: String? = null, val likes: Int = 0, val stories: Int = 0, val favorites: Int = 0, val comments: Int = 0)
data class CreatorStats(val followers: Int = 0, val totals: CreatorTotals? = null, val items: List<CreatorStatItem>? = null)
data class ChatCastBody(val participantIds: List<String>)
data class CharacterMemoryItem(val id: String, val content: String, val pinned: Boolean = false, val source: String? = null)
data class CharacterMemoryPage(val items: List<CharacterMemoryItem> = emptyList())
data class CharacterMemoryBody(val content: String, val pinned: Boolean = false)
data class CharacterScenario(val id: String, val title: String, val scenario: String, val greeting: String? = null)
data class ScenarioPage(val items: List<CharacterScenario> = emptyList())
data class ScenarioBody(val title: String, val scenario: String, val greeting: String = "")
data class NovaChatMessage(
    val id: String, val role: String, val content: String,
    val createdAt: String? = null, val sequence: Int = 0, val pinned: Boolean = false,
    val alternatives: List<String>? = null, // Gson ignores Kotlin defaults: absent field stays null.
    val speakerName: String? = null,
    val speakerId: String? = null
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

data class PublicationInfo(val id: String, val likesCount: Int = 0)
data class SocialComment(val id: String, val content: String, val status: String)
data class CommentPage(val items: List<SocialComment> = emptyList())
data class RefreshBody(val refreshToken: String)
data class MemoryItem(val id: String, val content: String, val kind: String, val pinned: Boolean = false, val source: String? = null)
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
    @POST("v1/characters/{id}/hide") suspend fun hideCharacter(@Header("Authorization") bearer: String, @Path("id") id: String)
    @GET("v1/community/following") suspend fun following(@Header("Authorization") bearer: String): CharacterPage
    @PUT("v1/creators/{id}/follow") suspend fun follow(@Header("Authorization") bearer: String, @Path("id") id: String)
    @DELETE("v1/creators/{id}/follow") suspend fun unfollow(@Header("Authorization") bearer: String, @Path("id") id: String)
    @PUT("v1/publications/{id}/like") suspend fun like(@Header("Authorization") bearer: String, @Path("id") id: String)
    @GET("v1/publications/{id}/comments") suspend fun comments(@Header("Authorization") bearer: String, @Path("id") id: String): CommentPage
    @POST("v1/publications/{id}/comments") suspend fun comment(@Header("Authorization") bearer: String, @Path("id") id: String, @Body body: Map<String,String>)

    @POST("v1/auth/refresh") fun refreshSync(@Body body: RefreshBody): retrofit2.Call<AuthTokens>
    @POST("v1/auth/logout") suspend fun logout(@Header("Authorization") bearer: String)
    @GET("v1/characters/{id}") suspend fun character(@Header("Authorization") bearer: String, @Path("id") id: String): CreatedCharacter
    @retrofit2.http.PATCH("v1/characters/{id}") suspend fun editCharacter(@Header("Authorization") bearer: String, @Path("id") id: String, @Body body: CharacterDraft)
    @POST("v1/characters/{id}/publish") suspend fun publish(@Header("Authorization") bearer: String, @Path("id") id: String)
    @POST("v1/characters/{id}/clone") suspend fun clone(@Header("Authorization") bearer: String, @Path("id") id: String): CreatedCharacter
    @GET("v1/characters/favorites") suspend fun favorites(@Header("Authorization") bearer: String): FavoritePage
    @DELETE("v1/characters/{id}/favorite") suspend fun removeFavorite(@Header("Authorization") bearer: String, @Path("id") id: String): FavoriteResult
    @retrofit2.http.PATCH("v1/chats/{id}") suspend fun saveCast(@Header("Authorization") bearer: String, @Path("id") id: String, @Body body: ChatCastBody)
    @GET("v1/characters/{id}/memories") suspend fun characterMemories(@Header("Authorization") bearer: String, @Path("id") id: String): CharacterMemoryPage
    @POST("v1/characters/{id}/memories") suspend fun addCharacterMemory(@Header("Authorization") bearer: String, @Path("id") id: String, @Body body: CharacterMemoryBody)
    @DELETE("v1/characters/{id}/memories/{memoryId}") suspend fun deleteCharacterMemory(@Header("Authorization") bearer: String, @Path("id") id: String, @Path("memoryId") memoryId: String): retrofit2.Response<Unit>
    @POST("v1/characters/assist") suspend fun assist(@Header("Authorization") bearer: String, @Body body: AssistRequest): AssistResponse
    @POST("v1/media/avatar") suspend fun uploadAvatar(@Header("Authorization") bearer: String, @Body body: AvatarUpload): AvatarResult
    @GET("v1/discover/recommended") suspend fun recommended(@Header("Authorization") bearer: String): CharacterPage
    @GET("v1/discover/collections") suspend fun shelves(@Header("Authorization") bearer: String): ShelvesResponse
    @GET("v1/creators/{id}") suspend fun author(@Header("Authorization") bearer: String, @Path("id") id: String): AuthorPage
    @GET("v1/me/creator-stats") suspend fun creatorStats(@Header("Authorization") bearer: String): CreatorStats
    @GET("v1/characters/{id}/scenarios") suspend fun scenarios(@Header("Authorization") bearer: String, @Path("id") id: String): ScenarioPage
    @POST("v1/characters/{id}/scenarios") suspend fun addScenario(@Header("Authorization") bearer: String, @Path("id") id: String, @Body body: ScenarioBody)
    @DELETE("v1/characters/{id}/scenarios/{scenarioId}") suspend fun deleteScenario(@Header("Authorization") bearer: String, @Path("id") id: String, @Path("scenarioId") scenarioId: String): retrofit2.Response<Unit>
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
        @Query("sort") sort: String = "recent",
        @Query("section") section: String = "general"
    ): CharacterPage

    @GET("v1/characters/mine")
    suspend fun myCharacters(@Header("Authorization") bearer: String): CharacterPage

    @PUT("v1/me/content-settings") suspend fun contentSettings(@Header("Authorization") bearer: String, @Body body: ContentSettingsBody): ContentSettings
    @GET("v1/me") suspend fun me(@Header("Authorization") bearer: String): MeResponse

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
    suspend fun chats(@Header("Authorization") bearer: String, @Query("q") query: String? = null): NovaChatsResponse

    @POST("v1/chats")
    suspend fun createChat(
        @Header("Authorization") bearer: String,
        @Body body: NovaCreateChatBody
    ): NovaChatResponse

    @GET("v1/chats/{id}/messages")
    suspend fun chatMessages(
        @Header("Authorization") bearer: String,
        @Path("id") id: String, @Query("before") before: Int? = null, @Query("q") query: String? = null
    ): NovaChatMessagesResponse
    @retrofit2.http.PATCH("v1/chats/{id}/messages/{messageId}") suspend fun editMessage(@Header("Authorization") bearer: String,
        @Path("id") id: String, @Path("messageId") messageId: String, @Body body: EditMessageBody)
    @DELETE("v1/chats/{id}/messages/{messageId}") suspend fun deleteMessage(@Header("Authorization") bearer: String,
        @Path("id") id: String, @Path("messageId") messageId: String)

    /** Server-Sent Events: "delta" events, then one "done" or "error". Read off the main thread. */
    @retrofit2.http.Streaming
    @POST("v1/chats/{id}/messages/stream")
    suspend fun sendChatMessageStream(
        @Header("Authorization") bearer: String,
        @Path("id") id: String,
        @Body body: NovaChatSendBody
    ): okhttp3.ResponseBody

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
    @GET("v1/art/status") suspend fun artStatus(@Header("Authorization") bearer: String): ArtStatus
    @POST("v1/art") suspend fun createArt(@Header("Authorization") bearer: String, @Body body: ArtRequest): ArtResult
    @GET("v1/rewards/daily") suspend fun dailyBonus(@Header("Authorization") bearer: String): DailyBonusStatus
    @POST("v1/rewards/daily") suspend fun claimDailyBonus(@Header("Authorization") bearer: String): DailyBonusResult
    @GET("v1/referrals") suspend fun referrals(@Header("Authorization") bearer: String): ReferralStatus
    @POST("v1/referrals/apply") suspend fun applyReferral(@Header("Authorization") bearer: String, @Body body: ApplyReferralBody): ApplyReferralResult

    @GET("v1/wallet/transactions")
    suspend fun transactions(@Header("Authorization") bearer: String): TransactionPage
}

// Rewards. Gson ignores Kotlin defaults for absent fields, so object/string fields are nullable.
data class DailyBonusStatus(val enabled: Boolean = false, val amount: Int = 0, val claimedToday: Boolean = false, val nextAt: String? = null)
data class DailyBonusResult(val granted: Boolean = false, val amount: Int = 0, val nextAt: String? = null)
data class MyInvite(val rewarded: Boolean = false, val turns: Int = 0)
data class ReferralStatus(val code: String? = null, val bonusTokens: Int = 0, val invited: Int = 0, val rewarded: Int = 0,
    val requiredTurns: Int = 3, val myInvite: MyInvite? = null, val canApplyCode: Boolean = false)
data class ApplyReferralBody(val code: String)
data class ApplyReferralResult(val applied: Boolean = false, val rewarded: Boolean = false, val requiredTurns: Int = 3)

data class ContentSettingsBody(val showMature: Boolean, val confirmAdult: Boolean? = null)
data class ContentSettings(val showMature: Boolean = false)
data class MeProfile(val showMature: Boolean = false)
data class MeResponse(val profile: MeProfile? = null)

data class EditMessageBody(val content: String)
