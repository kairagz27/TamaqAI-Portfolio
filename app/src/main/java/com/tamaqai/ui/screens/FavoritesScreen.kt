package com.tamaqai.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tamaqai.R
import com.tamaqai.data.local.PhotoStore
import com.tamaqai.domain.model.*
import com.tamaqai.ui.components.*
import java.time.LocalDate

@Composable fun FavoritesScreen(vm: DailyViewModel, photos: PhotoStore, date: LocalDate,
    open: (String) -> Unit, back: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var delete by remember { mutableStateOf<FavoriteMeal?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
            Text(stringResource(R.string.favorites), style = MaterialTheme.typography.headlineLarge)
            Text(dateLabel(date))
        }
        if (state.loading) item { WellnessLoadingIndicator() }
        if (!state.loading && state.favorites.isEmpty()) item {
            EmptyState(stringResource(R.string.empty_favorites_title), stringResource(R.string.empty_favorites_body))
        }
        items(state.favorites, key = FavoriteMeal::id) { favorite ->
            TamaqCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MealPhoto(photos, null, Modifier.size(70.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(favorite.nutrition.name, style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.calories_value, favorite.nutrition.calories), color = com.tamaqai.ui.theme.AccentBrown)
                    }
                }
                MacroSummary(favorite.nutrition)
                Text(favorite.source.label(), style = MaterialTheme.typography.labelSmall)
                Text(stringResource(R.string.repeat_hint), style = MaterialTheme.typography.bodySmall)
                PrimaryButton(stringResource(R.string.use_favorite), { vm.useFavorite(favorite, date, open) }, enabled = !state.busy)
                WellnessTextButton(onClick = { delete = favorite }, enabled = !state.busy) { Text(stringResource(R.string.remove_favorite)) }
            }
        }
    }
    delete?.let { favorite -> ConfirmDialog(R.string.favorite_delete_title, R.string.favorite_delete_body,
        { delete = null }, { vm.removeFavorite(favorite.id); delete = null }) }
}
