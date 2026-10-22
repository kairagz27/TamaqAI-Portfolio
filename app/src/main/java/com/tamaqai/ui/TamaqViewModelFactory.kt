package com.tamaqai.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.tamaqai.data.AppContainer
import com.tamaqai.ui.screens.HomeViewModel
import com.tamaqai.ui.screens.ScannerViewModel
import com.tamaqai.ui.screens.MealEditorViewModel
import com.tamaqai.ui.screens.DailyViewModel

class TamaqViewModelFactory(
    private val container: AppContainer,
    private val mealUid: String? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(DailyViewModel::class.java) ->
            DailyViewModel(container.mealRepository, container.dailyRepository, container.settings, container.photos, container.ready) as T
        modelClass.isAssignableFrom(HomeViewModel::class.java) ->
            HomeViewModel(container.mealRepository, container.ready, container::text) as T

        modelClass.isAssignableFrom(ScannerViewModel::class.java) ->
            ScannerViewModel(container.mealRepository, container.photos, container.ready, container::text) as T

        modelClass.isAssignableFrom(MealEditorViewModel::class.java) ->
            MealEditorViewModel(requireNotNull(mealUid), container.mealRepository, container.photos, container.analyzeMealPhotoUseCase, container.ready, text = container::text) as T

        else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
