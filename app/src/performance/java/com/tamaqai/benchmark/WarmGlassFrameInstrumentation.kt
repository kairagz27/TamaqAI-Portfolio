package com.tamaqai.benchmark

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.app.UiAutomation
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.tamaqai.BuildConfig
import com.tamaqai.MainActivity
import com.tamaqai.R
import com.tamaqai.TamaqApplication
import com.tamaqai.data.local.AppDatabase
import com.tamaqai.data.local.ThemeMode
import com.tamaqai.data.remote.AccountProfile
import com.tamaqai.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Native, self-targeted release render probe, compiled inside the isolated APK.
 * No AndroidX Test/Compose Test class, injected gesture, bitmap capture, account,
 * main-app state, or model/provider request is involved. R8 sees every fixture
 * call together with the application and can rewrite both consistently.
 */
class WarmGlassFrameInstrumentation : Instrumentation() {
    @Volatile private var activity: MainActivity? = null
    private lateinit var application: TamaqApplication
    private lateinit var automation: UiAutomation
    private var deadline = 0L
    private var scrolls = 0
    private var tabs = 0
    private var drawers = 0
    private var operation = "initializing"
    private var failureReason: String? = null
    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(value: Activity) {
            if (value is MainActivity) {
                activity = value
                value.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        override fun onActivityPaused(value: Activity) { if (activity === value) activity = null }
        override fun onActivityDestroyed(value: Activity) { if (activity === value) activity = null }
        override fun onActivityCreated(value: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(value: Activity) = Unit
        override fun onActivityStopped(value: Activity) = Unit
        override fun onActivitySaveInstanceState(value: Activity, state: Bundle) = Unit
    }

    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    private fun phase(value: String) {
        operation = value
        sendStatus(1, Bundle().apply { putString("warm_glass_phase", value) })
    }
    private fun requireProbe(condition: Boolean, reason: String) {
        if (!condition) { failureReason = reason; throw IllegalStateException() }
    }
    private fun bounded() = requireProbe(SystemClock.elapsedRealtime() < deadline, "probe_deadline")
    private fun await(timeout: Long = 8000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!condition()) {
            bounded()
            requireProbe(SystemClock.elapsedRealtime() < end, "state_timeout")
            Thread.sleep(80)
        }
    }
    private fun root(initial: Boolean = false): AccessibilityNodeInfo {
        val end = SystemClock.elapsedRealtime() + if (initial) 5000 else 1000
        while (true) {
            bounded()
            if (android.os.Build.VERSION.SDK_INT >= 34) automation.clearCache()
            val root = automation.rootInActiveWindow
            if (root?.packageName?.toString() == BuildConfig.APPLICATION_ID) return root
            requireProbe(initial || root == null, "foreground_left")
            requireProbe(SystemClock.elapsedRealtime() < end, "root_unavailable")
            Thread.sleep(80)
        }
    }
    private fun settle(milliseconds: Long = 320) {
        waitForIdleSync(); Thread.sleep(milliseconds); root()
    }
    private fun label(id: Int): String = requireNotNull(activity).getString(id)
    private fun findAction(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null || node.packageName?.toString() != BuildConfig.APPLICATION_ID) return null
        if (node.isVisibleToUser && (node.text?.toString() == text || node.contentDescription?.toString() == text)) {
            var candidate: AccessibilityNodeInfo = node
            while (!candidate.isClickable && !candidate.isSelected) {
                candidate = candidate.parent ?: break
                if (candidate.packageName?.toString() != BuildConfig.APPLICATION_ID) break
            }
            if (candidate.packageName?.toString() == BuildConfig.APPLICATION_ID && candidate.isVisibleToUser &&
                (candidate.isSelected || candidate.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })) return candidate
        }
        repeat(node.childCount) { index -> findAction(node.getChild(index), text)?.let { return it } }
        return null
    }
    private fun click(id: Int, selectedTab: Boolean = false) {
        operation = "click-" + targetContext.resources.getResourceEntryName(id)
        var action: AccessibilityNodeInfo? = null
        await { action = findAction(root(), label(id)); action != null }
        val target = requireNotNull(action)
        if (!(selectedTab && target.isSelected))
            requireProbe(target.performAction(AccessibilityNodeInfo.ACTION_CLICK), "click_rejected")
        settle()
        if (selectedTab) await { findAction(root(), label(id))?.isSelected == true }
    }
    private fun scrollContainer(node: AccessibilityNodeInfo, minHeight: Int): AccessibilityNodeInfo? {
        if (node.packageName?.toString() != BuildConfig.APPLICATION_ID) return null
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        if (node.isVisibleToUser && node.isScrollable && bounds.height() > minHeight) return node
        repeat(node.childCount) { index -> node.getChild(index)?.let { scrollContainer(it, minHeight)?.let { found -> return found } } }
        return null
    }
    private fun scroll(action: Int) {
        operation = if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) "scroll-forward" else "scroll-backward"
        val page = root()
        val bounds = Rect().also { page.getBoundsInScreen(it) }
        val container = scrollContainer(page, bounds.height() / 3)
        requireProbe(container != null, "scroll_container_missing")
        requireProbe(requireNotNull(container).performAction(action), "scroll_rejected")
        scrolls++
        settle()
    }
    private fun routine() {
        // Alternate one viewport down/up. Unlike repeated forward requests at
        // the end of a list, each accepted action traverses visible content.
        repeat(3) {
            scroll(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            scroll(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        }
        for (id in listOf(R.string.history, R.string.progress, R.string.profile, R.string.today)) {
            click(id, selectedTab = true); tabs++
        }
        click(R.string.menu)
        await { findAction(root(), label(R.string.close)) != null }
        click(R.string.close)
        await { findAction(root(), label(R.string.menu)) != null }
        drawers++
    }

    override fun onStart() {
        deadline = SystemClock.elapsedRealtime() + 140_000
        var passed = false
        var error: String? = null
        var registered = false
        try {
            requireProbe(BuildConfig.APPLICATION_ID == "com.tamaqai.performance" && !BuildConfig.DEBUG, "wrong_variant")
            application = targetContext.applicationContext as TamaqApplication
            runOnMainSync { application.registerActivityLifecycleCallbacks(callbacks) }; registered = true
            phase("awaiting-activity")
            await(45_000) { activity != null }
            val data = application.container
            requireProbe(data.owner == "guest", "owner_not_guest")
            application.preferences.continueGuest()
            phase("fixture-prepare")
            runBlocking {
                data.ready.await()
                requireProbe(AppDatabase.getInstance(targetContext).mealDao().getAllMeals().first()
                    .all { it.uid.startsWith("warm-glass-") }, "unrelated_records")
                data.dailyRepository.clearLocalRecords()
                data.settings.save(DailyGoals(2200, 120, 70, 270))
                data.account.save(AccountProfile(name = "Тестовый профиль"))
                repeat(12) { index -> data.mealRepository.confirm(Meal(
                    uid = "warm-glass-performance-$index",
                    nutrition = Nutrition("Тестовый приём пищи ${index + 1}", 150, 10, 6, 14),
                    details = MealDetails(listOf(MealComponent("Тестовый компонент", 100.0, 150.0, 10.0, 6.0, 14.0))))) }
            }
            runOnMainSync {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ru"))
                application.preferences.setTheme(ThemeMode.Light)
            }
            await { activity != null }
            runOnMainSync { activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            root(initial = true)
            click(R.string.today, selectedTab = true)
            phase("warmup"); repeat(2) { routine() }
            phase("measure-ready"); settle(1800)
            scrolls = 0; tabs = 0; drawers = 0
            phase("measure-start"); repeat(2) { routine() }
            phase("measure-done"); settle(3000)
            requireProbe(scrolls == 12 && tabs == 8 && drawers == 2, "action_count_mismatch")
            phase("finished"); passed = true
        } catch (failure: Throwable) {
            // No exception message, UI text, account values or row contents.
            error = failure.javaClass.simpleName
        } finally {
            if (registered) runOnMainSync { application.unregisterActivityLifecycleCallbacks(callbacks) }
            finish(if (passed) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
                putBoolean("warm_glass_probe_passed", passed)
                putInt("warm_glass_scroll_actions", scrolls)
                putInt("warm_glass_tab_selections", tabs)
                putInt("warm_glass_drawer_cycles", drawers)
                error?.let { putString("warm_glass_error_type", it) }
                failureReason?.let { putString("warm_glass_failure_reason", it) }
                if (!passed) putString("warm_glass_failed_operation", operation)
            })
        }
    }
}
