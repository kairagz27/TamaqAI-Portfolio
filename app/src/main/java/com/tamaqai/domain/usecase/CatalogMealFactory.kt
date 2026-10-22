package com.tamaqai.domain.usecase

import com.tamaqai.domain.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Convert an explicitly selected version to a diary draft; history never dereferences live catalogue data. */
object CatalogMealFactory {
    /** A candidate for rice replaces rice only; all unmatched recognition estimates stay editable. */
    fun replaceComponent(original: Meal, index: Int, selection: CatalogSelection): Meal {
        require(index in original.details.components.indices)
        val chosen=create(selection)
        val complete=selection.calculation!=null
        require(complete || !original.details.catalogPendingManual || original.details.catalogPendingIndex==index)
        val pendingOther=original.details.catalogPendingManual && original.details.catalogPendingIndex!=index
        val components=original.details.components.toMutableList()
        if(complete) components[index]=chosen.details.components.single().copy(consumedPortion=1.0)
        val prior=original.details.catalogSnapshot?.let {runCatching {Json.parseToJsonElement(it).jsonObject}.getOrNull()}
        val receipts=prior?.get("component_selections")?.jsonArray?.filterNot {
            it.jsonObject["component_index"]?.jsonPrimitive?.intOrNull==index
        }?.toMutableList() ?: mutableListOf()
        receipts.add(buildJsonObject {put("component_index",index);put("receipt",Json.parseToJsonElement(requireNotNull(chosen.details.catalogSnapshot)))
            put("label","${selection.entry.name}:\n${chosen.details.catalogSourceLabel}")})
        val details=original.details.copy(components=components,catalogSnapshot=buildJsonObject {put("component_selections",JsonArray(receipts))}.toString(),
            catalogSourceLabel=receipts.mapNotNull {it.jsonObject["label"]?.jsonPrimitive?.contentOrNull}.joinToString("\n"),
            catalogPendingManual=!complete || pendingOther,
            catalogPendingIndex=if(!complete) index else if(pendingOther) original.details.catalogPendingIndex else null,userEdited=true)
        val nutrition=if(complete) NutritionCalculator().calculate(original.nutrition.name,components,details.portion) else original.nutrition
        return original.copy(nutrition=nutrition,details=details,
            source=if(components.size==1 && complete) NutritionSource.Catalog else NutritionSource.AiEstimate)
    }
    fun create(selection: CatalogSelection, timestamp: Long = System.currentTimeMillis(), original: Meal? = null): Meal {
        val entry=selection.entry
        val receipt=selection.calculation
        val components=receipt?.let { calculation ->
            require(calculation.nutrients.complete)
            listOf(MealComponent(entry.name,calculation.massGrams,
                requireNotNull(calculation.nutrients.calories),requireNotNull(calculation.nutrients.protein),
                requireNotNull(calculation.nutrients.fat),requireNotNull(calculation.nutrients.carbs)))
        } ?: emptyList()
        val snapshot=receipt?.snapshot ?: buildJsonObject {
            put("entry",Json.encodeToJsonElement(entry))
            put("quantity",selection.quantity);put("unit",selection.unit)
            put("manual_values_required",true)
        }
        val details=(original?.details ?: MealDetails(emptyList())).copy(components=components,portion=1.0,
            catalogSnapshot=snapshot.toString(),catalogSourceLabel="${entry.source.url}\n${entry.source.checkedAt} · ${entry.source.license}",
            catalogPendingManual=receipt==null,catalogPendingIndex=null,userEdited=receipt==null,
            // A chosen catalogue version supersedes the recognition estimate, not its analysis id/provenance.
            explanation=null,question=null)
        val nutrition=if(components.isEmpty()) Nutrition(entry.name,0,0,0,0)
            else NutritionCalculator().calculate(entry.name,components,1.0)
        return (original ?: Meal(nutrition=nutrition,timestamp=timestamp,details=details)).copy(
            nutrition=nutrition,status=MealStatus.NeedsReview,
            source=if(receipt==null) NutritionSource.Manual else NutritionSource.Catalog,details=details)
    }
}
