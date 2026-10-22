package com.tamaqai.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class MealComponent(
    val name: String,
    val massGrams: Double? = null,
    val calories: Double,
    val protein: Double,
    val fat: Double,
    val carbs: Double,
    /** Null inherits the whole-meal portion. An override never alters the original estimate. */
    val consumedPortion: Double? = null,
    val massReference: ComponentMassReference? = null,
)

/** Unrounded baseline for repeated local corrections of the estimated mass. */
@Serializable
data class ComponentMassReference(val grams: Double, val calories: Double, val protein: Double, val fat: Double, val carbs: Double)

enum class MealStatus { Draft, PendingUpload, Analyzing, NeedsReview, Confirmed, Failed }
enum class NutritionSource { Manual, AiEstimate, Demo, LegacyUnknown, Catalog }

@Serializable
data class MealDetails(
    val components: List<MealComponent>,
    val portion: Double = 1.0,
    val photoName: String? = null,
    val analysisId: String? = null,
    val schemaVersion: String? = null,
    val promptVersion: String? = null,
    val model: String? = null,
    val explanation: String? = null,
    val question: String? = null,
    val userEdited: Boolean = false,
    /** Frozen catalogue receipt. Never resolved again when reading historical meals. */
    val catalogSnapshot: String? = null,
    val catalogSourceLabel: String? = null,
    /** Unknown published nutrients require explicit manual values before confirmation. */
    val catalogPendingManual: Boolean = false,
    val catalogPendingIndex: Int? = null,
)
