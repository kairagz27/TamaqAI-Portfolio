package com.tamaqai.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import java.math.BigDecimal

@Serializable
data class CatalogNutrients(
    val calories: Double? = null,
    val protein: Double? = null,
    val fat: Double? = null,
    val carbs: Double? = null,
) {
    val complete: Boolean get() = listOf(calories, protein, fat, carbs).all { it != null && it.isFinite() && it >= 0 }
}

@Serializable
data class CatalogSource(
    val url: String,
    @SerialName("checked_at") val checkedAt: String,
    @SerialName("external_id") val externalId: String,
    val license: String,
    val status: String,
    val namespace: String,
)

@Serializable
data class CatalogEntry(
    val id: String,
    @SerialName("version_id") val versionId: String,
    val revision: Int,
    val kind: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    val brand: String? = null,
    val barcode: String? = null,
    @SerialName("country_of_sale") val countryOfSale: List<String> = emptyList(),
    @SerialName("country_of_manufacture") val countryOfManufacture: List<String> = emptyList(),
    val city: String? = null,
    val chain: String? = null,
    @SerialName("branch_scope") val branchScope: String? = null,
    val variant: String? = null,
    val basis: String,
    @SerialName("portion_grams") val portionGrams: Double? = null,
    @SerialName("portion_ml") val portionMl: Double? = null,
    val nutrients: CatalogNutrients,
    val source: CatalogSource,
    @SerialName("addon_ids") val addonIds: List<String> = emptyList(),
) {
    val canCalculate: Boolean get() = basis != "unknown" && nutrients.complete
    val defaultUnit: String get() = when (basis) { "per_100g" -> "g"; "per_100ml" -> "ml"; else -> "portion" }
    val defaultQuantity: Double get() = when (basis) { "per_100g", "per_100ml" -> 100.0; else -> 1.0 }
}

@Serializable
data class CatalogAddon(
    @SerialName("version_id") val versionId: String,
    val quantity: Double = 1.0,
    val unit: String = "portion",
)

@Serializable
data class CatalogCalculation(
    val name: String,
    val nutrients: CatalogNutrients,
    @SerialName("mass_grams") val massGrams: Double? = null,
    @SerialName("volume_ml") val volumeMl: Double? = null,
    val snapshot: JsonObject,
)

data class CatalogSelection(
    val entry: CatalogEntry,
    val quantity: Double,
    val unit: String,
    val addons: List<CatalogAddon> = emptyList(),
    /** Null explicitly requests manual values. Unknown catalogue values are never zero. */
    val calculation: CatalogCalculation? = null,
)

data class CatalogRestaurant(val chain: String, val city: String?, val branchScope: String?)
data class CatalogResults(val entries: List<CatalogEntry>, val offline: Boolean = false)

/** Frozen, owner-scoped entries remain usable offline. Units never imply a density. */
object CachedCatalogCalculator {
    private val json = Json { encodeDefaults = true; explicitNulls = true }

    fun calculate(entry: CatalogEntry, quantity: Double, unit: String, addons: List<Triple<CatalogEntry, Double, String>> = emptyList()): CatalogCalculation {
        require(quantity.isFinite() && quantity > 0 && quantity <= 10000) { "catalog_quantity_basis" }
        require(entry.canCalculate) { "catalog_nutrition_missing" }
        require(addons.all { it.first.id in entry.addonIds }) { "catalog_addon_invalid" }
        require(addons.size <= 10 && addons.map { it.first.versionId }.distinct().size == addons.size) { "catalog_addon_invalid" }
        fun number(value: Double) = BigDecimal.valueOf(value)
        val amount = number(quantity)
        val hundred = BigDecimal(100)
        val factor = when (entry.basis) {
            "per_100g" -> when (unit) {
                "g" -> amount.divide(hundred)
                "portion" -> amount.multiply(number(requireNotNull(entry.portionGrams) { "catalog_quantity_basis" })).divide(hundred)
                else -> throw IllegalArgumentException("catalog_quantity_basis")
            }
            "per_100ml" -> when (unit) {
                "ml" -> amount.divide(hundred)
                "portion" -> amount.multiply(number(requireNotNull(entry.portionMl) { "catalog_quantity_basis" })).divide(hundred)
                else -> throw IllegalArgumentException("catalog_quantity_basis")
            }
            "per_portion" -> when (unit) {
                "portion" -> amount
                "g" -> amount.divide(number(requireNotNull(entry.portionGrams) { "catalog_quantity_basis" }), java.math.MathContext.DECIMAL128)
                "ml" -> amount.divide(number(requireNotNull(entry.portionMl) { "catalog_quantity_basis" }), java.math.MathContext.DECIMAL128)
                else -> throw IllegalArgumentException("catalog_quantity_basis")
            }
            else -> throw IllegalArgumentException("catalog_quantity_basis")
        }
        fun scaled(value: Double?) = number(requireNotNull(value)).multiply(factor)
        val extras = addons.map { (extra, count, extraUnit) -> calculate(extra, count, extraUnit) }
        fun total(value: Double?, other: (CatalogNutrients) -> Double?) =
            extras.fold(scaled(value)) { result, extra -> result + number(requireNotNull(other(extra.nutrients))) }.toDouble()
        val nutrients = CatalogNutrients(total(entry.nutrients.calories) { it.calories }, total(entry.nutrients.protein) { it.protein },
            total(entry.nutrients.fat) { it.fat }, total(entry.nutrients.carbs) { it.carbs })
        val mass = when (entry.basis) {
            "per_100g" -> factor.multiply(hundred).toDouble()
            "per_100ml" -> if (entry.portionGrams != null && entry.portionMl != null)
                factor.multiply(hundred).multiply(number(entry.portionGrams)).divide(number(entry.portionMl), java.math.MathContext.DECIMAL128).toDouble() else null
            "per_portion" -> entry.portionGrams?.let { number(it).multiply(factor).toDouble() }
            else -> null
        }
        val volume = when (entry.basis) {
            "per_100ml" -> factor.multiply(hundred).toDouble()
            "per_portion" -> entry.portionMl?.let { number(it).multiply(factor).toDouble() }
            else -> null
        }
        // Mixed addons with unknown mass cannot produce a fabricated total mass.
        val totalMass = mass?.takeIf { extras.all { it.massGrams != null } }?.let { it + extras.sumOf { extra -> requireNotNull(extra.massGrams) } }
        val totalVolume = volume?.takeIf { extras.all { it.volumeMl != null } }?.let { it + extras.sumOf { extra -> requireNotNull(extra.volumeMl) } }
        require(requireNotNull(nutrients.calories) <= 10000 && listOf(nutrients.protein, nutrients.fat, nutrients.carbs).all { requireNotNull(it) <= 1000 } &&
            (totalMass == null || totalMass <= 10000)) { "catalog_quantity_too_large" }
        val snapshot = kotlinx.serialization.json.buildJsonObject {
            put("entry", json.encodeToJsonElement(entry))
            put("quantity", json.encodeToJsonElement(quantity))
            put("unit", json.encodeToJsonElement(unit))
            put("addons", kotlinx.serialization.json.JsonArray(extras.map { it.snapshot }))
            put("nutrients", json.encodeToJsonElement(nutrients))
            put("mass_grams", json.encodeToJsonElement(totalMass))
            put("volume_ml", json.encodeToJsonElement(totalVolume))
            put("offline_cached", json.encodeToJsonElement(true))
        }
        return CatalogCalculation(entry.name, nutrients, totalMass, totalVolume, snapshot)
    }
}
