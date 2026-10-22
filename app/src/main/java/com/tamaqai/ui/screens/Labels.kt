package com.tamaqai.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.tamaqai.R
import com.tamaqai.domain.model.*

@Composable fun MealStatus.label() = stringResource(when (this) {
    MealStatus.Draft -> R.string.status_draft
    MealStatus.PendingUpload -> R.string.status_pending
    MealStatus.Analyzing -> R.string.status_analyzing
    MealStatus.NeedsReview -> R.string.status_review
    MealStatus.Confirmed -> R.string.status_confirmed
    MealStatus.Failed -> R.string.status_failed
})
@Composable fun NutritionSource.label() = stringResource(when (this) {
    NutritionSource.Manual -> R.string.manual_values
    NutritionSource.AiEstimate -> R.string.estimate_photo
    NutritionSource.Demo -> R.string.demo_values
    NutritionSource.LegacyUnknown -> R.string.legacy_values
    NutritionSource.Catalog -> R.string.catalog_values_label
})
@Composable fun MealType.label() = stringResource(when (this) {
    MealType.Breakfast -> R.string.breakfast
    MealType.Lunch -> R.string.lunch
    MealType.Dinner -> R.string.dinner
    MealType.Snack -> R.string.snack
    MealType.Other -> R.string.meal_other
})
