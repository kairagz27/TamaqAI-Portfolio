package com.tamaqai.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.tamaqai.*
import com.tamaqai.R
import com.tamaqai.data.local.AppDatabase
import com.tamaqai.data.local.ThemeMode
import com.tamaqai.domain.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.Timeout
import java.io.File
import java.time.LocalDate
import java.util.UUID

/** Actual UI + isolated fake backend. No production package, credentials or AI. */
class PortfolioDemoTest {
    private val rule=createAndroidComposeRule<MainActivity>()
    @get:Rule val bounded:RuleChain=RuleChain.outerRule(Timeout.seconds(240)).around(rule)
    private val app get()=rule.activity.application as TamaqApplication
    private val container get()=app.container
    private val args get()=InstrumentationRegistry.getArguments()
    private val video get()=args.getString("portfolioVideo")=="true"
    private val directory get()=requireNotNull(app.getExternalFilesDir("portfolio"))
    private var stage="guard"
    private fun text(id:Int)=rule.activity.getString(id)
    private fun awaitRoot()=rule.waitUntil(20_000) {rule.onAllNodesWithTag("root-screen-home").fetchSemanticsNodes().isNotEmpty()}
    private fun click(id:Int) {
        rule.waitUntil(20_000) {rule.onAllNodesWithText(text(id)).filter(hasClickAction() and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
        val node=rule.onAllNodesWithText(text(id)).filter(hasClickAction() and isEnabled()).onFirst()
        runCatching {node.performScrollTo()};node.apricotClick();rule.waitForIdle()
    }
    private fun field(id:Int,value:String) {
        val node=rule.onNode(hasSetTextAction() and hasText(text(id)))
        node.performScrollTo();node.performTextReplacement(value)
    }
    private fun back() {rule.runOnUiThread {rule.activity.onBackPressedDispatcher.onBackPressed()};rule.waitForIdle()}
    private fun shot(name:String) {
        stage=name;rule.waitForIdle();Thread.sleep(300)
        // Modal panels create another Compose root. Capture the actual display
        // of this isolated synthetic demo instead of choosing/omitting a root.
        check(BuildConfig.APPLICATION_ID=="com.tamaqai.demo")
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        File(directory,"active-window-package").writeText(automation.rootInActiveWindow?.packageName?.toString() ?: "none")
        rule.waitUntil(5000) {automation.rootInActiveWindow?.packageName?.toString()==BuildConfig.APPLICATION_ID}
        check(automation.rootInActiveWindow?.packageName?.toString()==BuildConfig.APPLICATION_ID) {
            "Another window obscures the demo; capture rejected"
        }
        val image=requireNotNull(automation.takeScreenshot())
        File(directory,"$name.png").outputStream().use {check(image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))};image.recycle()
        File(directory,"phase").writeText(name)
        if(video) Thread.sleep(2500)
    }
    private fun menu() {
        rule.onNodeWithContentDescription(text(R.string.menu)).performScrollTo().apricotClick()
        rule.waitUntil(5000) {rule.onAllNodesWithTag("drawer-close").fetchSemanticsNodes().isNotEmpty()}
    }
    private fun closeMenu() {rule.onNodeWithTag("drawer-close").performScrollTo().apricotClick();rule.waitForIdle()}
    private fun seed()=runBlocking {
        container.ready.await()
        check(container.owner!="guest")
        check(container.mealRepository.getAllMeals().first().all {it.uid.startsWith("portfolio-")})
        container.dailyRepository.clearLocalRecords()
        container.settings.save(DailyGoals(2200,120,70,270))
        for((uid,name,calories) in listOf(Triple("breakfast","DEMO: oats and berries",410),Triple("snack","DEMO: yogurt",180))) {
            container.mealRepository.confirm(Meal(uid="portfolio-$uid",nutrition=Nutrition(name,calories,15,8,24),source=NutritionSource.Demo,
                details=MealDetails(listOf(MealComponent(name,200.0,calories.toDouble(),15.0,8.0,24.0)))))
        }
        val photo=container.photos.save("portfolio-photo",visualFoodFixture())
        container.mealRepository.saveDraft(Meal(uid="portfolio-photo",nutrition=Nutrition("DEMO: synthetic photo",0,0,0,0),status=MealStatus.PendingUpload,
            details=MealDetails(emptyList(),photoName=photo,analysisId=UUID.randomUUID().toString())))
        container.plans.local(MealPlan(id="portfolio-plan",demo=true,meals=listOf(PlannedMeal(dish="DEMO: manual lunch",time="13:00",
            ingredients=listOf(PlanIngredient(name="Synthetic ingredient",quantity=100.0,nutrition=PlanNutrients(120.0,5.0,4.0,16.0)))))))
    }
    @Test fun loginPhotoReviewPortionDiaryCatalogueAndSettings() {
        assumeTrue(args.getString("portfolioDemoProbe")=="true")
        check(BuildConfig.APPLICATION_ID=="com.tamaqai.demo" && BuildConfig.PORTFOLIO_DEMO)
        check(BuildConfig.BACKEND_URL=="http://127.0.0.1:8100")
        try {
            val input=File(app.filesDir,"portfolio-input.json")
            val credentials=JSONObject(input.readText());check(input.delete())
            check(credentials.getString("email").endsWith("@example.com"))
            check(runBlocking {container.sessions.raw("/health","GET")}.getBoolean("demo"))
            rule.runOnUiThread {
                rule.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"));app.preferences.setTheme(ThemeMode.Light)
            }
            if(container.owner!="guest") {
                runBlocking {container.account.logout()}
                rule.runOnUiThread {rule.activity.identityChanged()}
                rule.waitUntil(10_000) {container.owner=="guest"}
            }
            if(app.preferences.guestContinued) {awaitRoot();click(R.string.profile)}
            File(directory,"phase").writeText("ready")
            if(video) {
                val deadline=System.nanoTime()+60_000_000_000
                while(!File(directory,"go").exists() && System.nanoTime()<deadline) Thread.sleep(100)
                check(File(directory,"go").exists());File(directory,"go").delete()
            }
            stage="open-login";click(R.string.account_login)
            if(rule.onAllNodesWithText(text(R.string.email_method)).fetchSemanticsNodes().isNotEmpty()) click(R.string.email_method)
            stage="email-field";field(R.string.account_email,credentials.getString("email"))
            stage="password-field";field(R.string.account_password,credentials.getString("password"))
            // Espresso's global-root keyboard helper loses window focus on
            // API 36.1 after the IME opens; address this activity's insets.
            rule.runOnUiThread {
                androidx.core.view.WindowCompat.getInsetsController(rule.activity.window,rule.activity.window.decorView)
                    .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
            }
            shot("login")
            click(R.string.continue_action)
            rule.waitUntil(20_000) {container.owner!="guest" && container.nutrition.state.value.loaded}
            // Only the pre-created synthetic demo account is permitted here.
            if(rule.onAllNodesWithText(text(R.string.choose_goal)).fetchSemanticsNodes().isNotEmpty()) {
                click(R.string.goal_diary);click(R.string.continue_action)
            }
            awaitRoot();seed();rule.waitForIdle();click(R.string.today);shot("home-light")
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("DEMO: synthetic photo"))
            rule.onNodeWithText("DEMO: synthetic photo").apricotClick();shot("photo-draft-light")
            click(R.string.analyze)
            rule.waitUntil(20_000) {runBlocking {container.mealRepository.getMeal("portfolio-photo")?.source==NutritionSource.Demo}}
            val result=runBlocking {container.mealRepository.getMeal("portfolio-photo")!!}
            assertEquals("FAKE-DEVELOPMENT",result.details.model);shot("editor-light")
            rule.onNodeWithContentDescription(text(R.string.eaten_portion)+": "+text(R.string.portion_half)).performScrollTo().apricotClick()
            shot("portion-half-light");click(R.string.save_meal);awaitRoot()
            rule.waitUntil(10_000) {runBlocking {container.mealRepository.getMeal("portfolio-photo")?.status==MealStatus.Confirmed}}
            val meals=runBlocking {container.mealRepository.getAllMeals().first()}
            assertEquals(650,meals.filter {it.status==MealStatus.Confirmed}.sumOf {it.nutrition.calories})
            assertEquals(1,meals.count {it.uid=="portfolio-photo"})
            rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0);shot("confirmed-light")
            click(R.string.protein);shot("macros-light");rule.onNodeWithTag("nutrient-close").apricotClick()
            click(R.string.history);shot("history-light");click(R.string.profile);shot("profile-light");click(R.string.today)
            menu();shot("drawer-light");closeMenu()
            rule.onNodeWithContentDescription(text(R.string.add)).performScrollTo().apricotClick()
            click(R.string.catalog_product_option);rule.waitForIdle();Thread.sleep(800);shot("catalog-light");back()
            click(R.string.meal_plan);shot("plan-light");back()
            menu();rule.onNodeWithTag("drawer-appearance").performScrollTo().apricotClick();shot("settings-light")
            // State changes are real, not substituted screenshots.
            rule.runOnUiThread {app.preferences.setTheme(ThemeMode.Dark)};shot("settings-dark");back();click(R.string.today);shot("home-dark")
            click(R.string.history);shot("history-dark");click(R.string.profile);shot("profile-dark");click(R.string.today)
            menu();shot("drawer-dark");closeMenu()
            File(directory,"result.json").writeText(JSONObject().put("fake_ai",true).put("paid_calls",0).put("confirmed_calories",650)
                .put("single_confirmed_photo",true).put("login_ui",true).put("source_photo","native synthetic fixture; picker/camera not exercised")
                .put("cold_restart_requires_separate_test",true).toString())
            File(directory,"phase").writeText("done");if(video) Thread.sleep(5000)
        } catch(error:Throwable) {
            // Stack locations are useful diagnostics; exception messages may
            // contain field contents and are deliberately never serialized.
            File(directory,"failure-frames.txt").writeText(error.stackTrace.joinToString("\n"))
            File(directory,"failure-causes.txt").writeText(portfolioCauseFrames(error))
            // UI-test exceptions may contain entered text. Never log their details.
            throw AssertionError("Portfolio scenario failed at $stage (${error.javaClass.simpleName}); submitted values withheld")
        } finally {File(app.filesDir,"portfolio-input.json").delete()}
    }
}
