package com.tamaqai.ui

import androidx.lifecycle.viewModelScope

import com.tamaqai.data.local.PhotoStore
import com.tamaqai.domain.model.*
import com.tamaqai.domain.repository.*
import com.tamaqai.domain.usecase.*
import com.tamaqai.ui.screens.MealEditorViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class MealEditorTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var repo: MemoryMeals
    private lateinit var draft: Meal
    private lateinit var photos: PhotoStore
    private lateinit var vm: MealEditorViewModel
    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        repo = MemoryMeals()
        draft = Meal(nutrition = Nutrition("Еда",120,5,4,16), details = MealDetails(listOf(MealComponent("Еда",100.0,120.0,5.0,4.0,16.0)), analysisId = "analysis-key"))
        repo.rows.value = listOf(draft)
        photos = PhotoStore(folder.newFolder())
        createVm(object : FoodAnalysisRepository {
            override suspend fun analyzePhoto(jpegBytes: ByteArray, analysisId: String, invite: String): AnalysisResult = error("must not call AI")
        })
    }
    private fun createVm(provider: FoodAnalysisRepository) {
        if (::vm.isInitialized) vm.viewModelScope.cancel()
        vm = MealEditorViewModel(draft.uid, repo, photos, AnalyzeMealPhotoUseCase(provider), ioDispatcher = StandardTestDispatcher())
    }
    @After fun teardown() { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    @Test fun rapidPortionEditsPersistLatestAndConfirmOnce() = runTest {
        runCurrent()
        vm.portion("2")
        vm.portion("1,5")
        runCurrent()
        assertEquals(180, repo.getMeal(draft.uid)!!.nutrition.calories)
        assertEquals(1.5, repo.getMeal(draft.uid)!!.details.portion, 0.0)
        vm.confirm(); vm.confirm(); runCurrent()
        assertEquals(1, repo.confirmations)
        assertEquals(MealStatus.Confirmed, repo.getMeal(draft.uid)!!.status)
    }
    @Test fun invalidInputDoesNotDiscardPendingLastValidDraft() = runTest {
        runCurrent()
        vm.portion("2")
        vm.portion("NaN")
        runCurrent()
        assertEquals(240, repo.getMeal(draft.uid)!!.nutrition.calories)
        assertNotNull(vm.state.value.error)
        vm.confirm(); runCurrent()
        assertEquals(0, repo.confirmations)
    }
    @Test fun confirmationWithoutEditsPreservesProvenance() = runTest {
        runCurrent()
        vm.confirm(); runCurrent()
        assertFalse(repo.getMeal(draft.uid)!!.details.userEdited)
    }
    @Test fun unknownRestaurantValuesStartBlankAndCannotBeConfirmedAsZero() = runTest {
        draft=draft.copy(details=MealDetails(emptyList(),catalogPendingManual=true,catalogSnapshot="{\"manual_values_required\":true}"))
        repo.rows.value=listOf(draft)
        createVm(object:FoodAnalysisRepository {
            override suspend fun analyzePhoto(jpegBytes:ByteArray,analysisId:String,invite:String):AnalysisResult=error("must not call AI")
        })
        runCurrent()
        val blank=vm.state.value.components.single()
        assertEquals("",blank.calories);assertEquals("",blank.protein)
        assertNull(vm.state.value.snapshot)
        vm.confirm();runCurrent();assertEquals(0,repo.confirmations)
        vm.component(0,blank.copy(calories="123",protein="5",fat="4",carbs="17"))
        runCurrent();vm.confirm();runCurrent()
        assertEquals(1,repo.confirmations)
        assertFalse(repo.getMeal(draft.uid)!!.details.catalogPendingManual)
        assertEquals(123,repo.getMeal(draft.uid)!!.nutrition.calories)
    }
    @Test fun cancelledAnalysisKeepsKeyAndRecoversFailedDraft() = runTest {
        draft = draft.copy(status = MealStatus.PendingUpload, details = draft.details.copy(photoName = photos.save(draft.uid, byteArrayOf(1))))
        repo.rows.value = listOf(draft)
        createVm(object : FoodAnalysisRepository {
            override suspend fun analyzePhoto(jpegBytes: ByteArray, analysisId: String, invite: String): AnalysisResult = awaitCancellation()
        })
        runCurrent()
        vm.analyze("invite")
        // Both UI and IO use the test scheduler, so cancellation has no wall-clock race.
        runCurrent()
        vm.cancelAnalysis()
        advanceUntilIdle()
        assertEquals(MealStatus.Failed, repo.getMeal(draft.uid)!!.status)
        assertEquals("analysis-key", repo.getMeal(draft.uid)!!.details.analysisId)
        assertFalse(vm.state.value.busy)
    }
    @Test fun doubleAnalysisAndOfflineFailureKeepPhotoAndStableKey() = runTest {
        draft = draft.copy(status = MealStatus.PendingUpload, details = draft.details.copy(photoName = photos.save(draft.uid, byteArrayOf(1))))
        repo.rows.value = listOf(draft)
        var calls = 0
        createVm(object : FoodAnalysisRepository {
            override suspend fun analyzePhoto(jpegBytes: ByteArray, analysisId: String, invite: String): AnalysisResult {
                calls++
                throw java.io.IOException("offline fixture")
            }
        })
        runCurrent()
        vm.analyze("invite"); vm.analyze("invite")
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals(MealStatus.Failed, repo.getMeal(draft.uid)!!.status)
        assertEquals("analysis-key", repo.getMeal(draft.uid)!!.details.analysisId)
        assertArrayEquals(byteArrayOf(1), photos.read(requireNotNull(draft.details.photoName)))
        assertEquals(0, repo.confirmations)
    }
    @Test fun backFlushesValidDraftAndDeletionRemovesIt() = runTest {
        runCurrent()
        vm.portion("2")
        vm.leave()
        advanceUntilIdle()
        assertEquals(240, repo.getMeal(draft.uid)!!.nutrition.calories)
        createVm(object : FoodAnalysisRepository {
            override suspend fun analyzePhoto(jpegBytes: ByteArray, analysisId: String, invite: String): AnalysisResult = error("must not call AI")
        })
        runCurrent()
        vm.delete()
        advanceUntilIdle()
        assertNull(repo.getMeal(draft.uid))
    }
    @Test fun componentFractionAndMassCorrectionsRemainSeparateAndSurviveReload() = runTest {
        runCurrent()
        vm.componentPortion(0,"0.5"); runCurrent()
        assertEquals(60,vm.state.value.snapshot!!.calories)
        vm.component(0,vm.state.value.components[0].copy(mass="200")); runCurrent()
        assertEquals(120,vm.state.value.snapshot!!.calories)
        assertEquals(240.0,repo.getMeal(draft.uid)!!.details.components[0].calories,0.0)
        repeat(10) {
            vm.component(0,vm.state.value.components[0].copy(mass="150"))
            vm.component(0,vm.state.value.components[0].copy(mass="200"))
        }
        vm.component(0,vm.state.value.components[0].copy(mass="bad")); runCurrent()
        assertNull(vm.state.value.snapshot)
        vm.component(0,vm.state.value.components[0].copy(mass="100")); runCurrent()
        assertEquals(60,vm.state.value.snapshot!!.calories)
        createVm(object : FoodAnalysisRepository {
            override suspend fun analyzePhoto(jpegBytes: ByteArray, analysisId: String, invite: String): AnalysisResult = error("must not call AI")
        }); runCurrent()
        assertEquals("0.5",vm.state.value.components[0].consumedPortion)
        vm.portion("1"); runCurrent()
        assertNull(vm.state.value.components[0].consumedPortion)
        assertEquals(120,vm.state.value.snapshot!!.calories)
    }
    private class MemoryMeals : MealRepository {
        val rows = MutableStateFlow<List<Meal>>(emptyList())
        var confirmations = 0
        override fun getAllMeals() = rows
        override suspend fun getMeal(uid: String) = rows.value.find { it.uid == uid }
        override suspend fun saveDraft(meal: Meal) { rows.value = rows.value.filterNot { it.uid == meal.uid } + meal }
        override suspend fun prepareEdit(uid: String) = uid
        override suspend fun confirm(meal: Meal) { confirmations++; saveDraft(meal.copy(status = MealStatus.Confirmed)) }
        override suspend fun delete(uid: String) { rows.value = rows.value.filterNot { it.uid == uid } }
        override suspend fun recoverInterruptedAnalyses() = Unit
    }
}
