package com.tamaqai.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.tamaqai.BuildConfig
import com.tamaqai.MainActivity
import com.tamaqai.R
import com.tamaqai.TamaqApplication
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.Timeout
import java.io.File
import java.util.UUID

/** Repeats real navigation without signing in, seeding data or requesting AI. */
class PortfolioNavigationTest {
    private val rule = createAndroidComposeRule<MainActivity>()
    @get:Rule val bounded: RuleChain = RuleChain.outerRule(Timeout.seconds(180)).around(rule)
    private val app get() = rule.activity.application as TamaqApplication
    private val container get() = app.container
    private val args get() = InstrumentationRegistry.getArguments()
    private var phase = "guard"

    private fun await(tag: String) = rule.waitUntil(15_000) {
        rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun click(id: Int) {
        val label = rule.activity.getString(id)
        rule.waitUntil(15_000) {
            rule.onAllNodesWithText(label).filter(hasClickAction() and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        val node = rule.onAllNodesWithText(label).filter(hasClickAction() and isEnabled()).onFirst()
        runCatching { node.performScrollTo() }
        node.apricotClick()
        rule.waitForIdle()
    }

    @Test fun fiveAddSheetCatalogueAndBackCycles() {
        assumeTrue(args.getString("portfolioNavigationProbe") == "true")
        check(BuildConfig.APPLICATION_ID == "com.tamaqai.demo" && BuildConfig.PORTFOLIO_DEMO)
        check(BuildConfig.BACKEND_URL == "http://127.0.0.1:8100")
        val run = args.getString("portfolioNavigationRun") ?: UUID.randomUUID().toString()
        check(run.matches(Regex("[a-z0-9-]{1,80}")))
        val directory = File(requireNotNull(app.getExternalFilesDir("portfolio-navigation")), run)
        check(directory.mkdirs())
        fun mark(value: String) {
            phase = value
            File(directory, "phase").writeText(value)
        }
        try {
            mark("guard")
            runBlocking { container.ready.await() }
            val owner = container.owner
            check(owner != "guest")
            val profile = container.account.localProfile()
            check(profile.email == "portfolio@example.com" && profile.verified && profile.pilotLinked)
            val health = runBlocking { container.sessions.raw("/health", "GET") }
            check(health.getBoolean("demo") && health.getString("status") == "ok")
            rule.runOnUiThread {
                rule.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            await("root-screen-home")
            repeat(5) { index ->
                val cycle = index + 1
                mark("cycle-$cycle-open-sheet")
                rule.onNodeWithContentDescription(rule.activity.getString(R.string.add))
                    .performScrollTo().apricotClick()
                await("nutrient-glass-sheet")
                mark("cycle-$cycle-select-catalogue")
                click(R.string.catalog_product_option)
                await("catalog_screen")
                rule.onNodeWithTag("nutrient-glass-sheet").assertDoesNotExist()
                mark("cycle-$cycle-back")
                rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
                rule.waitForIdle()
                await("root-screen-home")
                check(container.owner == owner)
                mark("cycle-$cycle-complete")
            }
            File(directory, "result.json").writeText(JSONObject().put("cycles_completed", 5)
                .put("fake_ai", true).put("paid_calls", 0).put("account_unchanged", true).toString())
            mark("done")
        } catch (error: Throwable) {
            File(directory, "failure-causes.txt").writeText(portfolioCauseFrames(error))
            throw AssertionError("Portfolio navigation failed at $phase (${error.javaClass.simpleName}); submitted values withheld")
        }
    }
}

/** Bounded cause classes and stack locations only; messages can contain entered values. */
internal fun portfolioCauseFrames(error: Throwable): String = buildString {
    var cause: Throwable? = error
    val seen = mutableSetOf<Throwable>()
    repeat(8) { index ->
        val failure = cause ?: return@buildString
        if (!seen.add(failure)) return@buildString
        appendLine("cause[$index]=${failure.javaClass.name}")
        failure.stackTrace.take(80).forEach { appendLine(it.toString()) }
        cause = failure.cause
    }
}
