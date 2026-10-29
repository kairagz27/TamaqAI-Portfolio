package com.tamaqai.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tamaqai.data.local.AppDatabase
import com.tamaqai.data.local.PhotoStore
import com.tamaqai.data.local.UserSettings
import com.tamaqai.data.repository.MealRepositoryImpl
import com.tamaqai.data.standalone.*
import com.tamaqai.domain.model.MealStatus
import com.tamaqai.domain.model.NutritionSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** In-memory Room and namespaced preferences; no main diary, identity, backend or ledger is touched. */
@RunWith(AndroidJUnit4::class)
class StandaloneDemoPersistenceTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var files: File
    private val clock = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC)
    private lateinit var diary: MealRepositoryImpl
    private lateinit var settings: UserSettings
    private lateinit var account: LocalAccountStore
    private lateinit var nutrition: LocalNutritionStore
    private lateinit var plans: LocalPlanStore
    private lateinit var catalog: LocalCatalogStore
    private lateinit var controller: StandaloneDemoController

    @Before fun setup() {
        val original = ApplicationProvider.getApplicationContext<Context>()
        val namespace = "standalone_test_${UUID.randomUUID()}_"
        files = File(original.cacheDir, namespace).apply { mkdirs() }
        context = object : ContextWrapper(original) {
            override fun getFilesDir(): File = files
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                original.getSharedPreferences(namespace + name, mode)
        }
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        diary = MealRepositoryImpl(database.mealDao(), StandaloneDemoController.OWNER)
        settings = UserSettings(context, StandaloneDemoController.OWNER)
        account = LocalAccountStore(context, StandaloneDemoController.OWNER)
        nutrition = LocalNutritionStore(context, StandaloneDemoController.OWNER, settings, clock)
        plans = LocalPlanStore(context, StandaloneDemoController.OWNER, diary, clock)
        catalog = LocalCatalogStore(context, database.catalogDao(), StandaloneDemoController.OWNER, clock)
        controller = newController(clock)
    }
    private fun newController(time: Clock) = StandaloneDemoController(context, database, diary, settings,
        account, nutrition, plans, catalog, PhotoStore(context), time, context::getString)
    @After fun cleanup() { database.close(); files.deleteRecursively() }

    @Test fun seedOnceEditAndDateRolloverPreserveRoomRows() = runBlocking {
        controller.initialize()
        val original = diary.getAllMeals().first()
        assertEquals(6, original.size)
        assertEquals(4, database.dailyDao().weights(StandaloneDemoController.OWNER).first().size)
        assertTrue(account.localProfile().email.isEmpty())
        assertTrue(account.localProfile().phone.isEmpty())
        assertEquals(2100, settings.onDate(LocalDate.now(clock)).calories)
        val selected = original.first()
        val draftId = diary.prepareEdit(selected.uid)
        val draft = requireNotNull(diary.getMeal(draftId))
        diary.confirm(draft.copy(details = draft.details.copy(portion = .5, userEdited = true)))
        val edited = requireNotNull(diary.getMeal(selected.uid))
        newController(Clock.offset(clock, java.time.Duration.ofDays(1))).initialize()
        assertEquals(6, diary.getAllMeals().first().size)
        assertEquals(.5, requireNotNull(diary.getMeal(edited.uid)).details.portion, 0.0)
        assertEquals(original.map { it.uid }.toSet(), diary.getAllMeals().first().map { it.uid }.toSet())
    }

    @Test fun builtInPhotoUsesMatchingResultAndDoubleConfirmationCreatesOneEntry() = runBlocking {
        controller.initialize()
        val sample = controller.samples().single()
        val uid = controller.createSample(sample.id, LocalDate.now(clock))
        val draft = requireNotNull(diary.getMeal(uid))
        assertEquals(MealStatus.NeedsReview, draft.status)
        assertEquals(NutritionSource.Demo, draft.source)
        assertEquals(4, draft.details.components.size)
        assertEquals(237, draft.nutrition.calories)
        assertEquals("LOCAL-PREPARED-EXAMPLE", draft.details.model)
        diary.confirm(draft)
        diary.confirm(draft)
        assertEquals(7, diary.getAllMeals().first().size)
        try {
            controller.analysis.analyzePhoto(byteArrayOf(1, 2, 3), "arbitrary", "")
            fail("Arbitrary photo must not receive the bundled result")
        } catch (expected: IllegalArgumentException) {
            assertEquals("standalone_own_photo_unavailable", expected.message)
        }
    }

    @Test fun offlineCatalogAndPlanConfirmAreLocalAndIdempotent() = runBlocking {
        controller.initialize()
        assertTrue(catalog.isBundled)
        assertFalse(catalog.search("", "product").offline)
        assertEquals(39, catalog.search("", "product").entries.size)
        val product = catalog.search("", "product").entries.first { it.canCalculate }
        val calculation = catalog.calculate(product, product.defaultQuantity, product.defaultUnit)
        assertTrue(calculation.nutrients.complete)
        assertEquals(product.nutrients.calories!!, calculation.nutrients.calories!!, .0001)
        assertTrue(catalog.search("", "product", barcode = "0000000000000").entries.isEmpty())
        val plan = plans.plans.value.single()
        assertEquals(6, diary.getAllMeals().first().size)
        val id = plans.eat(plan.id, plan.meals.single().id, 1.0)
        assertEquals(id, plans.eat(plan.id, plan.meals.single().id, .5))
        assertEquals(7, diary.getAllMeals().first().size)
        assertEquals(1.0, requireNotNull(diary.getMeal(id)).details.portion, 0.0)
    }

    @Test fun resetRestoresOnlyDemoDataAndPreservesAppearancePreference() = runBlocking {
        val appearance=com.tamaqai.data.local.AppPreferences(context)
        appearance.setTheme(com.tamaqai.data.local.ThemeMode.Dark)
        controller.initialize()
        controller.start()
        val deleted = diary.getAllMeals().first().first().uid
        diary.delete(deleted)
        account.save(account.localProfile().copy(name = "Changed demo"))
        controller.reset()
        assertFalse(controller.started.value)
        assertEquals(6, diary.getAllMeals().first().size)
        assertNotNull(diary.getMeal(deleted))
        assertEquals("Demo", account.localProfile().name)
        assertEquals(2100, settings.onDate(LocalDate.now(clock)).calories)
        assertEquals(com.tamaqai.data.local.ThemeMode.Dark,appearance.theme.value)
        assertEquals(com.tamaqai.data.local.ThemeMode.Dark,com.tamaqai.data.local.AppPreferences(context).theme.value)
        // Actual AppCompat locale preservation is exercised by StandaloneDemoUiTest.
        controller.start()
        newController(clock).initialize()
        assertEquals(6, diary.getAllMeals().first().size)
    }

    @Test fun compositionRootNeverConstructsServerRepositoriesOrSessions() {
        val app=ApplicationProvider.getApplicationContext<com.tamaqai.TamaqApplication>()
        val local=app.container
        assertNull(local.remoteSessions)
        assertNotNull(local.standalone)
        assertEquals("standalone",local.owner)
        assertTrue(local.account is LocalAccountStore)
        assertTrue(local.nutrition is LocalNutritionStore)
        assertTrue(local.plans is LocalPlanStore)
        assertTrue(local.catalog is LocalCatalogStore)
    }

    @Test fun mergedApplicationHasNoInternetPermissionOrServerUrl() {
        @Suppress("DEPRECATION")
        val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse(permissions.contains("android.permission.INTERNET"))
        assertEquals("", com.tamaqai.BuildConfig.BACKEND_URL)
        assertEquals("com.tamaqai.standalone", context.packageName)
    }
}
