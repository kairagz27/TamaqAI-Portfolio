package com.tamaqai.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tamaqai.R
import com.tamaqai.ui.theme.TextSecondary
import com.tamaqai.ui.theme.WarningColor

/** The accompanying numbers include only confirmed records with known nutrients. */
@Composable
fun IncompleteNutritionNotice(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Column(modifier.fillMaxWidth().testTag("incomplete-nutrition-notice"),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.catalog_totals_incomplete),
            style = MaterialTheme.typography.titleSmall, color = WarningColor)
        Text(pluralStringResource(R.plurals.catalog_totals_missing_records, count, count),
            style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    }
}
