package com.tamaqai.data

import androidx.room.Room
import androidx.room.withTransaction
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tamaqai.data.local.AppDatabase
import com.tamaqai.data.local.Migrations
import com.tamaqai.data.local.UserSettings
import com.tamaqai.data.repository.MealRepositoryImpl
import com.tamaqai.data.repository.PlanningOwnerTransfer
import com.tamaqai.data.repository.RoomPlanningRepository
import com.tamaqai.domain.model.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** Isolated in-memory/named test databases only. No app diary, profile or server is cleared. */
@RunWith(AndroidJUnit4::class)
class MealPlanningPersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val clock = Clock.fixed(Instant.parse("2026-10-29T10:00:00Z"), ZoneOffset.UTC)
    private val date = LocalDate.now(clock).toString()

    @Suppress("DEPRECATION")
    @get:Rule val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java.canonicalName, FrameworkSQLiteOpenHelperFactory())

    private fun database() = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private suspend fun <T> useDatabase(action: suspend (AppDatabase) -> T): T {
        val database = database()
        try { return action(database) } finally { database.close() }
    }
    private fun repository(db: AppDatabase, owner: String = "test-owner", check: () -> Unit = {}) =
        RoomPlanningRepository(db, owner, MealRepositoryImpl(db.mealDao(), owner), clock, check)

    @Test fun migrationFromFivePreservesDiaryFavoritesWeightsCatalogAndSettings() {
        val name = "planning-migration-" + UUID.randomUUID()
        val owner = "migration-owner-" + UUID.randomUUID()
        val prefs = context.getSharedPreferences("daily_settings_$owner", 0)
        try {
            UserSettings(context, owner).save(DailyGoals(2200, 110, 75, 270), LocalDate.now(clock), "manual")
            migrations.createDatabase(name, 5).apply {
                execSQL("INSERT INTO meals(id,name,calories,protein,fat,carbs,timestamp,uid,status,zoneId,source,detailsJson,originalUid,mealType,owner) VALUES(42,'Old meal',350,20,10,45,0,'old-diary-id','Confirmed','UTC','Manual',NULL,NULL,'Breakfast',?)", arrayOf(owner))
                execSQL("INSERT INTO favorite_meals(id,name,calories,protein,fat,carbs,detailsJson,mealType,source,owner) VALUES('old-favorite','Old favorite',120,5,4,16,'{}','Lunch','Manual',?)", arrayOf(owner))
                execSQL("INSERT INTO weight_entries(id,date,kilograms,owner) VALUES('old-weight','2026-10-28',72.5,?)", arrayOf(owner))
                execSQL("INSERT INTO catalog_cache(owner,versionId,entryJson,lastUsed,favorite) VALUES(?,'old-product','{}',100,1)", arrayOf(owner))
                execSQL("INSERT INTO local_links(operation,owner) VALUES('old-link',?)", arrayOf(owner))
                close()
            }
            migrations.runMigrationsAndValidate(name, 6, true, Migrations.FROM_5_TO_6).apply {
                query("SELECT id,name,calories,owner FROM meals WHERE uid='old-diary-id'").use {
                    assertTrue(it.moveToFirst()); assertEquals(42L, it.getLong(0)); assertEquals("Old meal", it.getString(1))
                    assertEquals(350, it.getInt(2)); assertEquals(owner, it.getString(3))
                }
                query("SELECT name,owner FROM favorite_meals WHERE id='old-favorite'").use { assertTrue(it.moveToFirst()); assertEquals("Old favorite", it.getString(0)); assertEquals(owner, it.getString(1)) }
                query("SELECT kilograms,owner FROM weight_entries WHERE id='old-weight'").use { assertTrue(it.moveToFirst()); assertEquals(72.5, it.getDouble(0), 0.0); assertEquals(owner, it.getString(1)) }
                query("SELECT favorite,owner FROM catalog_cache WHERE versionId='old-product'").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)); assertEquals(owner, it.getString(1)) }
                query("SELECT owner FROM local_links WHERE operation='old-link'").use { assertTrue(it.moveToFirst()); assertEquals(owner, it.getString(0)) }
                for (table in listOf("planning_workspaces", "shopping_documents")) query("SELECT COUNT(*) FROM $table").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
                close()
            }
            val restored = UserSettings(context, owner)
            assertEquals(DailyGoals(2200, 110, 75, 270), restored.onDate(LocalDate.now(clock)))
            assertEquals("manual", restored.originOn(LocalDate.now(clock)))
        } finally {
            context.deleteDatabase(name)
            assertTrue(prefs.edit().clear().commit())
        }
    }

    @Test fun threeDayInitializationAndDayCopyNeverWriteTheDiary() = runBlocking {
        useDatabase { db ->
            val repo = repository(db); repo.initialize()
            val initial = requireNotNull(repo.workspace.first())
            assertEquals(date, initial.startDate); assertEquals(3, initial.dayCount)
            val recipe = repo.recipes.first { it.nutrients.complete }
            val id = repo.addRecipe(date, MealSlot.Breakfast, recipe.id, 1.5)
            repo.copyDay(date, initial.dates[1])
            val workspace = requireNotNull(repo.workspace.first())
            assertEquals(2, workspace.entries.size)
            assertEquals(2, workspace.entries.map { it.id }.distinct().size)
            assertEquals(1.5, workspace.entries.single { it.id != id }.servings, 0.0)
            assertEquals(workspace.totalsForDate(date), workspace.totalsForDate(initial.dates[1]))
            assertTrue(MealRepositoryImpl(db.mealDao(), "test-owner").getAllMeals().first().isEmpty())
            repo.initialize()
            assertEquals(workspace, repo.workspace.first())
        }
    }

    @Test fun recreationAndShoppingRefreshRetainManualNotesAndRequestReviewOnIncrease() = runBlocking {
        useDatabase { db ->
            val repo = repository(db); repo.initialize()
            val recipe = repo.recipes.first { it.nutrients.complete }
            val entry = repo.addRecipe(date, MealSlot.Lunch, recipe.id, 1.0)
            val manualKey = repo.addManual("Reusable lunch box", 1.0, RecipeUnit.Piece, ShoppingCategory.Other, "Keep this manual note")
            // Adding a manual row before the first explicit refresh must not omit automatic ingredients.
            assertTrue(requireNotNull(repo.shopping.first()).rows.any { !it.manual })
            repo.refreshShopping()
            val row = requireNotNull(repo.shopping.first()).rows.first { !it.manual && it.quantity != null }
            repo.markRow(row.key, ShoppingStatus.Bought); repo.noteRow(row.key, "Pick the same package")
            repo.changeServings(entry, 2.0)
            val restored = repository(db)
            assertTrue(requireNotNull(restored.shopping.first()).isStale(requireNotNull(restored.workspace.first())))
            restored.refreshShopping()
            val updated = requireNotNull(restored.shopping.first())
            val marked = updated.rows.single { it.key == row.key }
            assertEquals(ShoppingStatus.Bought, marked.status); assertTrue(marked.needsReview)
            assertEquals("Pick the same package", marked.note)
            val manual = updated.rows.single { it.key == manualKey }
            assertTrue(manual.manual); assertEquals("Keep this manual note", manual.note)
            restored.markRow(marked.key, marked.status)
            assertFalse(requireNotNull(restored.shopping.first()).rows.single { it.key == row.key }.needsReview)
            restored.refreshShopping()
            assertFalse(requireNotNull(restored.shopping.first()).rows.single { it.key == row.key }.needsReview)
        }
    }

    @Test fun separateOwnersAndStaleSessionCannotAccessOrMutateAnotherWorkspace() = runBlocking {
        useDatabase { db ->
            var active = "account-a"
            val a = repository(db, "account-a") { check(active == "account-a") { "session_changed" } }
            val b = repository(db, "account-b") { check(active == "account-b") { "session_changed" } }
            a.initialize(); a.addRecipe(date, MealSlot.Lunch, a.recipes.first().id, 1.0)
            val before = a.workspace.first()
            active = "account-b"; b.initialize()
            assertTrue(requireNotNull(b.workspace.first()).entries.isEmpty())
            assertNull(b.shopping.first())
            try { a.deleteEntry(requireNotNull(before).entries.single().id); fail("Stale owner write must fail") } catch (_: IllegalStateException) { }
            try { a.workspace.first(); fail("Stale owner read must fail") } catch (_: IllegalStateException) { }
            active = "account-a"
            assertEquals(before, a.workspace.first())
        }
    }

    @Test fun concurrentConfirmationAcrossInstancesCreatesOneImmutableDiaryEntry() = runBlocking {
        useDatabase { db ->
            val a = repository(db); val b = repository(db); a.initialize()
            val recipe = a.recipes.first { it.nutrients.complete }
            val entry = a.addRecipe(date, MealSlot.Dinner, recipe.id, 2.0)
            val ids = coroutineScope {
                val first = async { a.confirmEaten(entry, 1.0) }
                val second = async { b.confirmEaten(entry, .5) }
                listOf(first.await(), second.await())
            }
            assertEquals(1, ids.distinct().size)
            val diary = MealRepositoryImpl(db.mealDao(), "test-owner")
            val saved = diary.getAllMeals().first().single()
            assertEquals(ids.first(), saved.uid)
            assertEquals(saved.uid, b.confirmEaten(entry, 20.0))
            assertEquals(saved, diary.getMeal(saved.uid))
            assertEquals(1, diary.getAllMeals().first().size)
        }
    }

    @Test fun diaryConfirmationStoresTheSelectedRecipeLanguageWithoutChangingTheRecipeSnapshot() = runBlocking {
        useDatabase { db ->
            val diary = MealRepositoryImpl(db.mealDao(), "test-owner")
            val repo = RoomPlanningRepository(db, "test-owner", diary, clock, language = { "kk" })
            repo.initialize()
            val recipe = repo.recipes.first { it.nutrients.complete }
            val id = repo.addRecipe(date, MealSlot.Lunch, recipe.id, 1.0)
            val uid = repo.confirmEaten(id, 1.0)
            val saved = requireNotNull(diary.getMeal(uid))
            assertEquals(recipe.displayName("kk"), saved.nutrition.name)
            assertEquals(recipe.ingredients.map { it.displayName("kk") }, saved.details.components.map { it.name })
            assertEquals(recipe, requireNotNull(repo.workspace.first()).entries.single().recipe)
        }
    }

    @Test fun unknownNutritionAndInvalidQuantityNeverBecomeZeroDiaryRows() = runBlocking {
        useDatabase { db ->
            val repo = repository(db); repo.initialize()
            val incomplete = repo.recipes.first { !it.nutrients.complete }
            val entry = repo.addRecipe(date, MealSlot.Lunch, incomplete.id, 1.0)
            assertFalse(requireNotNull(repo.workspace.first()).totalsForDate(date).complete)
            try { repo.confirmEaten(entry, 1.0); fail("Unknown nutrition cannot be confirmed as zero") }
            catch (error: IllegalArgumentException) { assertEquals("planning_unknown_nutrition", error.message) }
            val before = repo.workspace.first()
            try { repo.changeServings(entry, Double.NaN); fail("NaN must be rejected") } catch (_: IllegalArgumentException) { }
            assertEquals(before, repo.workspace.first())
            val key = repo.addManual("Quantity to check", null, RecipeUnit.Unknown, ShoppingCategory.Other, "Ask before buying")
            val row = requireNotNull(repo.shopping.first()).rows.single { it.key == key }
            assertNull(row.quantity); assertTrue(row.unknownQuantity)
            assertTrue(MealRepositoryImpl(db.mealDao(), "test-owner").getAllMeals().first().isEmpty())
        }
    }

    @Test fun newerServerSnapshotCannotOverwriteLocalPortionsOrReimportDeletedEntries() = runBlocking {
        useDatabase { db ->
            val repo = repository(db); repo.initialize()
            val first = MealPlan(id = "server-plan", version = 1, date = LocalDate.parse(date), meals = listOf(
                PlannedMeal(id = "server-meal", dish = "Imported rice", ingredients = listOf(
                    PlanIngredient(id = "server-rice", name = "Rice", quantity = 100.0, nutrition = PlanNutrients(120.0, 3.0, 1.0, 24.0))))))
            assertEquals(1, repo.importDailyPlans(listOf(first)))
            val imported = requireNotNull(repo.workspace.first()).entries.single()
            repo.changeServings(imported.id, 3.0)
            val next = first.copy(version = 2, meals = listOf(first.meals.single().copy(dish = "Server changed meal")))
            assertEquals(0, repo.importDailyPlans(listOf(next)))
            val restored = repository(db)
            assertEquals(3.0, requireNotNull(restored.workspace.first()).entries.single().servings, 0.0)
            assertEquals("Imported rice", requireNotNull(restored.workspace.first()).entries.single().recipe.name)
            assertEquals(1, first.version)
            assertEquals("Imported rice", first.meals.single().dish)
            assertEquals(100.0, first.meals.single().ingredients.single().quantity, 0.0)
            repo.deleteEntry(imported.id)
            assertEquals(0, repo.importDailyPlans(listOf(next)))
            assertTrue(requireNotNull(repo.workspace.first()).entries.isEmpty())
        }
    }

    @Test fun explicitGuestTransferPreservesPlannerAndNotesOverOnlyPristineTargetMetadata() = runBlocking {
        useDatabase { db ->
            val guest = repository(db, "guest"); val target = repository(db, "linked-account")
            guest.initialize(); target.initialize(); target.refreshShopping()
            guest.addRecipe(date, MealSlot.Lunch, guest.recipes.first().id, 1.0)
            guest.refreshShopping()
            val manual = guest.addManual("Transfer fixture", null, RecipeUnit.Unknown, ShoppingCategory.Other, "Retain guest note")
            guest.markRow(manual, ShoppingStatus.AtHome)
            val oldWorkspace = guest.workspace.first(); val oldShopping = guest.shopping.first()
            PlanningOwnerTransfer.validate(db, "guest", "linked-account")
            PlanningOwnerTransfer.transfer(db, "guest", "linked-account")
            assertNull(guest.workspace.first()); assertNull(guest.shopping.first())
            assertEquals(oldWorkspace, target.workspace.first()); assertEquals(oldShopping, target.shopping.first())
            // Replaying after the surrounding account linkage receipt is durable does not clear moved data.
            PlanningOwnerTransfer.transfer(db, "guest", "linked-account")
            assertEquals(oldWorkspace, target.workspace.first()); assertEquals(oldShopping, target.shopping.first())
        }
    }

    @Test fun guestTransferRejectsExistingAccountNotesOrPeriodEditsWithoutDeletingEitherOwner() = runBlocking {
        useDatabase { db ->
            val guest = repository(db, "guest"); val noted = repository(db, "noted-account"); val edited = repository(db, "edited-account")
            guest.initialize(); guest.addRecipe(date, MealSlot.Lunch, guest.recipes.first().id, 1.0)
            noted.initialize(); noted.addManual("Existing account item", 1.0, RecipeUnit.Piece, ShoppingCategory.Other, "Important existing note")
            edited.initialize(); edited.setPeriod(date, 7)
            val beforeGuest = guest.workspace.first(); val beforeNoted = noted.shopping.first(); val beforeEdited = edited.workspace.first()
            for (owner in listOf("noted-account", "edited-account")) {
                try { PlanningOwnerTransfer.validate(db, "guest", owner); fail("Account edits must not be merged implicitly") }
                catch (error: IllegalStateException) { assertEquals("planning_link_conflict", error.message) }
                try { PlanningOwnerTransfer.transfer(db, "guest", owner); fail("Conflict must prevent deletion") }
                catch (error: IllegalStateException) { assertEquals("planning_link_conflict", error.message) }
            }
            assertEquals(beforeGuest, guest.workspace.first())
            assertEquals(beforeNoted, noted.shopping.first()); assertEquals(beforeEdited, edited.workspace.first())
        }
    }

    @Test fun transferWithNoGuestRowsLeavesExistingAccountAndRejectsUnknownDocumentFieldsWhenGuestExists() = runBlocking {
        useDatabase { db ->
            val target = repository(db, "existing-account"); target.initialize()
            target.addManual("Account item", 1.0, RecipeUnit.Piece, ShoppingCategory.Other, "Do not overwrite")
            val before = target.shopping.first()
            PlanningOwnerTransfer.validate(db, "guest", "existing-account")
            PlanningOwnerTransfer.transfer(db, "guest", "existing-account")
            assertEquals(before, target.shopping.first())
            val guest = repository(db, "guest"); guest.initialize()
            val pristine = repository(db, "future-account"); pristine.initialize()
            val entity = requireNotNull(db.planningDao().workspace("future-account", "default"))
            db.planningDao().saveWorkspace(entity.copy(documentJson = entity.documentJson.dropLast(1) + ",\"futureUserNotes\":\"must survive\"}"))
            try { PlanningOwnerTransfer.transfer(db, "guest", "future-account"); fail("Unknown fields must fail closed") }
            catch (error: IllegalStateException) { assertEquals("planning_link_conflict", error.message) }
            assertEquals(entity.documentJson.dropLast(1) + ",\"futureUserNotes\":\"must survive\"}", db.planningDao().workspace("future-account", "default")!!.documentJson)
            assertNotNull(guest.workspace.first())
        }
    }

    @Test fun alreadyConfirmedGuestPlanKeepsOneDiaryIdentityAfterExplicitOwnershipTransfer() = runBlocking {
        useDatabase { db ->
            val guest = repository(db, "guest"); val target = repository(db, "linked-account")
            guest.initialize(); target.initialize()
            val recipe = guest.recipes.first { it.nutrients.complete }
            val entry = guest.addRecipe(date, MealSlot.Dinner, recipe.id, 2.0)
            val uid = guest.confirmEaten(entry, 1.0)
            val before = requireNotNull(MealRepositoryImpl(db.mealDao(), "guest").getMeal(uid))
            // Mirrors only the local transaction after both session proofs have been checked by AccountRepository.
            db.withTransaction {
                PlanningOwnerTransfer.transfer(db, "guest", "linked-account")
                db.openHelper.writableDatabase.execSQL("UPDATE meals SET owner=? WHERE owner='guest'", arrayOf("linked-account"))
            }
            assertEquals(uid, target.confirmEaten(entry, .5))
            val moved = MealRepositoryImpl(db.mealDao(), "linked-account")
            assertEquals(before, moved.getMeal(uid))
            assertEquals(1, moved.getAllMeals().first().size)
            assertTrue(MealRepositoryImpl(db.mealDao(), "guest").getAllMeals().first().isEmpty())
        }
    }
}
