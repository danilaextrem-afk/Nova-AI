package ai.nova.app

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import java.io.File

/**
 * Real end-to-end sign-up and sign-in through the app's UI against the live NOVA server (Render).
 * Runs only when CI passes -Pandroid.testInstrumentationRunnerArguments.novaLiveUrl=https://…;
 * the account it creates is deleted at the end through the app's own "Delete account" flow.
 */
class NovaLiveAuthTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private val liveUrl:String?=InstrumentationRegistry.getArguments().getString("novaLiveUrl")
 private val email="live-android-${System.currentTimeMillis()}@example.com"
 private val password="Live-android-"+java.util.UUID.randomUUID().toString().take(8)+"-A1"

 @Before fun setUp(){
  Assume.assumeTrue("live server not configured for this run",!liveUrl.isNullOrBlank())
  SessionStore(compose.activity).clear()
  compose.runOnIdle{ViewModelProvider(compose.activity)[NovaViewModel::class.java].serverUrl=liveUrl!!}
 }
 @After fun finish(){ if(!liveUrl.isNullOrBlank())SessionStore(compose.activity).clear() }

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
  val n=compose.onNode(hasText(text) and isEnabled());runCatching{n.performScrollTo()};n.performClick()
 }
 private fun confirmAdult(){ if(compose.onAllNodes(isToggleable() and isOff()).fetchSemanticsNodes().isNotEmpty())compose.onNode(isToggleable()).performScrollTo().performClick() }
 private fun type(label:String,value:String){val n=compose.onNodeWithText(label);n.performTextClearance();n.performTextInput(value)}

 @Test fun registerSignOutSignInAndDeleteOnLiveServer(){
  // 1. Unknown account: the real server answers 401 and the app explains it.
  type("Email",email);type("Пароль (от 10 символов)",password)
  tap("Войти")
  waitText("unknown-account","Неверный email или пароль. Если аккаунта ещё нет — нажми «Создать аккаунт».")
  // 2. Registration with the age confirmation.
  confirmAdult()
  tap("Создать аккаунт")
  waitText("registered","Каталог");screenshot("live-01-registered")
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
  type("Email"," "+email.uppercase()+" ")
  tap("Войти")
  waitText("signed-in","Каталог");screenshot("live-02-signed-in")
  // 5. Clean up: delete the test account through the app.
  compose.onNodeWithText("Профиль").performClick()
  tap("Удалить аккаунт")
  tap("Удалить навсегда")
  waitText("deleted","Создать аккаунт")
 }
}
