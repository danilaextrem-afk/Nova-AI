package ai.nova.app

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Real end-to-end sign-up and sign-in through the app's UI against the live NOVA server (Render).
 * Runs only when CI passes -Pandroid.testInstrumentationRunnerArguments.novaLiveUrl=https://…;
 * the account it creates is deleted at the end through the app's own "Delete account" flow.
 */
class NovaLiveAuthTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private val liveUrl:String?=InstrumentationRegistry.getArguments().getString("novaLiveUrl")
 private val expectedCommit = InstrumentationRegistry.getArguments().getString("novaLiveCommit")
 private var cleanupNeeded = false
 private var registered = false
 private val email="live-android-${System.currentTimeMillis()}@example.com"
 private val password="Live-android-"+java.util.UUID.randomUUID().toString().take(8)+"-A1"

 @Before fun setUp(){
  Assume.assumeTrue("live server not configured for this run",!liveUrl.isNullOrBlank())
  val expected = expectedCommit
  if (!expected.isNullOrBlank()) {
   val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
   val base = requireNotNull(liveUrl).trimEnd('/')
   val health = probe(client, base + "/health")
   assertEquals("Release server commit", expected.take(7), health.get("commit")?.asString)
   probe(client, base + "/ready")
  }
  SessionStore(compose.activity).clear()
  compose.runOnIdle{ViewModelProvider(compose.activity)[NovaViewModel::class.java].serverUrl=liveUrl!!}
 }
 @After fun finish() {
  if (liveUrl.isNullOrBlank()) return
  val store = SessionStore(compose.activity)
  try {
   if (cleanupNeeded) runBlocking {
    val api = Retrofit.Builder()
     .baseUrl(requireNotNull(liveUrl).trimEnd('/') + "/")
     .client(OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build())
     .addConverterFactory(GsonConverterFactory.create())
     .build().create(NovaApi::class.java)
    val token = try {
     api.login(Credentials(email, password)).accessToken
    } catch (error: HttpException) {
     // A failed registration may have left no account to clean up.
     if (error.code() == 401 && !registered) return@runBlocking
     throw error
    }
    api.deleteAccount("Bearer " + token, mapOf("confirmation" to "DELETE"))
   }
  } finally {
   store.clear()
  }
 }

 private fun screenshot(name:String){
  val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
  val dir=File(compose.activity.getExternalFilesDir(null),"screenshots");dir.mkdirs()
  File(dir,name+"-"+image.width+"x"+image.height+".png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
 }
 /** The free server may be asleep: allow two minutes for its first answer. */
 private fun waitText(step:String,text:String){
  try{compose.waitUntil(120000){compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}}
  catch(e:androidx.compose.ui.test.ComposeTimeoutException){
   screenshot("live-failure-"+step)
   var state="";compose.runOnIdle{val vm=ViewModelProvider(compose.activity)[NovaViewModel::class.java];state="error=${vm.error}; loading=${vm.loading}"}
   throw AssertionError("Live step '$step' did not show '$text'; $state",e)
  }
 }
 private fun tap(text:String){
  compose.waitUntil(120000){compose.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
  val n = compose.onNode(hasText(text) and isEnabled())
  runCatching { n.performScrollTo() }
  n.assertIsDisplayed().performClick()
 }
 private fun confirmAdult(){ if(compose.onAllNodes(isToggleable() and isOff()).fetchSemanticsNodes().isNotEmpty())compose.onNode(isToggleable()).performScrollTo().performClick() }
 private fun type(label: String, value: String) {
  val field = compose.onNodeWithText(label)
  runCatching { field.performScrollTo() }
  field.performTextClearance()
  field.performTextInput(value)
  compose.waitForIdle()
 }

 @Test fun registerSignOutSignInAndDeleteOnLiveServer(){
  // 1. Unknown account: the real server answers 401 and the app explains it.
  type("Email",email);type("Пароль (от 10 символов)",password)
  tap("Войти")
  waitText("unknown-account","Неверный email или пароль. Если аккаунта ещё нет — нажми «Создать аккаунт».")
  // 2. Registration with the age confirmation.
  confirmAdult()
  cleanupNeeded = true
  tap("Создать аккаунт")
  waitText("registered","Каталог"); registered = true;screenshot("live-01-registered")
  // 3. Sign out, then the same email cannot be registered twice.
  compose.onNodeWithText("Профиль").performClick()
  tap("Выйти из аккаунта")
  waitText("signed-out","Создать аккаунт")
  type("Email",email);type("Пароль (от 10 символов)",password)
  confirmAdult()
  tap("Создать аккаунт")
  waitText("email-taken","Этот email уже зарегистрирован — нажми «Войти».")
  assertTrue(compose.onAllNodes(hasText("HTTP",substring=true)).fetchSemanticsNodes().isEmpty())
  // 4. Sign in with the right password, with stray spaces and capitals in the email.
  // Dismiss the previous error before touching the form again. Its snackbar overlays the sign-in actions.
  state { it.dismissMessages() }
  type("Email"," "+email.uppercase()+" ")
  submitLogin("sign-back-in")
  waitText("signed-in","Каталог");screenshot("live-02-signed-in")
  // 5. Clean up: delete the test account through the app.
  compose.onNodeWithText("Профиль").performClick()
  tap("Удалить аккаунт")
  tap("Удалить навсегда")
  waitText("deleted","Создать аккаунт")
  cleanupNeeded = false
 }

 private fun probe(client: OkHttpClient, url: String): com.google.gson.JsonObject {
  val request = okhttp3.Request.Builder().url(url).build()
  return client.newCall(request).execute().use { response ->
   assertEquals("Health probe for $url", 200, response.code)
   val body = com.google.gson.JsonParser.parseString(requireNotNull(response.body).string()).asJsonObject
   assertTrue("Health probe for $url", body.get("ok")?.asBoolean == true)
   body
  }
 }

 private fun model() = ViewModelProvider(compose.activity)[NovaViewModel::class.java]

 private fun waitState(step: String, predicate: (NovaViewModel) -> Boolean) {
  try {
   compose.waitUntil(120000) { compose.runOnIdle { predicate(model()) } }
  } catch (error: ComposeTimeoutException) {
   screenshot("live-failure-" + step)
   val details = compose.runOnIdle { "loading=${model().loading}, error=${model().error}" }
   throw AssertionError("Live step '$step' timed out. $details", error)
  }
 }

 private fun state(action: (NovaViewModel) -> Unit) = compose.runOnIdle { action(model()) }

 private fun submitLogin(step: String) {
  val accepted = compose.runOnIdle { model().email.trim().equals(email, ignoreCase = true) && model().password == password }
  assertTrue("$step: the sign-in form contains the test credentials", accepted)
  tap("Войти")
  waitState(step) { it.loading || it.accessToken != null || it.error != null || it.emailError != null || it.passwordError != null }
 }

 private fun send(text: String) {
  val previous = compose.runOnIdle { model().chatMessages.map { it.id }.toSet() }
  val field = compose.onNodeWithText("Напиши сообщение")
  field.performTextInput(text)
  val send = hasContentDescription("Отправить бесплатно") and isEnabled()
  compose.waitUntil(120000) { compose.onAllNodes(send).fetchSemanticsNodes().isNotEmpty() }
  compose.onNode(send).performClick()
  waitState("ai-reply") { vm ->
   !vm.loading && vm.chatMessages.any { it.role == "assistant" && it.id !in previous && it.content.isNotBlank() }
  }
 }

 @Test fun liveCreatorConversationMemoryImageAndWallet() {
  Assume.assumeTrue("Run as part of a commit-bound release check", !expectedCommit.isNullOrBlank())

  type("Email", email)
  type("Пароль (от 10 символов)", password)
  confirmAdult()
  cleanupNeeded = true
  tap("Создать аккаунт")
  waitState("register") { !it.loading && it.accessToken != null }
  registered = true

  tap("Каталог")
  waitState("discover") { !it.loading && it.catalog.isNotEmpty() }
  val guest = compose.runOnIdle { model().catalog.first() }
  screenshot("live-beta-catalog")

  tap("Создать")
  tap("✨ Создать персонажа с AI")
  type("Твоя идея", "Весёлая рыжеволосая путешественница, которая любит приключения")
  tap("Придумать")
  waitText("generated-card", "Применить ко всей карточке")
  tap("Применить ко всей карточке")
  waitState("apply-card") { it.draftName.isNotBlank() && it.draftGreeting.isNotBlank() }
  tap("Возраст *")
  tap("27")
  tap("Женский")
  waitState("adult-choices") { it.draftAge == 27 && it.draftGender == "female" }
  tap("✨ Создать персонажа")
  waitState("save-character") { !it.loading && it.lastCreated != null }
  val created = compose.runOnIdle { requireNotNull(model().lastCreated) }
  screenshot("live-beta-created")
  state { it.editCharacter(created) }
  waitState("open-editor") { !it.loading && it.editingCharacterId == created.id }
  val editedName = created.name.take(68) + " NOVA"
  val nameInput = compose.onNode(hasText("Имя") and hasSetTextAction())
  runCatching { nameInput.performScrollTo() }
  nameInput.performTextReplacement(editedName)
  tap("Сохранить изменения")
  waitState("save-edit") { !it.loading && it.lastCreated?.name == editedName }
  tap("Начать чат")
  waitState("new-chat") { !it.loading && it.activeChat?.characterId == created.id }
  send("Как тебя зовут? Ответь одним коротким предложением.")
  val initial = compose.runOnIdle { model().chatMessages.last() }
  tap("Другой ответ")
  waitState("regenerate") { !it.loading && it.chatMessages.lastOrNull()?.alternatives.orEmpty().contains(initial.content) }
  screenshot("live-beta-reply")

  val memory = "Моя экспедиция называется Медный Маяк."
  state { it.characterMemoryInput = memory; it.addCharacterMemory() }
  waitState("write-memory") { !it.loading && it.characterMemories.any { item -> item.content == memory } }
  state { it.responseStyle = "story"; it.saveStorySettings() }
  waitState("story-settings") { !it.loading && it.activeChat?.responseStyle == "story" }
  state { vm -> vm.openChat(requireNotNull(vm.activeChat)) }
  waitState("reload-history-and-memory") { vm ->
   !vm.loading && vm.activeChat?.responseStyle == "story" &&
    vm.characterMemories.any { it.content == memory } &&
    vm.chatMessages.any { it.id == initial.id && it.alternatives.orEmpty().contains(initial.content) }
  }

  state { it.toggleCastMember(guest.id) }
  waitState("group-cast") { !it.loading && it.activeChat?.participantIds.orEmpty().contains(guest.id) }
  val known = compose.runOnIdle { model().chatMessages.map { it.id }.toSet() }
  send("$editedName и ${guest.name}, опишите по очереди, что заметили у маяка.")
  waitState("group-replies") { vm ->
   val speakers = vm.chatMessages.filter { it.id !in known && it.role == "assistant" && it.content.isNotBlank() }
    .mapNotNull { it.speakerId }.toSet()
   speakers.containsAll(listOf(created.id, guest.id))
  }
  screenshot("live-beta-group-story")

  state { it.openArt("Медный маяк на скале на рассвете") }
  waitState("art-available") { !it.loading && it.artStatus?.configured == true && (it.artStatus?.freeLeft ?: 0) > 0 }
  tap("Нарисовать бесплатно")
  waitState("generated-art") { !it.loading && (it.artLatest?.length() ?: 0) > 0 }
  val image = compose.runOnIdle { requireNotNull(model().artLatest) }
  val bitmap = android.graphics.BitmapFactory.decodeFile(image.path)
  assertNotNull("Generated image must decode", bitmap)
  bitmap?.recycle()
  screenshot("live-beta-art")
  state { it.deleteArt(image); it.changeTab(NovaTab.WALLET) }
  waitState("wallet") { !it.loading && it.wallet != null }
  assertEquals("Free chat and image did not debit the new account", 0, compose.runOnIdle { model().wallet!!.total })

  tap("Профиль")
  tap("Выйти из аккаунта")
  waitText("logout", "Создать аккаунт")
  type("Email", email)
  type("Пароль (от 10 символов)", password)
  submitLogin("login-again")
  waitState("login-again") { !it.loading && it.accessToken != null }
  tap("Профиль")
  tap("Удалить аккаунт")
  tap("Удалить навсегда")
  waitText("delete-beta-account", "Создать аккаунт")
  cleanupNeeded = false
 }
}
