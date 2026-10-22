package com.tamaqai.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tamaqai.R
import com.tamaqai.data.remote.ApiFailure
import com.tamaqai.data.remote.CatalogRepository
import com.tamaqai.domain.model.*
import com.tamaqai.ui.components.*
import com.tamaqai.ui.theme.TextSecondary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.math.BigDecimal

@Composable
fun CatalogScreen(
    repository: CatalogRepository,
    kind: String,
    initialQuery: String = "",
    initialVersionId: String? = null,
    initialBarcode: String? = null,
    onBack: () -> Unit,
    onSelected: (CatalogSelection) -> Unit,
    onScanBarcode: (() -> Unit)? = null,
    onManualEntry: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var query by rememberSaveable(repository, kind, initialQuery) { mutableStateOf(initialQuery) }
    var barcode by rememberSaveable(repository, initialBarcode) { mutableStateOf(initialBarcode) }
    var city by rememberSaveable(repository, kind) { mutableStateOf<String?>(null) }
    var chain by rememberSaveable(repository, kind) { mutableStateOf<String?>(null) }
    var branchScope by rememberSaveable(repository, kind) { mutableStateOf<String?>(null) }
    var cities by remember(repository) { mutableStateOf(emptyList<String>()) }
    var restaurants by remember(repository) { mutableStateOf(emptyList<CatalogRestaurant>()) }
    var results by remember(repository) { mutableStateOf(emptyList<CatalogEntry>()) }
    var recent by remember(repository) { mutableStateOf(emptyList<CatalogEntry>()) }
    var selected by remember(repository) { mutableStateOf<CatalogEntry?>(null) }
    var addons by remember(repository) { mutableStateOf(emptyList<CatalogEntry>()) }
    var selectedAddons by remember(repository) { mutableStateOf(emptySet<String>()) }
    var addonAmounts by remember(repository) { mutableStateOf(emptyMap<String, String>()) }
    var quantity by rememberSaveable(repository) { mutableStateOf("100") }
    var eaten by rememberSaveable(repository) { mutableStateOf("1.0") }
    var calculation by remember(repository) { mutableStateOf<CatalogCalculation?>(null) }
    var favorite by remember(repository) { mutableStateOf(false) }
    var busy by remember(repository) { mutableStateOf(false) }
    var calculating by remember(repository) { mutableStateOf(false) }
    var offline by remember(repository) { mutableStateOf(false) }
    var error by remember(repository) { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    fun fail(failure: Exception) {
        if (failure is CancellationException) throw failure
        error = if (failure is ApiFailure) failure.code else failure.message?.takeIf { it.startsWith("catalog_") } ?: "catalog_network"
    }
    fun select(entry: CatalogEntry) {
        selected = entry; quantity = catalogNumber(entry.defaultQuantity); eaten = "1.0"
        calculation = null; error = null; selectedAddons = emptySet(); addons = emptyList(); addonAmounts = emptyMap()
        scope.launch {
            try {
                repository.remember(entry)
                val isFavorite = repository.isFavorite(entry.versionId)
                val loadedAddons = entry.addonIds.map { repository.get(it, rememberSelection = true) }
                if (selected?.versionId == entry.versionId) {
                    favorite = isFavorite
                    addons = loadedAddons
                    addonAmounts = loadedAddons.associate { it.versionId to catalogNumber(it.defaultQuantity) }
                }
            } catch (failure: Exception) { fail(failure) }
        }
    }
    fun back() {
        when {
            selected != null && initialVersionId != null -> onBack()
            selected != null -> { selected = null; calculation = null; error = null }
            kind == "dish" && chain != null -> { chain = null; branchScope = null }
            kind == "dish" && city != null -> { city = null; branchScope = null }
            else -> onBack()
        }
    }
    BackHandler(onBack = ::back)

    LaunchedEffect(repository, initialVersionId) {
        recent = repository.recent().filter { it.kind == kind }
        if (initialVersionId != null) {
            busy = true
            try { select(repository.get(initialVersionId)) } catch (failure: Exception) { fail(failure) }
            finally { busy = false }
        }
    }
    LaunchedEffect(repository, kind, query, city, chain, branchScope, refresh, barcode) {
        if (selected != null) return@LaunchedEffect
        busy = true; error = null
        try {
            when {
                kind == "dish" && city == null -> cities = repository.cities()
                kind == "dish" && chain == null -> restaurants = repository.restaurants(requireNotNull(city))
                else -> {
                    delay(250)
                    val found = repository.search(query, kind, city, chain, barcode, branchScope)
                    results = found.entries; offline = found.offline
                }
            }
        } catch (failure: Exception) { fail(failure) }
        finally { busy = false }
    }

    LazyColumn(Modifier.fillMaxSize().imePadding().testTag("catalog_screen"),
        contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 36.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
                Text(stringResource(if (kind == "dish") R.string.catalog_restaurant_title else R.string.catalog_product_title),
                    style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            }
        }
        if (offline) item { Text(stringResource(R.string.catalog_offline), color = TextSecondary, style = MaterialTheme.typography.bodyMedium) }
        error?.let { code -> item {
            WellnessCard(Modifier.fillMaxWidth()) {
                Text(stringResource(catalogError(code)), color = MaterialTheme.colorScheme.error)
                WellnessTextButton({ refresh++ }) { Text(stringResource(R.string.retry)) }
            }
        } }
        if (busy || calculating) item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            WellnessLoadingIndicator(Modifier.size(24.dp)); Text(stringResource(R.string.catalog_loading))
        } }
        val entry = selected
        if (entry != null) {
            item {
                WellnessCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(entry.name, style = MaterialTheme.typography.headlineSmall)
                            listOfNotNull(entry.brand, entry.chain, entry.city, entry.variant).distinct().takeIf { it.isNotEmpty() }?.let {
                                Text(it.joinToString(" · "), color = TextSecondary)
                            }
                        }
                        IconButton({ scope.launch { try { repository.favorite(entry, !favorite); favorite = !favorite } catch (failure: Exception) { fail(failure) } } }) {
                            Icon(if (favorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                                stringResource(if (favorite) R.string.catalog_unfavorite else R.string.catalog_favorite))
                        }
                    }
                    CatalogProvenance(entry)
                    CatalogNutrientsView(entry.nutrients)
                    entry.portionGrams?.let { Text(stringResource(R.string.catalog_serving_mass, catalogNumber(it))) }
                    entry.portionMl?.let { Text(stringResource(R.string.catalog_serving_volume, catalogNumber(it))) }
                    entry.barcode?.let { Text(stringResource(R.string.catalog_barcode_value, it), style = MaterialTheme.typography.bodySmall) }
                    if (entry.countryOfSale.isNotEmpty()) Text(stringResource(R.string.catalog_sold_in, entry.countryOfSale.joinToString()), style = MaterialTheme.typography.bodySmall)
                    if (entry.countryOfManufacture.isNotEmpty()) Text(stringResource(R.string.catalog_made_in, entry.countryOfManufacture.joinToString()), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (entry.canCalculate) {
                item {
                    WellnessCard(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.catalog_amount_title), style = MaterialTheme.typography.titleLarge)
                        val parsed = quantity.replace(',', '.').toDoubleOrNull()
                        WellnessTextField(quantity, { quantity = it; calculation = null },
                            label = { Text(stringResource(catalogQuantityLabel(entry.defaultUnit))) },
                            modifier = Modifier.fillMaxWidth().testTag("catalog_quantity"), singleLine = true, enabled = !busy && !calculating,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            isError = parsed == null || !parsed.isFinite() || parsed <= 0 || parsed > 10000,
                            supportingText = { Text(stringResource(R.string.catalog_quantity_hint)) }, colors = wellnessFieldColors())
                        EatenPortionSelector(eaten, { eaten = it; calculation = null }, enabled = !busy && !calculating)
                    }
                }
                if (addons.isNotEmpty()) item {
                    WellnessCard(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.catalog_addons), style = MaterialTheme.typography.titleLarge)
                        addons.forEach { extra ->
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                WellnessCheckbox(extra.versionId in selectedAddons, { checked ->
                                    selectedAddons = if (checked) selectedAddons + extra.versionId else selectedAddons - extra.versionId
                                    calculation = null
                                }, enabled = extra.canCalculate && !busy && !calculating)
                                Column(Modifier.weight(1f)) {
                                    Text(extra.name)
                                    Text(stringResource(catalogBasis(extra.basis)), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                    if (!extra.canCalculate) Text(stringResource(R.string.catalog_nutrition_missing), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (extra.versionId in selectedAddons) {
                                val value = addonAmounts[extra.versionId].orEmpty()
                                val amount = value.replace(',', '.').toDoubleOrNull()
                                WellnessTextField(value, { addonAmounts = addonAmounts + (extra.versionId to it); calculation = null },
                                    label = { Text(stringResource(catalogQuantityLabel(extra.defaultUnit))) },
                                    modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy && !calculating, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    isError = amount == null || !amount.isFinite() || amount <= 0 || amount > 10000, colors = wellnessFieldColors())
                                val fraction = eaten.replace(',', '.').toDoubleOrNull()
                                if (amount != null && fraction != null && amount.isFinite() && fraction.isFinite()) Text(
                                    stringResource(R.string.catalog_addon_consumed, catalogNumber(amount * fraction), stringResource(catalogUnit(extra.defaultUnit))),
                                    style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            }
                            }
                        }
                    }
                }
                val currentCalculation = calculation
                if (currentCalculation != null) item {
                    WellnessCard(Modifier.fillMaxWidth(), emphasized = true) {
                        Text(stringResource(R.string.catalog_calculated_title), style = MaterialTheme.typography.titleLarge)
                        CatalogNutrientsView(currentCalculation.nutrients)
                        currentCalculation.massGrams?.let { Text(stringResource(R.string.catalog_consumed_mass, catalogNumber(it))) }
                        currentCalculation.volumeMl?.let { Text(stringResource(R.string.catalog_consumed_volume, catalogNumber(it))) }
                        if (currentCalculation.snapshot["offline_cached"]?.toString() == "true") Text(stringResource(R.string.catalog_offline_calculation), color = TextSecondary)
                        Text(stringResource(R.string.catalog_snapshot_hint), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                }
                item {
                    val parsed = quantity.replace(',', '.').toDoubleOrNull()
                    val fraction = eaten.replace(',', '.').toDoubleOrNull()
                    val validAddons = selectedAddons.all { key -> addonAmounts[key]?.replace(',', '.')?.toDoubleOrNull()?.let { it.isFinite() && it > 0 && fraction != null && it * fraction <= 10000 } == true }
                    val valid = parsed != null && parsed.isFinite() && parsed > 0 && fraction != null && fraction.isFinite() && fraction in .05..20.0 && parsed * fraction <= 10000 && validAddons
                    PrimaryButton(stringResource(if (currentCalculation == null) R.string.catalog_calculate else R.string.catalog_use_values), {
                        val amount = requireNotNull(parsed) * requireNotNull(fraction)
                        val selectedExtras = addons.filter { it.versionId in selectedAddons }.map { CatalogAddon(it.versionId, requireNotNull(addonAmounts[it.versionId]?.replace(',', '.')?.toDoubleOrNull()) * fraction, it.defaultUnit) }
                        if (currentCalculation != null) onSelected(CatalogSelection(entry, amount, entry.defaultUnit, selectedExtras, currentCalculation))
                        else {
                          calculating = true; error = null
                          scope.launch {
                            val requestedQuantity = quantity
                            val requestedEaten = eaten
                            val requestedAddons = selectedAddons
                            val requestedAmounts = addonAmounts
                            try {
                                val response = repository.calculate(entry, amount, entry.defaultUnit, selectedExtras)
                                if (selected?.versionId == entry.versionId && quantity == requestedQuantity && eaten == requestedEaten &&
                                    selectedAddons == requestedAddons && addonAmounts == requestedAmounts) calculation = response
                            }
                            catch (failure: Exception) { fail(failure) }
                            finally { calculating = false }
                          }
                        }
                    }, enabled = valid && !busy && !calculating, modifier = Modifier.testTag("catalog_confirm"))
                }
            } else item {
                WellnessCard(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.catalog_nutrition_missing), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.catalog_manual_explanation), color = TextSecondary)
                    PrimaryButton(stringResource(R.string.catalog_enter_manually), {
                        onSelected(CatalogSelection(entry, entry.defaultQuantity, entry.defaultUnit))
                    }, modifier = Modifier.testTag("catalog_manual"))
                }
            }
        } else when {
            kind == "dish" && city == null -> {
                item { Text(stringResource(R.string.catalog_choose_city), style = MaterialTheme.typography.titleLarge) }
                items(cities, key = { it }) { choice -> WellnessOutlinedButton({ city = choice; branchScope = null }, Modifier.fillMaxWidth()) { Text(choice) } }
                if (!busy && cities.isEmpty()) item { Text(stringResource(R.string.catalog_no_cities)) }
            }
            kind == "dish" && chain == null -> {
                item { Text(requireNotNull(city), style = MaterialTheme.typography.titleLarge); Text(stringResource(R.string.catalog_choose_restaurant)) }
                items(restaurants, key = { listOf(it.chain, it.city, it.branchScope).joinToString("|") }) { choice ->
                    WellnessCard(Modifier.fillMaxWidth()) {
                        Text(choice.chain, style = MaterialTheme.typography.titleLarge)
                        choice.branchScope?.let { Text(it, color = TextSecondary) }
                        WellnessOutlinedButton({ chain = choice.chain; branchScope = choice.branchScope }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.catalog_open_menu)) }
                    }
                }
                if (!busy && restaurants.isEmpty()) item { Text(stringResource(R.string.catalog_empty)) }
            }
            else -> {
                item {
                    if (kind == "dish") { Text(listOfNotNull(city, chain).joinToString(" · "), style = MaterialTheme.typography.titleMedium)
                        branchScope?.let { Text(it, color = TextSecondary, style = MaterialTheme.typography.bodySmall) } }
                    WellnessTextField(query, { query = it; barcode = null }, modifier = Modifier.fillMaxWidth().testTag("catalog_search"),
                        label = { Text(stringResource(R.string.catalog_search_label)) }, singleLine = true,
                        supportingText = { Text(stringResource(R.string.catalog_languages_hint)) }, colors = wellnessFieldColors())
                    if (barcode != null) {
                        Text(stringResource(R.string.catalog_barcode_value, requireNotNull(barcode)))
                        WellnessTextButton({ barcode = null; query = "" }) { Text(stringResource(R.string.catalog_clear_barcode)) }
                    }
                    if (kind == "product" && onScanBarcode != null) WellnessOutlinedButton(onScanBarcode, Modifier.fillMaxWidth()) { Text(stringResource(R.string.catalog_scan_barcode)) }
                }
                if (query.isBlank() && barcode == null && recent.isNotEmpty()) {
                    item { Text(stringResource(R.string.catalog_recent), style = MaterialTheme.typography.titleLarge) }
                    items(recent.filter { kind != "dish" || it.city == city && it.chain == chain && (branchScope == null || it.branchScope == branchScope) }.take(5), key = { "recent:${it.versionId}" }) { candidate ->
                        CatalogEntryCard(candidate, { select(candidate) })
                    }
                }
                item { Text(stringResource(R.string.catalog_results), style = MaterialTheme.typography.titleLarge) }
                items(results, key = { it.versionId }) { candidate -> CatalogEntryCard(candidate, { select(candidate) }) }
                if (!busy && results.isEmpty()) item {
                    Text(stringResource(if (barcode != null) R.string.catalog_barcode_no_match else R.string.catalog_empty), color = TextSecondary)
                    if (onManualEntry != null) WellnessOutlinedButton(onManualEntry, Modifier.fillMaxWidth()) { Text(stringResource(R.string.manual)) }
                }
            }
        }
        item { Text(stringResource(R.string.catalog_coverage), style = MaterialTheme.typography.bodySmall, color = TextSecondary) }
    }
}

