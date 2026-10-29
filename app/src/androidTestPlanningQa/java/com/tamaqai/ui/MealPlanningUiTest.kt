package com.tamaqai.ui

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
import com.tamaqai.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.Timeout
import java.io.File
import java.time.LocalDate

/** Real UI and Room, limited to a newly installed isolated QA package, never the user's app. */
class MealPlanningUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val bounded: RuleChain = RuleChain.outerRule(Timeout.seconds(240)).around(compose)
    private val app get() = compose.activity.application as TamaqApplication
    private val container get() = app.container
    private val repository get() = container.planningRepository
    private val directory get() = requireNotNull(app.getExternalFilesDir("meal-planning"))
    private val video get() = InstrumentationRegistry.getArguments().getString("planningVideo") == "true"
    private var phase = "guard"
    private fun text(id: Int) = compose.activity.getString(id)
    private fun workspace() = runBlocking { requireNotNull(repository.workspace.first()) }
    private fun shopping() = runBlocking { requireNotNull(repository.shopping.first()) }
    private fun meals() = runBlocking { container.mealRepository.getAllMeals().first() }

    private fun verticalList() = compose.onAllNodes(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).onFirst()
    private fun find(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        if (compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty()) {
            verticalList().performScrollToNode(matcher)
        }
        compose.waitUntil(10_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        return compose.onAllNodes(matcher).onFirst().also { runCatching { it.performScrollTo() } }
    }
    private fun tag(value: String) = find(hasTestTag(value))
    private fun clickTag(value: String) { find(hasTestTag(value) and hasClickAction() and isEnabled()).apricotClick(); compose.waitForIdle() }
    private fun clickText(id: Int) { find(hasText(text(id)) and hasClickAction() and isEnabled()).apricotClick(); compose.waitForIdle() }
    private fun field(tag: String, value: String) { find(hasTestTag(tag) and hasSetTextAction()).performTextReplacement(value) }
    private fun hideKeyboard() {
        compose.runOnUiThread {
            androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
                .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
        compose.waitForIdle()
    }
    private fun mark(value: String) { phase = value; File(directory, "phase").writeText(value) }
    private fun shot(name: String) {
        mark(name); compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        compose.waitUntil(5_000) { automation.rootInActiveWindow?.packageName?.toString() == BuildConfig.APPLICATION_ID }
        val original = requireNotNull(automation.takeScreenshot())
        val scaled = Bitmap.createScaledBitmap(original, 540, (original.height * 540.0 / original.width).toInt(), true)
        File(directory, "$name.png").outputStream().use { check(scaled.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        if (scaled !== original) scaled.recycle()
        original.recycle()
        if (video) Thread.sleep(750)
    }

    @Test fun threeDaysPortionsShoppingMarksManualRefreshShareAndPreservation() {
        check(BuildConfig.APPLICATION_ID == "com.tamaqai.planningqa.standalone")
        check(BuildConfig.BACKEND_URL.isEmpty() && container.remoteSessions == null)
        try {
            runBlocking {
                container.ready.await(); repository.initialize()
                // Only new module data inside this dedicated QA sandbox is prepared.
                repository.setPeriod(LocalDate.now(container.clock).toString(), 3)
                workspace().entries.forEach { repository.deleteEntry(it.id) }
                repository.refreshShopping()
                shopping().rows.filter { it.manual }.forEach { repository.removeManual(it.key) }
            }
            val oldMeals = meals().filter { it.status == MealStatus.Confirmed }
            val oldGoals = container.settings.history.value.toList()
            if (compose.onAllNodesWithTag("standalone-start").fetchSemanticsNodes().isNotEmpty()) clickTag("standalone-start")
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("root-screen-home").fetchSemanticsNodes().isNotEmpty() }
            clickText(R.string.mp_open_period)
            tag("meal-planning-screen").assertExists()
            compose.onNodeWithTag("planner-day-count", useUnmergedTree = true).assertTextEquals("3")
            val dates = workspace().dates
            mark("ready")
            if (video) {
                val go = File(directory, "go")
                val deadline = System.nanoTime() + 20_000_000_000L
                while (!go.exists() && System.nanoTime() < deadline) Thread.sleep(100)
                check(go.exists()); check(go.delete())
            }
            mark("first-recipe")
            clickTag("planner-add-${dates[0]}")
            clickTag("planner-meal-slot-Breakfast")
            clickTag("planner-recipe-demo-oats-milk-banana")
            compose.waitUntil(5_000) { workspace().entries.size == 1 }
            mark("second-recipe")
            clickTag("planner-add-${dates[1]}")
            clickTag("planner-recipe-demo-chicken-rice")
            compose.waitUntil(5_000) { workspace().entries.size == 2 }
            clickTag("planner-copy-${dates[0]}")
            clickTag("planner-copy-target-${dates[2]}")
            clickTag("planner-copy-confirm")
            compose.waitUntil(5_000) { workspace().entries.size == 3 }
            val first = workspace().entries.single { it.date == dates[0] }
            val copied = workspace().entries.single { it.date == dates[2] }
            assertNotEquals(first.id, copied.id)
            clickTag("planner-servings-${first.id}")
            field("planner-edit-servings", "2"); hideKeyboard(); clickTag("planner-servings-confirm")
            compose.waitUntil(5_000) { workspace().entries.single { it.id == first.id }.servings == 2.0 }
            shot("three-day-plan")
            clickTag("planner-shopping-tab"); clickTag("shopping-refresh")
            compose.waitUntil(5_000) { !shopping().isStale(workspace()) && shopping().rows.isNotEmpty() }
            shot("shopping-calculated")
            val milk = shopping().rows.single { it.productId == "milk" }
            val rice = shopping().rows.single { it.productId == "rice" }
            clickTag("shopping-status-${milk.key}-Bought")
            compose.waitUntil(5_000) { shopping().rows.single { it.key == milk.key }.status == ShoppingStatus.Bought }
            clickTag("shopping-status-${rice.key}-AtHome")
            clickTag("shopping-edit-note-${milk.key}")
            field("shopping-note-input", "Bring the cold bag"); hideKeyboard(); clickTag("shopping-note-save")
            compose.waitUntil(5_000) { shopping().rows.single { it.key == milk.key }.note == "Bring the cold bag" }
            clickTag("shopping-add-manual")
            field("shopping-manual-name", "Demo paper napkins")
            field("shopping-manual-quantity", "1")
            field("shopping-manual-note", "Reusable shopping bag")
            hideKeyboard(); shot("manual-shopping-item"); clickTag("shopping-manual-save")
            compose.waitUntil(5_000) { shopping().rows.any { it.manual && it.name == "Demo paper napkins" } }
            val manual = shopping().rows.single { it.name == "Demo paper napkins" }
            shot("shopping-marked")
            clickTag("planner-plan-tab")
            clickTag("planner-servings-${first.id}")
            field("planner-edit-servings", "3"); hideKeyboard(); clickTag("planner-servings-confirm")
            compose.waitUntil(5_000) { workspace().entries.single { it.id == first.id }.servings == 3.0 }
            clickTag("planner-shopping-tab")
            tag("shopping-stale").assertExists()
            assertTrue(shopping().isStale(workspace()))
            clickTag("shopping-refresh")
            compose.waitUntil(5_000) { !shopping().isStale(workspace()) }
            val updated = shopping().rows.single { it.key == milk.key }
            assertTrue(requireNotNull(updated.quantity) > requireNotNull(milk.quantity))
            assertTrue(updated.needsReview)
            assertEquals(ShoppingStatus.Bought, updated.status)
            assertEquals("Bring the cold bag", updated.note)
            assertEquals(manual, shopping().rows.single { it.key == manual.key })
            shot("shopping-needs-review")
            clickTag("shopping-ack-${milk.key}")
            compose.waitUntil(5_000) { !shopping().rows.single { it.key == milk.key }.needsReview }
            shot("shopping-refreshed")
            mark("system-share")
            clickTag("shopping-share")
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            compose.waitUntil(5_000) {
                automation.rootInActiveWindow?.packageName?.toString()?.let { it != BuildConfig.APPLICATION_ID } == true
            }
            val sharePackage = automation.rootInActiveWindow?.packageName?.toString()
            assertTrue(sharePackage in setOf("android", "com.android.intentresolver", "com.google.android.intentresolver", "com.android.systemui"))
            assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            compose.waitUntil(5_000) { automation.rootInActiveWindow?.packageName?.toString() == BuildConfig.APPLICATION_ID }
            // Planned entries alone never change the real diary.
            assertEquals(oldMeals, meals().filter { it.status == MealStatus.Confirmed })
            clickTag("planner-plan-tab")
            clickTag("planner-eat-${first.id}")
            field("planner-actual-servings", "0.5"); hideKeyboard()
            val callback = requireNotNull(find(hasTestTag("planner-servings-confirm") and hasClickAction()).fetchSemanticsNode().config[SemanticsActions.OnClick].action)
            compose.runOnUiThread { callback(); callback() }
            compose.waitUntil(5_000) { meals().count { it.status == MealStatus.Confirmed } == oldMeals.size + 1 }
            assertEquals(oldMeals.size + 1, meals().count { it.status == MealStatus.Confirmed })
            assertTrue(oldMeals.all { it in meals() })
            assertEquals(oldGoals, container.settings.history.value)
            clickTag("planner-shopping-tab")
            val config = compose.activity.resources.configuration
            val result = JSONObject().put("package", BuildConfig.APPLICATION_ID).put("screen_width_dp", config.screenWidthDp)
                .put("font_scale", config.fontScale).put("language", config.locales[0].language)
                .put("days", 3).put("plan_entries", 3).put("workspace_revision", workspace().revision)
                .put("manual_key", manual.key).put("milk_key", milk.key).put("manual_note", manual.note)
                .put("milk_note", updated.note).put("share_opened", true).put("no_message_sent", true)
                .put("confirmed_once_after_double_tap", true).put("old_diary_and_goals_preserved", true)
                .put("paid_ai_calls", 0)
            File(directory, "result.json").writeText(result.toString(2))
            mark("done")
        } catch (failure: Throwable) {
            File(directory, "failure-phase").writeText(phase)
            File(directory, "failure-message.txt").writeText(failure.message ?: failure.javaClass.simpleName)
            File(directory, "failure-stack.txt").writeText(failure.stackTrace.joinToString("\n"))
            throw AssertionError("Isolated planning QA failed at $phase (${failure.javaClass.simpleName}); only demo fixtures were used")
        }
    }
}
