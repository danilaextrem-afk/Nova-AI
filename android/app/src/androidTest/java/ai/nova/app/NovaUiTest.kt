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
 @Volatile private var registration:String?=null
 @Volatile private var studioDraft:String?=null
 @Volatile private var sent=false
 @Volatile private var commentSent=false
 @Volatile private var regenerated=false
 @Volatile private var streamCalls=0
 @Volatile private var streamFailure=false
 private val streamIds=java.util.Collections.synchronizedList(mutableListOf<String>())
 @Volatile private var bonusClaims=0
 @Volatile private var showMature=false
 @Volatile private var userText="Привет"
 @Volatile private var artBody:String?=null
 @Volatile private var authRequests=0
 private val artJpeg:String by lazy {
  val b=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888);b.eraseColor(android.graphics.Color.rgb(120,80,200))
  val out=java.io.ByteArrayOutputStream();b.compress(Bitmap.CompressFormat.JPEG,90,out)
  android.util.Base64.encodeToString(out.toByteArray(),android.util.Base64.NO_WRAP)
 }
 @Before fun setUp(){
  server.dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest):MockResponse {
   if(r.path=="/v1/auth/login"||r.path=="/v1/auth/register")authRequests++
   // Fixture copies of the server's answers for a wrong password and an already registered email.
   val authBody=if(r.path?.startsWith("/v1/auth/")==true)r.body.clone().readUtf8() else ""
   if(r.path=="/v1/auth/login"&&authBody.contains("wrong-password-123"))
    return MockResponse().setResponseCode(401).setHeader("Content-Type","application/json").setBody("""{"error":"INVALID_CREDENTIALS"}""")
   if(r.path=="/v1/auth/register"&&authBody.contains("taken@example.test"))
    return MockResponse().setResponseCode(409).setHeader("Content-Type","application/json").setBody("""{"error":"EMAIL_TAKEN"}""")
   if(r.path=="/v1/auth/register"){
    registration=r.body.readUtf8()
    return MockResponse().setHeader("Content-Type","application/json").setBody("""{"accessToken":"fixture-token","refreshToken":"fixture-refresh-token"}""").setBodyDelay(1,java.util.concurrent.TimeUnit.SECONDS)
   }
   if(r.path=="/v1/chats/$chat/messages/stream"){
    streamIds.add(com.google.gson.JsonParser.parseString(r.body.readUtf8()).asJsonObject.get("requestId").asString)
    if(streamFailure)return MockResponse().setResponseCode(503).setHeader("Content-Type","application/json").setBody("{\"error\":\"AI_NOT_CONFIGURED\"}")
    sent=true;streamCalls++
    val sse="event: delta\ndata: {\"text\":\"Тестовый \"}\n\n"+"event: delta\ndata: {\"text\":\"ответ сервера\"}\n\n"+
     "event: done\ndata: {\"userMessage\":{\"id\":\"u\",\"role\":\"user\",\"content\":\"Привет\"},\"assistantMessage\":{\"id\":\"a\",\"role\":\"assistant\",\"content\":\"Тестовый ответ сервера\"},\"tokensCharged\":0}\n\n"
    return MockResponse().setHeader("Content-Type","text/event-stream").setBody(sse)
   }
   val json=when {
    r.path=="/v1/characters" && r.method=="POST" -> {studioDraft=r.body.readUtf8(); """{"character":{"id":"$character","name":"Аделина Мар","greeting":"Привет","category":"Романтика","tags":[],"mature":true}}"""}
    r.path=="/v1/characters/$character" -> """{"character":{"id":"$character","name":"Аврора Вейл","greeting":"Привет, исследователь!","category":"Приключения","tags":[],"biography":"28 лет. Исследовательница далёких миров.","personality":"Любознательная и решительная."}}"""
    r.path=="/v1/auth/login" -> """{"accessToken":"fixture-token","refreshToken":"fixture-refresh-token"}"""
    r.path=="/v1/me" -> """{"id":"me","email":"adult@example.test","profile":{"showMature":$showMature}}"""
    r.path=="/v1/me/content-settings" -> {val b=r.body.clone().readUtf8();showMature=b.contains("\"showMature\":true")&&b.contains("\"confirmAdult\":true");"""{"showMature":$showMature}"""}
    r.path?.startsWith("/v1/characters/catalog")==true && r.path!!.contains("section=mature") -> """{"items":[{"id":"00000000-0000-4000-8000-000000000199","name":"Северина Лис","greeting":"Добрый вечер.","category":"Романтика","tags":[],"mature":true}]}"""
    r.path?.startsWith("/v1/characters/catalog")==true -> """{"items":[{"id":"$character","name":"Аврора Вейл","greeting":"Привет, исследователь!","category":"Приключения","tags":[],"ownerId":"fixture-creator","publication":{"id":"fixture-publication","likesCount":0}}]}"""
    r.path=="/v1/chats" && r.method=="POST" -> """{"chat":{"id":"$chat","characterId":"$character","title":"Аврора Вейл"}}"""
    r.path=="/v1/chats" -> """{"items":[{"id":"$chat","characterId":"$character","title":"Аврора Вейл"}]}"""
    r.path=="/v1/rewards/daily" && r.method=="POST" -> {bonusClaims++;"""{"granted":true,"amount":2}"""}
    r.path=="/v1/rewards/daily" -> """{"enabled":true,"amount":2,"claimedToday":${bonusClaims>0}}"""
    r.path=="/v1/wallet" -> """{"purchased":0,"adReward":0,"bonus":${bonusClaims*2},"total":${bonusClaims*2}}"""
    r.path=="/v1/referrals" -> """{"code":"NOVA2345","bonusTokens":20,"invited":0,"rewarded":0,"requiredTurns":3,"canApplyCode":true}"""
    r.path=="/v1/ai/status" -> """{"provider":"fixture","model":"fixture-only","configured":true,"premiumConfigured":false,"premiumPriceTokens":5,"tokenBalance":0}"""
    r.path=="/v1/chats/$chat/messages" && r.method=="POST" && r.body.clone().readUtf8().contains("\"regenerateMessageId\":\"a\"") -> {regenerated=true;"""{"duplicate":false,"assistantMessage":{"id":"a","role":"assistant","content":"Новая версия ответа","alternatives":["Тестовый ответ сервера"]},"tokensCharged":0}"""}
    r.path=="/v1/chats/$chat/messages" && r.method=="POST" -> {sent=true;"""{"userMessage":{"id":"u","role":"user","content":"Привет"},"assistantMessage":{"id":"a","role":"assistant","content":"Тестовый ответ сервера"}}"""}
    r.path=="/v1/chats/$chat/messages/u" && r.method=="PATCH" -> {val b=r.body.clone().readUtf8();userText=com.google.gson.JsonParser.parseString(b).asJsonObject.get("content").asString;"""{"updated":true}"""}
    r.path=="/v1/chats/$chat/messages" && regenerated -> """{"items":[{"id":"u","role":"user","content":"$userText","sequence":1},{"id":"a","role":"assistant","content":"Новая версия ответа","sequence":2,"alternatives":["Тестовый ответ сервера"]}]}"""
    r.path=="/v1/chats/$chat/messages" -> if(sent) """{"items":[{"id":"u","role":"user","content":"Привет","sequence":1},{"id":"a","role":"assistant","content":"Тестовый ответ сервера","sequence":2}]}""" else """{"items":[]}"""
    r.path=="/v1/chats/$chat/memories" -> """{"items":[]}"""
    r.path=="/v1/publications/fixture-publication/comments" && r.method=="POST" -> {commentSent=true;"{}"}
    r.path=="/v1/publications/fixture-publication/comments" -> if(commentSent) """{"items":[{"id":"comment","content":"Интересная история","status":"PENDING_REVIEW"}]}""" else """{"items":[]}"""
    r.path=="/v1/auth/logout" -> "{}"
    r.path=="/v1/art/status" -> """{"configured":true,"freePerDay":3,"usedToday":${if(artBody==null)0 else 1},"freeLeft":${if(artBody==null)3 else 2},"priceTokens":3}"""
    r.path=="/v1/art" && r.method=="POST" -> {artBody=r.body.readUtf8();"""{"image":"$artJpeg","mime":"image/jpeg","prompt":"a lake","chargedTokens":0,"freeLeft":2}"""}
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
 @After fun finish(){server.shutdown();SessionStore(compose.activity).clear();NovaLang.set(compose.activity,"ru");File(compose.activity.filesDir,"nova-art").deleteRecursively()}
 private fun screenshot(name:String){
  val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
  val dir=File(compose.activity.getExternalFilesDir(null),"screenshots");dir.mkdirs()
  File(dir,name+"-"+image.width+"x"+image.height+".png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}
 }
 // Buttons are disabled while a request is in flight (enabled=!model.loading). Clicking a disabled
 // button is a no-op, so wait for the enabled state exactly as a person would before tapping.
 /** Named wait: on timeout the failure says which step hung and dumps the visible UI tree. */
 private fun waitFor(step:String,condition:()->Boolean){
  try{compose.waitUntil(20000,condition)}catch(e:androidx.compose.ui.test.ComposeTimeoutException){
   val tree=runCatching{compose.onAllNodes(isRoot()).printToString(maxDepth=30)}.getOrDefault("<no tree>")
   screenshot("failure-"+step.replace(Regex("[^a-zA-Z0-9]"),"-"))
   var state=""
   compose.runOnIdle { val vm=ViewModelProvider(compose.activity)[NovaViewModel::class.java];state="loading=${vm.loading}; error=${vm.error}; tab=${vm.activeTab}; studioDraft=$studioDraft" }
   throw AssertionError("Timed out at step: $step; $state\n"+tree.take(20000),e)
  }
 }
 private fun SemanticsNodeInteraction.reveal():SemanticsNodeInteraction {
  // A screen may still be re-laying out after a background refresh, so retry briefly before failing.
  var last:AssertionError?=null
  repeat(10){
   try { performScrollTo(); return this } catch (e:AssertionError) {
    try { assertIsDisplayed(); return this } catch (e2:AssertionError) { last=e2 }
   }
   compose.waitForIdle();Thread.sleep(500)
  }
  screenshot("failure-not-displayed"); throw AssertionError("Not displayed: "+runCatching{fetchSemanticsNode().config.toString()}.getOrDefault("?"),last)
 }
 private fun clickWhenEnabled(text:String){
  // Matches a visible label or an icon button's accessible name (e.g. the round send button).
  val m=(hasText(text) or hasContentDescription(text)) and isEnabled()
  waitFor("enabled button '$text'"){compose.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()}
  val node=compose.onNode(m)
  try { node.reveal().assertIsDisplayed().performClick() } catch (e:AssertionError) {
   screenshot("failure-click-"+text.replace(Regex("[^a-zA-Zа-яА-Я0-9]"),"-")); throw AssertionError("Could not tap '$text'",e)
  }
 }
 @Test fun loginCatalogChatAndEncryptedDraftSurviveRecreation(){
  compose.onNodeWithText("Email").performTextInput("adult@example.test")
  compose.onNodeWithText("Пароль (от 10 символов)").performTextInput("testing-password-123")
  screenshot("01-login")
  clickWhenEnabled("Войти")
  compose.waitUntil(20000){compose.onAllNodesWithText("Каталог").fetchSemanticsNodes().isNotEmpty()}
  screenshot("07-home")
  compose.onNodeWithText("Каталог").performClick()
  compose.waitUntil(20000){compose.onAllNodesWithText("Начать AI-чат").fetchSemanticsNodes().isNotEmpty()}
  compose.waitUntil(20000){compose.onAllNodes(hasText("Начать AI-чат") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
  screenshot("08-search")
  clickWhenEnabled("Полный профиль")
  waitFor("character profile"){compose.onAllNodesWithText("Закрыть профиль").fetchSemanticsNodes().isNotEmpty()}
  screenshot("09-character-profile");clickWhenEnabled("Закрыть профиль")
  compose.onNodeWithText("Начать AI-чат").reveal();screenshot("02-catalog");clickWhenEnabled("Начать AI-чат")
  compose.waitUntil(20000){compose.onAllNodesWithText("Напиши сообщение").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("Напиши сообщение").reveal().performTextInput("Привет")
  clickWhenEnabled("Отправить бесплатно")
  compose.waitUntil(20000){compose.onAllNodesWithText("Тестовый ответ сервера").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("Тестовый ответ сервера").reveal();screenshot("03-chat")
  assertEquals("chat reply must arrive over the SSE stream route",1,streamCalls)
  clickWhenEnabled("Другой ответ")
  compose.waitUntil(20000){compose.onAllNodesWithText("Новая версия ответа").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("Версия 2 · прежние сохранены").reveal().assertExists();screenshot("04-regenerated")
  clickWhenEnabled("Изменить")
  waitFor("edit dialog field"){compose.onAllNodes(hasText("Привет") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()}
  compose.onNode(hasText("Привет") and hasSetTextAction()).performTextReplacement("Привет, изменено")
  clickWhenEnabled("Сохранить")
  waitFor("edited text shown"){compose.onAllNodesWithText("Привет, изменено").fetchSemanticsNodes().isNotEmpty()}
  assertEquals("Привет, изменено",userText)
  compose.onNodeWithText("Напиши сообщение").reveal().performTextInput("Сохранённый черновик")
  compose.activityRule.scenario.recreate()
  compose.waitUntil(20000){compose.onAllNodesWithText("Сохранённый черновик").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("Сохранённый черновик").reveal().assertExists()
 }
 @Test fun failedNetworkRequestKeepsDraftAndRequestIdAcrossRecreation(){
  streamFailure=true
  compose.onNodeWithText("Email").performTextInput("adult@example.test")
  compose.onNodeWithText("Пароль (от 10 символов)").performTextInput("testing-password-123")
  clickWhenEnabled("Войти")
  compose.waitUntil(20000){compose.onAllNodesWithText("Каталог").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("Каталог").performClick();clickWhenEnabled("Начать AI-чат")
  compose.waitUntil(20000){compose.onAllNodesWithText("Напиши сообщение").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("Напиши сообщение").reveal().performTextInput("Привет")
  clickWhenEnabled("Отправить бесплатно")
  compose.waitUntil(20000){compose.onAllNodesWithText("AI-провайдер ещё не подключён или временно недоступен").fetchSemanticsNodes().isNotEmpty()}
  compose.activityRule.scenario.recreate()
  compose.waitUntil(20000){compose.onAllNodesWithText("Привет").fetchSemanticsNodes().isNotEmpty()}
  streamFailure=false;clickWhenEnabled("Отправить бесплатно")
  compose.waitUntil(20000){compose.onAllNodesWithText("Тестовый ответ сервера").fetchSemanticsNodes().isNotEmpty()}
  assertEquals(2,streamIds.size);assertEquals(streamIds[0],streamIds[1])
 }
 @Test fun communityCommentClearlyShowsPendingModeration(){
  compose.onNodeWithText("Email").performTextInput("adult@example.test")
  compose.onNodeWithText("Пароль (от 10 символов)").performTextInput("testing-password-123")
  compose.onNodeWithText("Войти",useUnmergedTree=true).reveal().performClick()
  compose.waitUntil(20000){compose.onAllNodesWithText("Каталог").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("Каталог").performClick()
  clickWhenEnabled("Полный профиль") // discussion, likes and follows live in the character profile
  compose.waitUntil(20000){compose.onAllNodes(hasText("Обсуждение") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("Обсуждение").reveal().performClick()
  compose.waitUntil(20000){compose.onAllNodesWithText("Комментарий").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("Комментарий").reveal().performTextInput("Интересная история")
  compose.waitUntil(20000){compose.onAllNodes(hasText("Отправить комментарий") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("Отправить комментарий").reveal().performClick()
  compose.waitUntil(20000){compose.onAllNodesWithText("Интересная история · На модерации").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("Интересная история · На модерации").reveal().assertExists()
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
 @Test fun dailyBonusAndInviteCodeOnWallet(){
  compose.onNodeWithText("Email").performTextInput("adult@example.test")
  compose.onNodeWithText("Пароль (от 10 символов)").performTextInput("testing-password-123")
  clickWhenEnabled("Войти")
  compose.waitUntil(20000){compose.onAllNodesWithText("Токены").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("Токены").performClick()
  compose.waitUntil(20000){compose.onAllNodesWithText("NOVA2345").fetchSemanticsNodes().isNotEmpty()}
  clickWhenEnabled("Забрать +2 ◈")
  compose.waitUntil(20000){compose.onAllNodesWithText("Получено сегодня. Следующий бонус — завтра.").fetchSemanticsNodes().isNotEmpty()}
  assertEquals(1,bonusClaims)
  compose.onNodeWithText("NOVA2345").reveal().assertExists();screenshot("05-wallet-rewards")
 }
 @Test fun artStudioDrawsAPictureAndEnglishInterfaceSwitches(){
  compose.onNodeWithText("Email").performTextInput("adult@example.test")
  compose.onNodeWithText("Пароль (от 10 символов)").performTextInput("testing-password-123")
  clickWhenEnabled("Войти")
  waitFor("home"){compose.onAllNodesWithText("Открыть AI Арт").fetchSemanticsNodes().isNotEmpty()}
  clickWhenEnabled("Открыть AI Арт")
  waitFor("art status"){compose.onAllNodesWithText("Бесплатно сегодня: 3 из 3. Картинки сохраняются только на твоём телефоне.").fetchSemanticsNodes().isNotEmpty()}
  // The art screen re-lays out when the day's status arrives; tap again if the first tap landed mid-layout.
  for(attempt in 1..3){
   if(compose.onAllNodes(hasText("Фэнтези") and isSelected()).fetchSemanticsNodes().isNotEmpty())break
   compose.onNodeWithText("Фэнтези").reveal().performClick();compose.waitForIdle()
  }
  waitFor("style selected"){compose.onAllNodes(hasText("Фэнтези") and isSelected()).fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("Что нарисовать?").reveal().performTextInput("Озеро на рассвете")
  compose.onNodeWithText("Что нарисовать?").performImeAction()
  clickWhenEnabled("Нарисовать бесплатно")
  waitFor("art result"){compose.onAllNodesWithText("Мои картинки").fetchSemanticsNodes().isNotEmpty()}
  assertTrue("art request: $artBody",artBody!!.contains("fantasy")&&artBody!!.contains("Озеро на рассвете"))
  compose.onNodeWithText("ГОТОВО").reveal();screenshot("23-ai-art")
  compose.onNodeWithText("Профиль").performClick()
  compose.onNodeWithText("English").reveal().performClick()
  waitFor("english"){compose.onAllNodesWithText("Sign out").fetchSemanticsNodes().isNotEmpty()}
  assertTrue("bottom bar not translated",compose.onAllNodesWithText("Catalog").fetchSemanticsNodes().isNotEmpty())
  screenshot("24-english-profile")
  compose.onNodeWithText("Catalog").performClick()
  waitFor("english catalog"){compose.onAllNodesWithText("Start AI chat").fetchSemanticsNodes().isNotEmpty()}
  screenshot("25-english-catalog")
  compose.onNodeWithText("Profile").performClick()
  compose.onNodeWithText("Русский").reveal().performClick()
  waitFor("russian again"){compose.onAllNodesWithText("Каталог").fetchSemanticsNodes().isNotEmpty()}
 }
 @Test fun invalidEmailPasswordAndMissingAgeAreExplainedWithoutARequest(){
  compose.onNodeWithText("Email").performTextInput("adult@example")
  compose.onNodeWithText("Пароль (от 10 символов)").performTextInput("short")
  clickWhenEnabled("Войти")
  waitFor("field errors"){compose.onAllNodesWithText("Проверь email: он должен выглядеть как name@example.com, латиницей и без пробелов").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("Пароль слишком короткий: нужно не меньше 10 символов (сейчас 5)").assertExists()
  screenshot("26-login-validation")
  compose.onNodeWithText("Email").performTextClearance()
  compose.onNodeWithText("Email").performTextInput("adult@example.test")
  compose.onNodeWithText("Пароль (от 10 символов)").performTextInput("-password")
  clickWhenEnabled("Создать аккаунт")
  waitFor("age error"){compose.onAllNodesWithText("Отметь, что тебе исполнилось 18 лет — без этого аккаунт создать нельзя").fetchSemanticsNodes().isNotEmpty()}
  assertEquals("no request may leave the phone with invalid input",0,authRequests)
  assertTrue(compose.onAllNodes(hasText("HTTP",substring=true)).fetchSemanticsNodes().isEmpty())
 }
 @Test fun wrongPasswordAndTakenEmailGetClearSeparateMessages(){
  compose.onNodeWithText("Email").performTextInput("adult@example.test")
  compose.onNodeWithText("Пароль (от 10 символов)").performTextInput("wrong-password-123")
  clickWhenEnabled("Войти")
  waitFor("login error"){compose.onAllNodesWithText("Неверный email или пароль. Если аккаунта ещё нет — нажми «Создать аккаунт».").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("Неверный email или пароль. Если аккаунта ещё нет — нажми «Создать аккаунт».").assertIsDisplayed()
  screenshot("27-login-wrong-password")
  compose.onNodeWithText("Email").performTextClearance();compose.onNodeWithText("Email").performTextInput("taken@example.test")
  compose.onNode(isToggleable()).reveal().performClick()
  clickWhenEnabled("Создать аккаунт")
  waitFor("register error"){compose.onAllNodesWithText("Этот email уже зарегистрирован — нажми «Войти».").fetchSemanticsNodes().isNotEmpty()}
  assertTrue(compose.onAllNodes(hasText("HTTP",substring=true)).fetchSemanticsNodes().isEmpty())
  assertEquals(2,authRequests)
  compose.onNodeWithText("Пароль (от 10 символов)").performTextClearance()
  compose.onNodeWithText("Пароль (от 10 символов)").performTextInput("testing-password-123")
  compose.onNodeWithText("Email").performTextClearance();compose.onNodeWithText("Email").performTextInput("adult@example.test")
  clickWhenEnabled("Войти")
  waitFor("signed in"){compose.onAllNodesWithText("Каталог").fetchSemanticsNodes().isNotEmpty()}
 }
 @Test fun matureSectionNeedsExplicitAdultConfirmation(){
  compose.onNodeWithText("Email").performTextInput("adult@example.test")
  compose.onNodeWithText("Пароль (от 10 символов)").performTextInput("testing-password-123")
  clickWhenEnabled("Войти")
  compose.waitUntil(20000){compose.onAllNodesWithText("Профиль").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("Профиль").performClick()
  compose.waitUntil(20000){compose.onAllNodesWithText("Раздел 18+ (Mature)").fetchSemanticsNodes().isNotEmpty()}
  compose.waitUntil(20000){compose.onAllNodes(isToggleable() and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
  compose.onNode(isToggleable() and isEnabled()).reveal().performClick()
  compose.waitUntil(20000){compose.onAllNodesWithText("Мне есть 18").fetchSemanticsNodes().isNotEmpty()}
  assertFalse("dialog alone must not enable 18+",showMature)
  compose.onNodeWithText("Мне есть 18").performClick()
  compose.waitUntil(20000){showMature}
  compose.onNodeWithText("Каталог").performClick()
  clickWhenEnabled("18+ Mature")
  compose.waitUntil(20000){compose.onAllNodesWithText("Северина Лис").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("18+").assertExists();screenshot("06-mature-section")
  clickWhenEnabled("Открыть 12 историй")
  compose.onNodeWithText("Аделина Мар").reveal();screenshot("10-studio-collection")
  clickWhenEnabled("Аделина Мар");screenshot("11-studio-profile")
  clickWhenEnabled("Начать историю")
  waitFor("studio chat"){compose.onAllNodesWithText("Напиши сообщение").fetchSemanticsNodes().isNotEmpty()}
  val draft=com.google.gson.JsonParser.parseString(studioDraft).asJsonObject
  assertTrue(draft.get("mature").asBoolean)
  assertTrue(draft.get("biography").asString.startsWith("28 лет"))
  assertEquals(StudioCast.heroes.first().portraitUrl,draft.get("avatarUrl").asString)
  screenshot("12-studio-chat")
  compose.activityRule.scenario.recreate()
  waitFor("studio session restored"){compose.onAllNodesWithText("Аделина Мар").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithContentDescription("Портрет персонажа Аделина Мар").assertExists()
 }
 @Test fun newPortraitsDecodeAndAdultsHaveUniqueProfiles(){
  assertEquals(12,StudioCast.heroes.size)
  assertEquals(12,StudioCast.heroes.map{it.name}.toSet().size)
  StudioCast.heroes.forEach { hero ->
   assertTrue(hero.age>=18);assertTrue(hero.greeting.isNotBlank());assertTrue(hero.personality.isNotBlank())
   compose.activity.assets.open("portraits/${hero.key}.webp").use {
    val image=android.graphics.BitmapFactory.decodeStream(it)
    assertEquals(1024,image.width);assertEquals(1536,image.height);image.recycle()
   }
  }
 }
 @Test fun mainNavigationAndEditorialScreens(){
  screenshot("00-welcome")
  compose.onNodeWithText("Email").performTextInput("adult@example.test")
  compose.onNodeWithText("Пароль (от 10 символов)").performTextInput("testing-password-123")
  clickWhenEnabled("Войти")
  waitFor("home navigation"){compose.onAllNodesWithText("Каталог").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("Создать").performClick()
  compose.onNodeWithText("Имя персонажа").reveal();screenshot("13-create-character")
  compose.onNodeWithText("Профиль").performClick()
  waitFor("account"){compose.onAllNodesWithText("Раздел 18+ (Mature)").fetchSemanticsNodes().isNotEmpty()}
  screenshot("14-account")
  compose.onNodeWithText("Обращение в поддержку").reveal();screenshot("15-settings-support")
  compose.onNodeWithText("Токены").performClick()
  waitFor("wallet"){compose.onAllNodesWithText("Баланс").fetchSemanticsNodes().isNotEmpty()}
  screenshot("16-wallet")
  compose.onNodeWithText("Ежедневный бонус").reveal();screenshot("17-daily-bonus")
  compose.onNodeWithText("NOVA2345").reveal();screenshot("18-referral")
  compose.onNodeWithText("Чаты").performClick()
  waitFor("chat list"){compose.onAllNodesWithText("Мои чаты").fetchSemanticsNodes().isNotEmpty()}
  screenshot("19-chat-list-premium")
  compose.onNodeWithText("Каталог").performClick();clickWhenEnabled("Моё избранное")
  waitFor("empty favorites"){compose.onAllNodesWithText("Опубликованных персонажей пока нет.").fetchSemanticsNodes().isNotEmpty()}
  screenshot("20-favorites")
 }

 @Test fun registrationPreservesAdultConsentAndLoadingState(){
  compose.onNodeWithText("Email").performTextInput("adult@example.test")
  compose.onNodeWithText("Пароль (от 10 символов)").performTextInput("testing-password-123")
  clickWhenEnabled("Создать аккаунт")
  waitFor("adult confirmation required"){compose.onAllNodesWithText("Отметь, что тебе исполнилось 18 лет — без этого аккаунт создать нельзя").fetchSemanticsNodes().isNotEmpty()}
  assertNull(registration)
  compose.onNode(isToggleable()).reveal().performClick()
  screenshot("21-registration")
  clickWhenEnabled("Создать аккаунт")
  screenshot("22-loading")
  waitFor("registered home"){compose.onAllNodesWithText("Каталог").fetchSemanticsNodes().isNotEmpty()}
  val body=com.google.gson.JsonParser.parseString(registration).asJsonObject
  assertTrue(body.get("adultConfirmed").asBoolean)
  assertEquals("adult@example.test",body.get("email").asString)
 }

}