@Composable private fun CatalogEntryCard(entry: CatalogEntry, select: () -> Unit) {
    WellnessCard(Modifier.fillMaxWidth()) {
        Text(entry.name, style = MaterialTheme.typography.titleLarge)
        listOfNotNull(entry.brand, entry.variant).takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" · "), color = TextSecondary) }
        Text(stringResource(catalogBasis(entry.basis)), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Text(stringResource(catalogStatus(entry.source.status)), style = MaterialTheme.typography.labelLarge)
        if (!entry.canCalculate) Text(stringResource(R.string.catalog_nutrition_missing), style = MaterialTheme.typography.bodySmall)
        WellnessOutlinedButton(select, Modifier.fillMaxWidth().testTag("catalog_choose_${entry.id}")) { Text(stringResource(R.string.catalog_choose)) }
    }
}

@Composable private fun CatalogProvenance(entry: CatalogEntry) {
    val uriHandler = LocalUriHandler.current
    Text(stringResource(catalogStatus(entry.source.status)), style = MaterialTheme.typography.labelLarge)
    Text(stringResource(catalogBasis(entry.basis)), style = MaterialTheme.typography.bodyMedium)
    entry.branchScope?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TextSecondary) }
    Text(stringResource(R.string.catalog_checked, entry.source.checkedAt), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    Text(entry.source.license, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    if (entry.source.url.startsWith("https://") || entry.source.url.startsWith("http://")) {
        WellnessTextButton({ uriHandler.openUri(entry.source.url) }) { Text(stringResource(R.string.catalog_source)) }
    }
}

@Composable private fun CatalogNutrientsView(values: CatalogNutrients) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(R.string.calorie_total to values.calories, R.string.protein to values.protein,
            R.string.fat to values.fat, R.string.carbs to values.carbs).forEach { (label, value) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(label), modifier = Modifier.weight(1f))
                Text(value?.let { catalogNumber(it) + " " + stringResource(if (label == R.string.calorie_total) R.string.calories_unit else R.string.catalog_unit_g) }
                    ?: stringResource(R.string.catalog_unknown), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

private fun catalogNumber(value: Double): String = BigDecimal.valueOf(value).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
private fun catalogBasis(value: String) = when (value) {
    "per_100g" -> R.string.catalog_per_100g; "per_100ml" -> R.string.catalog_per_100ml
    "per_portion" -> R.string.catalog_per_portion; else -> R.string.catalog_basis_unknown
}
private fun catalogQuantityLabel(value: String) = when (value) { "g" -> R.string.catalog_quantity_g; "ml" -> R.string.catalog_quantity_ml; else -> R.string.catalog_quantity_portion }
private fun catalogUnit(value: String) = when (value) { "g" -> R.string.catalog_unit_g; "ml" -> R.string.catalog_unit_ml; else -> R.string.catalog_unit_portion }
private fun catalogStatus(value: String) = when (value) {
    "official" -> R.string.catalog_status_official; "open" -> R.string.catalog_status_open
    "user" -> R.string.catalog_status_user; else -> R.string.catalog_status_estimate
}
private fun catalogError(value: String) = when (value) {
    "catalog_nutrition_missing" -> R.string.catalog_nutrition_missing
    "catalog_quantity_basis" -> R.string.catalog_unit_error
    "catalog_addon_invalid", "catalog_addon_not_allowed" -> R.string.catalog_addon_error
    "catalog_quantity_too_large" -> R.string.catalog_quantity_too_large
    "unauthorized", "session_expired", "token_expired" -> R.string.catalog_auth_error
    else -> R.string.catalog_network_error
}
