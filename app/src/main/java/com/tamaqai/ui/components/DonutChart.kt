package com.tamaqai.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.tamaqai.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tamaqai.domain.model.Nutrition
import com.tamaqai.ui.theme.CarbColor
import com.tamaqai.ui.theme.FatColor
import com.tamaqai.ui.theme.ChartTrack
import com.tamaqai.ui.theme.BodyFont
import com.tamaqai.ui.theme.TextSecondary
import com.tamaqai.ui.theme.ProteinColor
import java.text.NumberFormat

@Composable
fun DonutChart(
    nutrition: Nutrition,
    modifier: Modifier = Modifier,
) {
    val proteinCalories = nutrition.protein * 4f
    val fatCalories = nutrition.fat * 9f
    val carbCalories = nutrition.carbs * 4f
    val macroCalories = proteinCalories + fatCalories + carbCalories
    val segments = listOf(
        proteinCalories to ProteinColor,
        fatCalories to FatColor,
        carbCalories to CarbColor,
    )
    val caloriesLabel = NumberFormat.getIntegerInstance().format(nutrition.calories)

    val description = stringResource(R.string.nutrition_description, nutrition.calories, nutrition.protein, nutrition.fat, nutrition.carbs)
    Box(
        modifier = modifier
            .size(188.dp)
            .semantics {
                contentDescription = description
            },
        contentAlignment = Alignment.Center,
    ) {
        val track=ChartTrack
        Canvas(Modifier.fillMaxSize()) {
            val strokeWidth = 22.dp.toPx()
            val diameter = size.minDimension - strokeWidth
            val arcSize = androidx.compose.ui.geometry.Size(diameter, diameter)
            val topLeft = androidx.compose.ui.geometry.Offset(
                (size.width - diameter) / 2f,
                (size.height - diameter) / 2f,
            )
            val arcStyle = Stroke(width = strokeWidth, cap = StrokeCap.Butt)

            if (macroCalories <= 0f) {
                drawArc(
                    color = track,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = arcStyle,
                )
            } else {
                var startAngle = -90f
                segments.forEach { (value, color) ->
                    if (value > 0f) {
                        val sweepAngle = value / macroCalories * 360f
                        drawArc(
                            color = color,
                            startAngle = startAngle,
                            sweepAngle = (sweepAngle - 2f).coerceAtLeast(0f),
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = arcStyle,
                        )
                        startAngle += sweepAngle
                    }
                }
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = caloriesLabel,
                style = MaterialTheme.typography.headlineMedium.copy(fontFamily=BodyFont),
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.calories_unit),
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
            )
        }
    }
}
