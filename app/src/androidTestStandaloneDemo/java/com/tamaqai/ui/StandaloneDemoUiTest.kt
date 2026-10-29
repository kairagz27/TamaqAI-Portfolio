package com.tamaqai.ui

import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.tamaqai.BuildConfig
import com.tamaqai.MainActivity
import com.tamaqai.R
import com.tamaqai.TamaqApplication
import com.tamaqai.data.local.ThemeMode
import com.tamaqai.data.standalone.LocalPlanStore
import com.tamaqai.domain.model.*
import com.tamaqai.domain.usecase.DiaryTotals
import com.tamaqai.domain.usecase.NutritionCalculator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.Timeout
import java.io.File
import java.text.NumberFormat
import java.time.LocalDate

/** Real shared Compose screens and Room; only the separately installed offline demo may run these checks. */
class StandaloneDemoUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val bounded: RuleChain = RuleChain.outerRule(Timeout.seconds(180)).around(compose)
    private val app get() = compose.activity.application as TamaqApplication
    private val container get() = app.container
    private val demo get() = checkNotNull(container.standalone)
    private val directory get() = requireNotNull(app.getExternalFilesDir("standalone"))
    private val video get() = InstrumentationRegistry.getArguments().getString("standaloneVideo") == "true"
    private var phase = "guard"
    // Home also contains a horizontal LazyRow for dates; it cannot scroll the food list.
    private val verticalLazy = hasScrollToIndexAction() and
        SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

    private fun text(id: Int) = compose.activity.getString(id)
    private fun meals() = runBlocking { container.mealRepository.getAllMeals().first() }
    private fun confirmed() = meals().filter { it.status == MealStatus.Confirmed }
    private fun awaitHome() = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag("root-screen-home").fetchSemanticsNodes().isNotEmpty()
    }
    private fun guardAndReset() {
        check(BuildConfig.APPLICATION_ID == "com.tamaqai.standalone")
        check(BuildConfig.BACKEND_URL.isEmpty())
        assertEquals(PackageManager.PERMISSION_DENIED,
            app.packageManager.checkPermission(android.Manifest.permission.INTERNET, BuildConfig.APPLICATION_ID))
        runBlocking { container.ready.await(); demo.reset() }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("standalone-start").filter(isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("standalone", container.owner)
        assertEquals(6, confirmed().size)
        assertEquals("", container.account.localProfile().email)
        assertEquals("", container.account.localProfile().phone)
        compose.runOnUiThread {
            compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            app.preferences.setTheme(ThemeMode.Light)
        }
    }
    private fun find(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        if (compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty()) {
            // Lazy items must first be materialized; ordinary scrolling Columns are already composed.
            runCatching { compose.onAllNodes(verticalLazy).onFirst().performScrollToNode(matcher) }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        return compose.onAllNodes(matcher).onFirst().also { runCatching { it.performScrollTo() } }
    }
    private fun clickTag(tag: String) { find(hasTestTag(tag) and hasClickAction() and isEnabled()).apricotClick(); compose.waitForIdle() }
    private fun click(id: Int) { find(hasText(text(id)) and hasClickAction() and isEnabled()).apricotClick(); compose.waitForIdle() }
    private fun field(id: Int, value: String) { find(hasText(text(id)) and hasSetTextAction()).performTextReplacement(value) }
    private fun hideKeyboard() {
        compose.runOnUiThread {
            androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
                .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
        compose.waitForIdle()
    }
    private fun back() { compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle() }
    private fun menu() {
        find(hasContentDescription(text(R.string.menu)) and hasClickAction()).apricotClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("drawer-close").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("drawer-local-only").assertExists()
        compose.onNodeWithTag("drawer-login").assertDoesNotExist()
        compose.onNodeWithTag("drawer-logout").assertDoesNotExist()
    }
    private fun top() {
        compose.onAllNodes(verticalLazy).onFirst().performScrollToIndex(0)
        compose.waitForIdle()
    }
    private fun doubleConfirm(id: Int) {
        val node = find(hasText(text(id)) and hasClickAction() and isEnabled()).fetchSemanticsNode()
        val action = requireNotNull(node.config[SemanticsActions.OnClick].action)
        // Invoke the real click callback twice before recomposition can disable the button.
        compose.runOnUiThread { assertTrue(action()); action() }
        compose.waitForIdle()
    }
    private fun shot(name: String) {
        phase = name
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        compose.waitUntil(5_000) { automation.rootInActiveWindow?.packageName?.toString() == BuildConfig.APPLICATION_ID }
        check(automation.rootInActiveWindow?.packageName?.toString() == BuildConfig.APPLICATION_ID) { "Demo is obscured; capture rejected" }
        val original = requireNotNull(automation.takeScreenshot())
        val height = (original.height * (540.0 / original.width)).toInt()
        val image = Bitmap.createScaledBitmap(original, 540, height, true)
        File(directory, "$name.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        if (image !== original) image.recycle()
        original.recycle()
        File(directory, "phase").writeText(name)
        if (video) Thread.sleep(1_000)
    }
    private fun guarded(work: () -> Unit) {
        try { work() }
        catch (failure: Throwable) {
            File(directory, "failure-phase").writeText(phase)
            File(directory, "failure-frames.txt").writeText(failure.stackTrace.joinToString("\n"))
            // This class resets only its own isolated package and enters synthetic data.
            // Keep diagnostic text in private evidence files rather than instrumentation logs.
            File(directory, "failure-message.txt").writeText(failure.message ?: "<no diagnostic message>")
            throw AssertionError("Standalone UI failed at $phase (${failure.javaClass.simpleName}); only synthetic data were used")
        }
    }
    private fun evidence(name: String, value: JSONObject) {
        val config = compose.activity.resources.configuration
        File(directory, "$name.json").writeText(value.put("application_id", BuildConfig.APPLICATION_ID)
            .put("backend_url_empty", BuildConfig.BACKEND_URL.isEmpty()).put("network_permission", false)
            .put("screen_width_dp", config.screenWidthDp).put("font_scale", config.fontScale)
            .put("language", config.locales[0].language).put("paid_ai_calls", 0).toString(2))
    }

    @Test fun bundledPhotoComponentsPortionDoubleConfirmAndRealDiary() = guarded {
        guardAndReset()
        shot("welcome")
        File(directory, "phase").writeText("ready")
        if (video) {
            val go = File(directory, "go")
            val until = System.nanoTime() + 20_000_000_000L
            while (!go.exists() && System.nanoTime() < until) Thread.sleep(100)
            check(go.exists()) { "Video controller did not signal readiness" }
            check(go.delete())
        }
        clickTag("standalone-start"); awaitHome(); shot("home-light")
        val initial = confirmed().map { it.uid }.toSet()
        clickTag("scan-food"); shot("example-picker")
        phase = "sample-open-editor"
        clickTag("standalone-sample-salad")
        // A persisted draft alone is not proof that navigation reached the editor.
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text(R.string.review_title)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10_000) { meals().any { it.uid !in initial && it.status == MealStatus.NeedsReview } }
        val uid = meals().single { it.uid !in initial }.uid
        val prepared = runBlocking { requireNotNull(container.mealRepository.getMeal(uid)) }
        assertEquals("LOCAL-PREPARED-EXAMPLE", prepared.details.model)
        assertEquals(NutritionSource.Demo, prepared.source)
        assertEquals(4, prepared.details.components.size)
        assertNotNull(container.photos.availableFile(prepared.details.photoName))
        shot("editor-light")
        phase = "whole-portion-half"
        find(hasContentDescription(text(R.string.eaten_portion) + ": " + text(R.string.portion_half))).apricotClick()
        phase = "first-component-quarter"
        find(hasContentDescription(compose.activity.getString(R.string.component_amount, 1) + ": " + text(R.string.portion_quarter))).apricotClick()
        phase = "first-component-mass"
        find(hasContentDescription(text(R.string.mass)) and hasSetTextAction()).performTextReplacement("200")
        hideKeyboard()
        phase = "local-edits-persisted"
        compose.waitUntil(10_000) {
            val saved = runBlocking { container.mealRepository.getMeal(uid) }
            saved?.details?.portion == .5 && saved.details.components.first().consumedPortion == .25 && saved.details.components.first().massGrams == 200.0
        }
        val edited = runBlocking { requireNotNull(container.mealRepository.getMeal(uid)) }
        val expected = NutritionCalculator().calculate(edited.nutrition.name, edited.details.components, edited.details.portion)
        assertEquals(119, expected.calories)
        assertEquals(30.0, edited.details.components.first().calories, .0001)
        shot("editor-portion-and-component")
        doubleConfirm(R.string.save_meal); awaitHome()
        compose.waitUntil(10_000) { confirmed().size == initial.size + 1 }
        val saved = confirmed().single { it.uid == uid }
        assertEquals(expected, saved.nutrition)
        assertEquals(1, confirmed().count { it.uid == uid })
        assertEquals(initial.size + 1, confirmed().size)
        phase = "home-calorie-total"
        top()
        val todayCalories = confirmed().filter { DiaryTotals.dateOf(it) == LocalDate.now() }.sumOf { it.nutrition.calories }
        val formattedCalories = NumberFormat.getIntegerInstance(compose.activity.resources.configuration.locales[0]).format(todayCalories)
        compose.waitUntil(5_000) {
            // CaloriesCard is one accessible clickable surface; its text child
            // tag belongs to the unmerged tree, while the displayed text is real.
            compose.onAllNodes(hasTestTag("calorie-value") and hasText(formattedCalories), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("calorie-value", useUnmergedTree = true).assertTextEquals(formattedCalories)
        shot("confirmed-light")
        click(R.string.protein); shot("macros-light"); clickTag("nutrient-close")
        click(R.string.history); shot("history-light")
        click(R.string.progress); shot("progress-light")
        click(R.string.profile); shot("profile-light")
        compose.onAllNodesWithText(text(R.string.account_login)).assertCountEquals(0)
        click(R.string.today); menu(); shot("drawer-light"); clickTag("drawer-close")
        evidence("photo-scenario", JSONObject().put("local_prepared_model", prepared.details.model)
            .put("sample_uid", uid).put("portion", .5).put("first_component_fraction", .25)
            .put("first_component_mass", 200).put("sample_calories", expected.calories)
            .put("confirmed_after_double_click", 1).put("confirmed_meals", confirmed().size).put("today_calories", todayCalories))
        File(directory, "phase").writeText("done")
    }

    @Test fun localProfileGoalsPlanCatalogManualEntryAndConfirmedReset() = guarded {
        guardAndReset(); clickTag("standalone-start"); awaitHome()
        click(R.string.profile); click(R.string.edit_profile)
        field(R.string.profile_name, "Portfolio Demo"); hideKeyboard(); click(R.string.continue_action)
        compose.waitUntil(5_000) { container.account.localProfile().name == "Portfolio Demo" }
        click(R.string.goals_title)
        field(R.string.calorie_goal_label, "2300"); hideKeyboard(); click(R.string.save)
        compose.waitUntil(5_000) { container.settings.onDate(LocalDate.now()).calories == 2300 }
        shot("goals-local"); back()
        click(R.string.meal_plan); shot("plan-local")
        val planned = container.plans.plans.value.single()
        val beforePlan = confirmed().size
        assertEquals(6, beforePlan)
        click(R.string.plan_edit_meal)
        field(R.string.plan_dish, "Demo dinner — local edit"); hideKeyboard(); click(R.string.plan_save_meal)
        compose.waitUntil(5_000) { container.plans.plans.value.single().meals.single().dish == "Demo dinner — local edit" }
        assertEquals(beforePlan, confirmed().size)
        click(R.string.plan_eat); field(R.string.actual_portion, "50"); hideKeyboard(); doubleConfirm(R.string.confirm)
        val planUid = LocalPlanStore.planMealUid(planned.id, planned.meals.single().id)
        compose.waitUntil(5_000) { confirmed().any { it.uid == planUid } }
        assertEquals(1, confirmed().count { it.uid == planUid })
        assertEquals(beforePlan + 1, confirmed().size)
        back(); click(R.string.today)
        find(hasContentDescription(text(R.string.add)) and hasClickAction()).apricotClick()
        click(R.string.catalog_product_option)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("catalog_search").fetchSemanticsNodes().isNotEmpty() }
        val product = runBlocking { container.catalog.search("", "product").entries.first() }
        find(hasTestTag("catalog_search") and hasSetTextAction()).performTextReplacement(product.name)
        hideKeyboard()
        clickTag("catalog_choose_${product.id}")
        compose.waitUntil(5_000) {compose.onAllNodesWithText(text(R.string.catalog_loading)).fetchSemanticsNodes().isEmpty()}
        shot("catalog-local")
        find(hasTestTag("catalog_quantity") and hasSetTextAction()).performTextReplacement("50")
        hideKeyboard(); clickTag("catalog_confirm")
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text(R.string.catalog_calculated_title)).fetchSemanticsNodes().isNotEmpty() }
        clickTag("catalog_confirm")
        compose.waitUntil(10_000) { meals().any { it.status != MealStatus.Confirmed && it.details.catalogSnapshot != null } }
        click(R.string.save_meal); awaitHome()
        assertEquals(beforePlan + 2, confirmed().size)
        clickTag("manual-food"); field(R.string.name, "Demo manual bowl"); click(R.string.add_component)
        find(hasSetTextAction() and hasText(compose.activity.getString(R.string.component_number, 1))).performTextReplacement("Demo ingredient")
        find(hasContentDescription(text(R.string.calories_unit)) and hasSetTextAction()).performTextReplacement("100")
        find(hasContentDescription(text(R.string.protein)) and hasSetTextAction()).performTextReplacement("5")
        find(hasContentDescription(text(R.string.fat)) and hasSetTextAction()).performTextReplacement("4")
        find(hasContentDescription(text(R.string.carbs)) and hasSetTextAction()).performTextReplacement("11")
        hideKeyboard(); shot("manual-local"); click(R.string.save_meal); awaitHome()
        compose.waitUntil(5_000) { confirmed().any { it.nutrition.name == "Demo manual bowl" && it.nutrition.calories == 100 } }
        assertEquals(beforePlan + 3, confirmed().size)
        find(hasText("Demo manual bowl") and hasClickAction()).apricotClick()
        find(hasContentDescription(text(R.string.eaten_portion) + ": " + text(R.string.portion_quarter))).apricotClick()
        click(R.string.save_meal); awaitHome()
        compose.waitUntil(5_000) { confirmed().any { it.nutrition.name == "Demo manual bowl" && it.nutrition.calories == 25 } }
        assertEquals(beforePlan + 3, confirmed().size)
        find(hasText("Demo manual bowl") and hasClickAction()).apricotClick()
        click(R.string.meal_delete_title)
        find(hasText(text(R.string.delete)) and hasClickAction() and hasAnyAncestor(isDialog())).apricotClick()
        awaitHome()
        compose.waitUntil(5_000) { confirmed().none { it.nutrition.name == "Demo manual bowl" } }
        assertEquals(beforePlan + 2, confirmed().size)
        // Reset through the visible settings UI, not by clearing package data.
        menu(); clickTag("drawer-appearance"); click(R.string.theme_dark); back()
        val languageBeforeReset = compose.activity.resources.configuration.locales[0].language
        menu(); clickTag("drawer-settings"); click(R.string.standalone_reset)
        compose.onAllNodesWithText(text(R.string.standalone_reset_title)).fetchSemanticsNodes().also { assertTrue(it.isNotEmpty()) }
        find(hasText(text(R.string.standalone_reset)) and hasClickAction() and hasAnyAncestor(isDialog())).apricotClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("standalone-start").filter(isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(ThemeMode.Dark, app.preferences.theme.value)
        assertEquals(languageBeforeReset, compose.activity.resources.configuration.locales[0].language)
        assertEquals(6, confirmed().size)
        assertEquals("Demo", container.account.localProfile().name)
        assertEquals(2100, container.settings.onDate(LocalDate.now()).calories)
        clickTag("standalone-start"); awaitHome(); shot("reset-home-dark")
        assertEquals(6, confirmed().size)
        evidence("local-features", JSONObject().put("profile_edit", true).put("goals_edit", true).put("plan_edit", true)
            .put("plan_confirm_double_click_one_record", true).put("catalog_search_calculation_confirm", true)
            .put("manual_entry_confirm_edit_delete", true).put("ui_reset_restored_seed", true)
            .put("reset_preserved_theme", true).put("reset_preserved_language", true))
    }

    @Test fun languagesBothThemesAndLocalDrawerStayUsable() = guarded {
        guardAndReset(); clickTag("standalone-start"); awaitHome()
        val locales = listOf("ru" to R.string.language_ru, "kk" to R.string.language_kk, "en" to R.string.language_en)
        for ((language, label) in locales) {
            menu(); clickTag("drawer-language"); click(label)
            compose.waitUntil(10_000) { compose.activity.resources.configuration.locales[0].language == language }
            if (compose.onAllNodesWithTag("root-screen-home").fetchSemanticsNodes().isEmpty()) back()
            awaitHome()
            compose.onNodeWithText(text(R.string.standalone_badge)).assertExists()
            for ((mode, button) in listOf(ThemeMode.Light to R.string.theme_light, ThemeMode.Dark to R.string.theme_dark)) {
                menu(); clickTag("drawer-appearance"); click(button)
                assertEquals(mode, app.preferences.theme.value); back(); awaitHome(); top()
                compose.onNodeWithTag("calorie-value", useUnmergedTree = true).assertIsDisplayed()
                shot("home-$language-${mode.name.lowercase()}")
                if (language == "en") {
                    click(R.string.history); shot("history-en-${mode.name.lowercase()}")
                    click(R.string.progress); shot("progress-en-${mode.name.lowercase()}")
                    click(R.string.profile); shot("profile-en-${mode.name.lowercase()}")
                    click(R.string.today); menu(); shot("drawer-en-${mode.name.lowercase()}"); clickTag("drawer-close")
                }
            }
        }
        assertEquals(6, confirmed().size)
        evidence("localization-themes", JSONObject().put("languages", "ru,kk,en").put("themes", "light,dark")
            .put("local_profile_without_login_or_logout", true).put("seed_record_count_unchanged", true))
    }
}
