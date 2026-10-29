package com.tamaqai.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tamaqai.R
import com.tamaqai.data.local.AppDatabase
import com.tamaqai.data.local.UserSettings
import com.tamaqai.data.repository.MealRepositoryImpl
import com.tamaqai.data.repository.RoomPlanningRepository
import com.tamaqai.data.standalone.LocalPlanStore
import com.tamaqai.domain.model.DailyGoals
import com.tamaqai.domain.model.MealSlot
import com.tamaqai.domain.repository.PlanningRepository
import com.tamaqai.ui.screens.PlanningViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real ViewModel/Room with isolated owners and an in-memory DB; no Activity/Espresso reset. */
@RunWith(AndroidJUnit4::class)
class PlanningViewModelRecoveryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val clock = Clock.fixed(Instant.parse("2026-10-29T10:00:00Z"), ZoneOffset.UTC)

    private suspend fun fixture(ownerCheck: () -> Unit = {}, action: suspend (Fixture) -> Unit) {
        val owner = "planning-viewmodel-test-" + UUID.randomUUID()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val diary = MealRepositoryImpl(db.mealDao(), owner)
        val settings = UserSettings(context, owner)
        val store = ViewModelStore()
        val repository = CountingRepository(RoomPlanningRepository(db, owner, diary, clock, ownerCheck))
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = PlanningViewModel(
                repository, settings, LocalPlanStore(context, owner, diary, clock), CompletableDeferred(Unit), clock,
            ) as T
        }
        try {
            val model = withContext(Dispatchers.Main) { ViewModelProvider(store, factory)[PlanningViewModel::class.java] }
            action(Fixture(db, owner, settings, repository, model))
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            db.close()
            check(context.getSharedPreferences("daily_settings_$owner", 0).edit().clear().commit())
            check(context.getSharedPreferences("plans_$owner", 0).edit().clear().commit())
        }
    }

    private data class Fixture(val db: AppDatabase, val owner: String, val settings: UserSettings,
                               val repository: CountingRepository, val model: PlanningViewModel)

    private class CountingRepository(private val delegate: PlanningRepository) : PlanningRepository by delegate {
        val initializations = AtomicInteger()
        override suspend fun initialize() {
            initializations.incrementAndGet()
            delegate.initialize()
        }
    }

    @Test fun sameModelRecoversAfterLocalResetDeletesItsPlanningWorkspace() = runBlocking {
        fixture { value ->
            coroutineScope {
                val subscription = launch { value.model.state.collect {} }
                try {
                    withTimeout(10_000) { value.model.state.first { !it.loading && it.workspace != null } }
                    value.settings.save(DailyGoals(2000, 100, 60, 250), LocalDate.now(clock))
                    value.repository.addRecipe(LocalDate.now(clock).toString(), MealSlot.Breakfast,
                        "demo-oats-milk-banana", 1.0)
                    withTimeout(5_000) { value.model.state.first { it.workspace?.entries?.size == 1 } }
                    val initializationsBeforeReset = value.repository.initializations.get()
                    value.db.dailyDao().clearLocalRecords(value.owner)
                    val recovered = withTimeout(10_000) {
                        value.model.state.first { !it.loading && it.workspace?.entries?.isEmpty() == true }
                    }
                    assertNull(recovered.error)
                    assertEquals(3, recovered.workspace?.dayCount)
                    assertEquals(initializationsBeforeReset + 1, value.repository.initializations.get())
                    assertEquals(DailyGoals(2000, 100, 60, 250), value.settings.onDate(LocalDate.now(clock)))
                    assertNotNull(value.db.planningDao().workspace(value.owner, "default"))
                } finally { subscription.cancelAndJoin() }
            }
        }
    }

    @Test fun expiredOwnerClearsVisibleSnapshotAndNeverRecreatesItsWorkspace() = runBlocking {
        val valid = AtomicBoolean(true)
        // SessionClient.requireOwner uses this exact message during account changes.
        fixture(ownerCheck = { check(valid.get()) { "account_changed" } }) { value ->
            coroutineScope {
                val subscription = launch { value.model.state.collect {} }
                try {
                    withTimeout(10_000) { value.model.state.first { !it.loading && it.workspace != null } }
                    val initializationsBeforeReset = value.repository.initializations.get()
                    valid.set(false)
                    value.db.withTransaction {
                        value.db.planningDao().clearShopping(value.owner)
                        value.db.planningDao().clearWorkspaces(value.owner)
                    }
                    val failed = withTimeout(10_000) { value.model.state.first { it.error == R.string.account_login_again } }
                    assertNull(failed.workspace)
                    assertNull(failed.shopping)
                    assertTrue(failed.recipes.isEmpty())
                    assertEquals(initializationsBeforeReset, value.repository.initializations.get())
                    assertNull(value.db.planningDao().workspace(value.owner, "default"))
                } finally { subscription.cancelAndJoin() }
            }
        }
    }

    @Test fun rejectedMutationForChangedAccountClearsSnapshotWithoutAutomaticRetry() = runBlocking {
        val valid = AtomicBoolean(true)
        fixture(ownerCheck = { check(valid.get()) { "account_changed" } }) { value ->
            coroutineScope {
                val subscription = launch { value.model.state.collect {} }
                try {
                    withTimeout(10_000) { value.model.state.first { !it.loading && it.workspace != null } }
                    val initializationsBeforeChange = value.repository.initializations.get()
                    val before = value.db.planningDao().workspace(value.owner, "default")
                    valid.set(false)
                    withContext(Dispatchers.Main) {
                        value.model.addRecipe(LocalDate.now(clock).toString(), MealSlot.Breakfast,
                            "demo-oats-milk-banana", 1.0)
                    }
                    val failed = withTimeout(10_000) { value.model.state.first { it.error == R.string.account_login_again } }
                    assertNull(failed.workspace)
                    assertNull(failed.shopping)
                    assertTrue(failed.recipes.isEmpty())
                    assertEquals(initializationsBeforeChange, value.repository.initializations.get())
                    assertEquals(before, value.db.planningDao().workspace(value.owner, "default"))
                } finally { subscription.cancelAndJoin() }
            }
        }
    }
}
