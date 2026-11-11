package ai.nova.app
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.net.InetAddress

/** UI integration against a named HTTP fixture; no claims of live AI/payment validation. */
class NovaUiTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private val server=MockWebServer()
 private val character="00000000-0000-4000-8000-000000000101"
 private val chat="10000000-0000-4000-8000-000000000001"
 @Volatile private var sent=false
 @Volatile private var commentSent=false
 @Volatile private var regenerated=false
 @Volatile private var streamCalls=0
 @Before fun setUp(){
  server.dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest):MockResponse {
   if(r.path=="/v1/chats/$chat/messages/stream"){
    sent=true;streamCalls++
    val sse="event: delta\ndata: {\"text\":\"Тестовый \"}\n\n"+"event: delta\ndata: {\"text\":\"ответ сервера\"}\n\n"+
     "event: done\ndata: {\"userMessage\":{\"id\":\"u\",\"role\":\"user\",\"content\":\"Привет\"},\"assistantMessage\":{\"id\":\"a\",\"role\":\"assistant\",\"content\":\"Тестовый ответ сервера\"},\"tokensCharged\":0}\n\n"
    return MockResponse().setHeader("Content-Type","text/event-stream").setBody(sse)
   }
   val json=when {
    r.path=="/v1/auth/login" -> """{"accessToken":"fixture-token","refreshToken":"fixture-refresh-token"}"""
    r.path?.startsWith("/v1/characters/catalog")==true -> """{"items":[{"id":"$character","name":"Аврора Вейл","greeting":"Привет, исследователь!","category":"Приключения","tags":[],"ownerId":"fixture-creator","publication":{"id":"fixture-publication","likesCount":0}}]}"""
    r.path=="/v1/chats" && r.method=="POST" -> """{"chat":{"id":"$chat","characterId":"$character","title":"Аврора Вейл"}}"""
    r.path=="/v1/chats" -> """{"items":[{"id":"$chat","characterId":"$character","title":"Аврора Вейл"}]}"""
    r.path=="/v1/ai/status" -> """{"provider":"fixture","model":"fixture-only","configured":true,"premiumConfigured":false,"premiumPriceTokens":5,"tokenBalance":0}"""
    r.path=="/v1/chats/$chat/messages" && r.method=="POST" && r.body.clone().readUtf8().contains("\"regenerateMessageId\":\"a\"") -> {regenerated=true;"""{"duplicate":false,"assistantMessage":{"id":"a","role":"assistant","content":"Новая версия ответа","alternatives":["Тестовый ответ сервера"]},"tokensCharged":0}"""}
    r.path=="/v1/chats/$chat/messages" && r.method=="POST" -> {sent=true;"""{"userMessage":{"id":"u","role":"user","content":"Привет"},"assistantMessage":{"id":"a","role":"assistant","content":"Тестовый ответ сервера"}}"""}
    r.path=="/v1/chats/$chat/messages" && regenerated -> """{"items":[{"id":"u","role":"user","content":"Привет","sequence":1},{"id":"a","role":"assistant","content":"Новая версия ответа","sequence":2,"alternatives":["Тестовый ответ сервера"]}]}"""
    r.path=="/v1/chats/$chat/messages" -> if(sent) """{"items":[{"id":"u","role":"user","content":"Привет","sequence":1},{"id":"a","role":"assistant","content":"Тестовый ответ сервера","sequence":2}]}""" else """{"items":[]}"""
    r.path=="/v1/chats/$chat/memories" -> """{"items":[]}"""
    r.path=="/v1/publications/fixture-publication/comments" && r.method=="POST" -> {commentSent=true;"{}"}
    r.path=="/v1/publications/fixture-publication/comments" -> if(commentSent) """{"items":[{"id":"comment","content":"Интересная история","status":"PENDING_REVIEW"}]}""" else """{"items":[]}"""
    r.path=="/v1/auth/logout" -> "{}"
    else -> """{"items":[]}"""
   };return MockResponse().setHeader("Content-Type","application/json").setBody(json)
  }}
  // MockWebServer.url() resolves canonicalHostName (reverse DNS). Calling it inside runOnIdle ran that
  // lookup on the main thread and StrictMode threw NetworkOnMainThreadException in @Before for every test.
  // Bind to the literal loopback address on the instrumentation thread and build the URL without DNS.
  server.start(InetAddress.getByAddress(byteArrayOf(127,0,0,1)),0)
  val baseUrl="http://127.0.0.1:"+server.port+"/"
  SessionStore(compose.activity).clear()
  compose.runOnIdle {
   val vm=ViewModelProvider(compose.activity)[NovaViewModel::class.java]
   vm.serverUrl=baseUrl
  }
 }
 @After fun finish(){server.shutdown();SessionStore(compose.activity).clear()}
 private fun screenshot(name:String){
  val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
  val dir=File(compose.activity.getExternalFilesDir(null),"screenshots");dir.mkdirs()
  File(dir,name+".png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}
 }
 // Buttons are disabled while a request is in flight (enabled=!model.loading). Clicking a disabled
 // button is a no-op, so wait for the enabled state exactly as a person would before tapping.
 private fun clickWhenEnabled(text:String){
  compose.waitUntil(20000){compose.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
  compose.onNode(hasText(text) and isEnabled()).performScrollTo().performClick()
 }
 @Test fun loginCatalogChatAndEncryptedDraftSurviveRecreation(){
  compose.onNodeWithText("Email").performTextInput("adult@example.test")
  compose.onNodeWithText("Пароль (от 10 символов)").performTextInput("testing-password-123")
  screenshot("01-login")
  clickWhenEnabled("Войти")
  compose.waitUntil(20000){compose.onAllNodesWithText("Каталог").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("Каталог").performClick()
  compose.waitUntil(20000){compose.onAllNodesWithText("Начать AI-чат").fetchSemanticsNodes().isNotEmpty()}
  compose.waitUntil(20000){compose.onAllNodes(hasText("Начать AI-чат") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("Начать AI-чат").performScrollTo();screenshot("02-catalog");clickWhenEnabled("Начать AI-чат")
  compose.waitUntil(20000){compose.onAllNodesWithText("Напиши сообщение").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("Напиши сообщение").performScrollTo().performTextInput("Привет")
  clickWhenEnabled("Отправить бесплатно")
  compose.waitUntil(20000){compose.onAllNodesWithText("Тестовый ответ сервера").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("Тестовый ответ сервера").performScrollTo();screenshot("03-chat")
  assertEquals("chat reply must arrive over the SSE stream route",1,streamCalls)
  clickWhenEnabled("Другой ответ")
  compose.waitUntil(20000){compose.onAllNodesWithText("Новая версия ответа").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("Версия 2 · прежние сохранены").performScrollTo().assertExists();screenshot("04-regenerated")
  compose.onNodeWithText("Напиши сообщение").performScrollTo().performTextInput("Сохранённый черновик")
  compose.activityRule.scenario.recreate()
  compose.waitUntil(20000){compose.onAllNodesWithText("Сохранённый черновик").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("Сохранённый черновик").performScrollTo().assertExists()
 }
 @Test fun communityCommentClearlyShowsPendingModeration(){
  compose.onNodeWithText("Email").performTextInput("adult@example.test")
  compose.onNodeWithText("Пароль (от 10 символов)").performTextInput("testing-password-123")
  compose.onNodeWithText("Войти",useUnmergedTree=true).performScrollTo().performClick()
  compose.waitUntil(20000){compose.onAllNodesWithText("Каталог").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("Каталог").performClick()
  compose.waitUntil(20000){compose.onAllNodes(hasText("Обсуждение") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("Обсуждение").performScrollTo().performClick()
  compose.waitUntil(20000){compose.onAllNodesWithText("Комментарий").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("Комментарий").performScrollTo().performTextInput("Интересная история")
  compose.waitUntil(20000){compose.onAllNodes(hasText("Отправить комментарий") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("Отправить комментарий").performScrollTo().performClick()
  compose.waitUntil(20000){compose.onAllNodesWithText("Интересная история · На модерации").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("Интересная история · На модерации").performScrollTo().assertExists()
  screenshot("04-comment-pending")
 }
 @Test fun sessionStoreEncryptsAndReadsAcrossInstances(){
  val a=SessionStore(compose.activity);a.put("secret-test","never-plaintext-123")
  assertEquals("never-plaintext-123",SessionStore(compose.activity).get("secret-test"))
  assertFalse(compose.activity.getSharedPreferences("nova_private_v2",0).getString("secret-test","")!!.contains("never-plaintext"))
  a.clear();assertNull(SessionStore(compose.activity).get("secret-test"))
 }
 @Test fun allTwelveIndividualPortraitsDecode(){
  for(i in 101..112){val image=NovaCastPortraits.find(compose.activity,"00000000-0000-4000-8000-000000000$i");assertNotNull(image);assertTrue(image!!.width>=1024)}
  assertNull(NovaCastPortraits.find(compose.activity,"ffffffff-ffff-4fff-8fff-000000000101"))
 }
}
