package com.tamaqai.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tamaqai.R
import com.tamaqai.domain.model.*
import com.tamaqai.ui.components.*
import com.tamaqai.ui.theme.*
import java.time.LocalDate

@Composable
fun ShoppingListScreen(state: PlanningUiState, vm: PlanningViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val language = LocalConfiguration.current.locales[0].language
    var manual by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<ShoppingRow?>(null) }
    var deleting by remember { mutableStateOf<ShoppingRow?>(null) }
    val document = state.shopping
    val labels = ShoppingShareLabels(
        context.getString(R.string.mp_share_title), context.getString(R.string.mp_unknown_quantity),
        context.getString(R.string.mp_unknown_addition), context.getString(R.string.mp_review_required),
        context.getString(R.string.mp_inactive), context.getString(R.string.mp_note),
        context.getString(R.string.mp_used_for), context.getString(R.string.mp_servings),
        ShoppingCategory.entries.associateWith { context.getString(categoryResource(it)) },
        ShoppingStatus.entries.associateWith { context.getString(shoppingStatusResource(it)) },
        RecipeUnit.entries.associateWith { context.getString(recipeUnitResource(it)) },
        FoodPreparation.entries.associateWith { context.getString(preparationResource(it)) },
    )
    LazyColumn(modifier.fillMaxWidth().imePadding().testTag("shopping-list-screen"),
        contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 36.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item("shopping-header") {
            WellnessCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.mp_shopping_tab), style = MaterialTheme.typography.headlineSmall)
                state.workspace?.let { workspace ->
                    Text(dateLabel(LocalDate.parse(workspace.startDate)) + " – " +
                        dateLabel(LocalDate.parse(workspace.dates.last())), color = TextSecondary)
                }
                if (state.shoppingStale) {
                    Text(stringResource(R.string.mp_stale), Modifier.testTag("shopping-stale"), color = WarningColor)
                } else {
                    Text(stringResource(R.string.mp_list_current), Modifier.testTag("shopping-current"),
                        style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
                PrimaryButton(stringResource(if (document == null) R.string.mp_generate_list else R.string.mp_refresh_list),
                    vm::refreshShopping, Modifier.testTag("shopping-refresh"), enabled = !state.busy)
                WellnessOutlinedButton({ manual = true }, Modifier.fillMaxWidth().testTag("shopping-add-manual"), enabled = !state.busy) {
                    Text(stringResource(R.string.mp_manual_add))
                }
                WellnessOutlinedButton({
                    document?.let {
                        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, labels.title)
                            .putExtra(Intent.EXTRA_TEXT, shoppingShareText(it, language, labels))
                        context.startActivity(Intent.createChooser(intent, labels.title))
                    }
                }, Modifier.fillMaxWidth().testTag("shopping-share"),
                    enabled = !state.busy && !state.shoppingStale && document?.rows?.isNotEmpty() == true) {
                    Text(stringResource(R.string.mp_share))
                }
            }
        }
        if (document?.rows.isNullOrEmpty()) item("shopping-empty") {
            Text(stringResource(R.string.mp_list_empty), color = TextSecondary)
        }
        ShoppingCategory.entries.forEach { category ->
            val rows = document?.rows?.filter { it.category == category }?.sortedWith(
                compareBy<ShoppingRow> { !it.inPlan && !it.manual }.thenBy { it.displayName(language) }) ?: emptyList()
            if (rows.isNotEmpty()) {
                item("category-${category.name}") {
                    Text(stringResource(categoryResource(category)), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                }
                rows.forEach { row -> item("shopping-${row.key}") {
                    ShoppingRowCard(row, language, state.busy,
                        onMark = { vm.markRow(row.key, it) }, onNote = { note = row }, onDelete = { deleting = row })
                } }
            }
        }
    }
    if (manual) ManualShoppingDialog(state.busy, { manual = false }) { name, amount, unit, category, text ->
        vm.addManual(name, amount, unit, category, text); manual = false
    }
    note?.let { row -> ShoppingNoteDialog(row, language, state.busy, { note = null }) {
        vm.saveNote(row.key, it); note = null
    } }
    deleting?.let { row -> ConfirmDialog(R.string.mp_remove_manual, R.string.mp_remove_manual_hint, { deleting = null }) {
        vm.removeManual(row.key); deleting = null
    } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShoppingRowCard(row: ShoppingRow, language: String, busy: Boolean, onMark: (ShoppingStatus) -> Unit,
                            onNote: () -> Unit, onDelete: () -> Unit) {
    var uses by rememberSaveable(row.key) { mutableStateOf(false) }
    val name = row.displayName(language)
    val noteLabel = stringResource(R.string.mp_note_for, name)
    WellnessCard(Modifier.fillMaxWidth().testTag("shopping-row-${row.key}")) {
        Text(name, style = MaterialTheme.typography.titleLarge)
        if (!row.manual) Text(stringResource(preparationResource(row.preparation)), color = TextSecondary,
            style = MaterialTheme.typography.labelMedium)
        Text(planningAmount(row.quantity, row.unit), Modifier.testTag("shopping-quantity-${row.key}"),
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = BodyFont))
        if (row.quantity != null && row.unknownQuantity) Text(stringResource(R.string.mp_unknown_addition), color = WarningColor)
        if (row.manual) Text(stringResource(R.string.mp_manual), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        if (!row.inPlan && !row.manual) Text(stringResource(R.string.mp_inactive), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShoppingStatus.entries.forEach { status -> FilterChip(row.status == status, { onMark(status) },
                enabled = !busy, label = { Text(stringResource(shoppingStatusResource(status))) }, colors = wellnessChipColors(),
                modifier = Modifier.heightIn(min = 48.dp).testTag("shopping-status-${row.key}-${status.name}")) }
        }
        if (row.needsReview) {
            Text(stringResource(R.string.mp_review_required), Modifier.testTag("shopping-review-${row.key}"),
                color = WarningColor, style = MaterialTheme.typography.bodyMedium)
            WellnessOutlinedButton({ onMark(row.status) }, Modifier.fillMaxWidth().testTag("shopping-ack-${row.key}"), enabled = !busy) {
                Text(stringResource(R.string.mp_review_ack))
            }
        }
        if (row.uses.isNotEmpty()) {
            WellnessTextButton({ uses = !uses }, enabled = !busy) { Text(stringResource(R.string.mp_used_for)) }
            if (uses) row.uses.forEach { use ->
                Text(use.displayName(language) + " · " + dateLabel(LocalDate.parse(use.date)) + " · " +
                    stringResource(R.string.mp_servings_value, shareNumber(use.servings, language)), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
        if (row.note.isNotBlank()) Text(row.note, Modifier.testTag("shopping-note-${row.key}"), style = MaterialTheme.typography.bodyMedium)
        WellnessTextButton(onNote, Modifier.semantics { contentDescription = noteLabel }.testTag("shopping-edit-note-${row.key}"), enabled = !busy) {
            Text(stringResource(R.string.mp_note))
        }
        if (row.manual) WellnessTextButton(onDelete, Modifier.testTag("shopping-remove-${row.key}"), enabled = !busy) { Text(stringResource(R.string.delete)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ManualShoppingDialog(busy: Boolean, onDismiss: () -> Unit,
                                 onSave: (String, Double?, RecipeUnit, ShoppingCategory, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var quantity by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf(RecipeUnit.Piece) }
    var category by rememberSaveable { mutableStateOf(ShoppingCategory.Other) }
    val number = planningNumber(quantity)
    val quantityValid = quantity.isBlank() || number?.let { it > 0 && it <= 1_000_000_000 } == true
    val nameValid = name.isNotBlank() && name.trim().length <= 160
    WellnessFullScreenDialog(onDismiss) {
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp).testTag("shopping-manual-form"),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            BackButton(onDismiss)
            Text(stringResource(R.string.mp_manual_item), style = MaterialTheme.typography.headlineLarge)
            WellnessTextField(name, { name = it }, label = { Text(stringResource(R.string.name)) },
                modifier = Modifier.fillMaxWidth().testTag("shopping-manual-name"), enabled = !busy, singleLine = true,
                isError = name.isNotEmpty() && !nameValid, supportingText = { Text(stringResource(R.string.mp_name_hint)) }, colors = wellnessFieldColors())
            WellnessTextField(quantity, { quantity = it }, label = { Text(stringResource(R.string.mp_quantity)) },
                modifier = Modifier.fillMaxWidth().testTag("shopping-manual-quantity"), enabled = !busy, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = !quantityValid,
                supportingText = { Text(stringResource(if (quantityValid) R.string.mp_quantity_hint else R.string.mp_quantity_range)) }, colors = wellnessFieldColors())
            Text(stringResource(R.string.mp_unit), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RecipeUnit.entries.forEach { value -> FilterChip(unit == value, { unit = value }, label = { Text(stringResource(recipeUnitResource(value))) },
                    enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("shopping-unit-${value.name}"), colors = wellnessChipColors()) }
            }
            Text(stringResource(R.string.mp_category), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShoppingCategory.entries.forEach { value -> FilterChip(category == value, { category = value }, label = { Text(stringResource(categoryResource(value))) },
                    enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("shopping-category-${value.name}"), colors = wellnessChipColors()) }
            }
            WellnessTextField(note, { note = it }, label = { Text(stringResource(R.string.mp_note)) }, modifier = Modifier.fillMaxWidth().testTag("shopping-manual-note"),
                enabled = !busy, isError = note.length > 1000, supportingText = { Text("${note.length}/1000") }, colors = wellnessFieldColors())
            PrimaryButton(stringResource(R.string.save), { onSave(name.trim(), number, unit, category, note) }, Modifier.testTag("shopping-manual-save"),
                enabled = !busy && nameValid && quantityValid && note.length <= 1000)
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun ShoppingNoteDialog(row: ShoppingRow, language: String, busy: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var note by rememberSaveable(row.key) { mutableStateOf(row.note) }
    WellnessDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(stringResource(R.string.mp_note_for, row.displayName(language))) },
        text = { Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            WellnessTextField(note, { note = it }, label = { Text(stringResource(R.string.mp_note)) },
                modifier = Modifier.fillMaxWidth().testTag("shopping-note-input"), enabled = !busy, isError = note.length > 1000,
                supportingText = { Text("${note.length}/1000") }, colors = wellnessFieldColors())
        } },
        confirmButton = { WellnessTextButton({ onSave(note) }, Modifier.testTag("shopping-note-save"), enabled = !busy && note.length <= 1000) { Text(stringResource(R.string.save)) } },
        dismissButton = { WellnessTextButton(onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } })
}

internal data class ShoppingShareLabels(
    val title: String, val unknown: String, val partial: String, val review: String,
    val inactive: String, val note: String, val uses: String, val servings: String,
    val categories: Map<ShoppingCategory, String>, val statuses: Map<ShoppingStatus, String>,
    val units: Map<RecipeUnit, String>, val preparations: Map<FoodPreparation, String>,
)

/** Plain text only. The caller opens Android Sharesheet after an explicit tap. */
internal fun shoppingShareText(document: ShoppingDocument, language: String, labels: ShoppingShareLabels): String = buildString {
    appendLine(labels.title)
    ShoppingCategory.entries.forEach { category ->
        val rows = document.rows.filter { it.category == category }
        if (rows.isNotEmpty()) {
            appendLine(); appendLine(labels.categories.getValue(category))
            rows.forEach { row ->
                val mark = when (row.status) { ShoppingStatus.Needed -> "[ ]"; ShoppingStatus.Bought -> "[x]"; ShoppingStatus.AtHome -> "[~]" }
                append(mark).append(' ').append(row.displayName(language)).append(" — ")
                if (row.quantity == null) append(labels.unknown)
                else append(shareNumber(row.quantity, language)).append(' ').append(labels.units.getValue(row.unit))
                if (row.quantity != null && row.unknownQuantity) append(" + ").append(labels.partial)
                append(" (").append(labels.statuses.getValue(row.status))
                if (!row.manual) append("; ").append(labels.preparations.getValue(row.preparation))
                appendLine(")")
                if (row.needsReview) appendLine("  " + labels.review)
                if (!row.inPlan && !row.manual) appendLine("  " + labels.inactive)
                row.uses.forEach { use -> appendLine("  " + labels.uses + ": " + use.displayName(language) + " · " + use.date + " · " +
                    labels.servings + ": " + shareNumber(use.servings, language)) }
                if (row.note.isNotBlank()) appendLine("  " + labels.note + ": " + row.note)
            }
        }
    }
}.trim()

private fun shareNumber(value: Double, language: String): String =
    java.text.NumberFormat.getNumberInstance(java.util.Locale.forLanguageTag(language)).apply {
        maximumFractionDigits = 2
    }.format(value)
