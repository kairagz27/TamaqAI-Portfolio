package com.tamaqai.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.tamaqai.R
import com.tamaqai.ui.theme.TextSecondary
import kotlinx.serialization.json.*
import java.math.BigDecimal

/** Read only the stored receipt. Historical UI never looks up a newer catalogue version. */
@Composable fun CatalogReceipt(raw:String) {
    val receipts=remember(raw) {runCatching {
        val document=Json.parseToJsonElement(raw).jsonObject
        val bases=document["component_selections"]?.jsonArray?.map {it.jsonObject.getValue("receipt").jsonObject} ?: listOf(document)
        bases.flatMap {base ->listOf(base)+(base["addons"] as? JsonArray)?.map {it.jsonObject}.orEmpty()}
    }.getOrDefault(emptyList())}
    if(receipts.isNotEmpty()) Text(stringResource(R.string.catalog_receipt_source),style=MaterialTheme.typography.labelLarge,color=TextSecondary)
    receipts.forEach {receipt ->
        val entry=receipt["entry"]?.jsonObject
        if(entry!=null) {
            val basis=entry["basis"]?.jsonPrimitive?.content
            val label=when(basis) {"per_100g"->R.string.catalog_per_100g;"per_100ml"->R.string.catalog_per_100ml
                "per_portion"->R.string.catalog_per_portion;else->R.string.catalog_basis_unknown}
            val quantity=receipt["quantity"]?.jsonPrimitive?.doubleOrNull
            val unit=when(receipt["unit"]?.jsonPrimitive?.content) {"g"->R.string.unit_grams;"ml"->R.string.unit_ml;else->R.string.catalog_quantity_portion}
            Text("${entry["name"]?.jsonPrimitive?.content.orEmpty()} · ${stringResource(label)}",style=MaterialTheme.typography.bodySmall,color=TextSecondary)
            if(quantity!=null) Text(stringResource(R.string.catalog_receipt_amount,BigDecimal.valueOf(quantity).stripTrailingZeros().toPlainString(),stringResource(unit)),
                style=MaterialTheme.typography.bodySmall,color=TextSecondary)
        }
    }
}
