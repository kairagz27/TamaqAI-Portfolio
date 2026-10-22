package com.tamaqai.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tamaqai.R
import com.tamaqai.ui.theme.*

/** Warm base layer shared by every screen. */
@Composable fun WellnessBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.background(PageBackground), content = content)
}

@Composable fun WellnessCard(modifier: Modifier = Modifier, emphasized: Boolean = false, contentPadding: androidx.compose.ui.unit.Dp = 20.dp, contentSpacing: androidx.compose.ui.unit.Dp = 12.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.matteSurface(emphasized)
        .padding(contentPadding), verticalArrangement = Arrangement.spacedBy(contentSpacing), content = content)
}

/** The shared field wrapper supplies the dense glass; states keep their own readable ink and outline. */
@Composable fun wellnessFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor=TextPrimary, unfocusedTextColor=TextPrimary, disabledTextColor=TextSecondary, errorTextColor=TextPrimary,
    focusedContainerColor=Color.Transparent, unfocusedContainerColor=Color.Transparent,
    disabledContainerColor=Color.Transparent, errorContainerColor=Color.Transparent,
    focusedBorderColor=AccentBrown, unfocusedBorderColor=ControlOutline, disabledBorderColor=ControlOutline, errorBorderColor=ErrorColor,
    focusedLabelColor=AccentBrown, unfocusedLabelColor=TextSecondary, disabledLabelColor=TextSecondary, errorLabelColor=ErrorColor,
    focusedLeadingIconColor=TextSecondary, unfocusedLeadingIconColor=TextSecondary, disabledLeadingIconColor=TextSecondary, errorLeadingIconColor=ErrorColor,
    focusedTrailingIconColor=TextSecondary, unfocusedTrailingIconColor=TextSecondary, disabledTrailingIconColor=TextSecondary, errorTrailingIconColor=ErrorColor,
    focusedSupportingTextColor=TextSecondary, unfocusedSupportingTextColor=TextSecondary, disabledSupportingTextColor=TextSecondary, errorSupportingTextColor=ErrorColor,
    cursorColor=AccentBrown, errorCursorColor=ErrorColor,
    selectionColors=TextSelectionColors(AccentBrown,SelectedSurface),
)

@Composable fun WellnessSnackbar(data: SnackbarData) {
    val shape=RoundedCornerShape(20.dp)
    Snackbar(data,modifier=Modifier.padding(12.dp).clip(shape).glassBackdrop(material=WellnessGlassMaterial.Panel)
        .border(.5.dp,BorderSoft,shape),
        actionOnNewLine=true,shape=shape,containerColor=Color.Transparent,contentColor=TextPrimary,
        actionColor=AccentBrown,dismissActionContentColor=TextSecondary)
}

@Composable fun WellnessOutlinedButton(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,content:@Composable RowScope.()->Unit) {
    OutlinedButton(onClick,modifier.heightIn(min=WellnessSpace.touch),enabled=enabled,shape=RoundedCornerShape(WellnessSpace.control),border=BorderStroke(1.dp,ControlOutline),
        colors=ButtonDefaults.outlinedButtonColors(containerColor=CardSurface,contentColor=AccentBrown,
            disabledContainerColor=DisabledSurface,disabledContentColor=TextSecondary),content=content)
}

@Composable fun WellnessTextButton(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,content:@Composable RowScope.()->Unit) {
    TextButton(onClick,modifier.heightIn(min=WellnessSpace.touch),enabled=enabled,
        colors=ButtonDefaults.textButtonColors(contentColor=AccentBrown,disabledContentColor=TextSecondary),content=content)
}

@Composable fun wellnessChipColors() = FilterChipDefaults.filterChipColors(
    containerColor=CardSurface,labelColor=TextPrimary,selectedContainerColor=SelectedSurface,selectedLabelColor=AccentBrown,
    disabledContainerColor=DisabledSurface,disabledLabelColor=TextSecondary,disabledSelectedContainerColor=DisabledSurface,
)

/** Pastel action fills do not provide sufficient contrast for a tiny selection stroke. */
@Composable fun WellnessRadioButton(selected:Boolean,onClick:(()->Unit)?,modifier:Modifier=Modifier,enabled:Boolean=true) {
    RadioButton(selected,onClick,modifier,enabled,colors=RadioButtonDefaults.colors(
        selectedColor=AccentBrown,unselectedColor=ControlOutline,
        disabledSelectedColor=TextSecondary,disabledUnselectedColor=ControlOutline))
}

@Composable fun WellnessCheckbox(checked:Boolean,onCheckedChange:((Boolean)->Unit)?,modifier:Modifier=Modifier,enabled:Boolean=true) {
    Checkbox(checked,onCheckedChange,modifier,enabled,colors=CheckboxDefaults.colors(
        checkedColor=AccentBrown,checkmarkColor=CardSurface,uncheckedColor=ControlOutline,
        disabledCheckedColor=TextSecondary,disabledUncheckedColor=ControlOutline))
}

@Composable fun WellnessLoadingIndicator(modifier:Modifier=Modifier) {
    CircularProgressIndicator(modifier=modifier,color=AccentBrown,trackColor=SecondarySurface)
}

@Composable fun NutritionRing(progress: Float, label: String, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), tween(WellnessMotion.indicator), label = "nutrition progress")
    val track=ChartTrack
    val accent=AccentBrown
    Canvas(modifier.size(104.dp).semantics { contentDescription = label; progressBarRangeInfo = ProgressBarRangeInfo(progress.coerceIn(0f,1f), 0f..1f) }) {
        val inset = 10.dp.toPx()
        val diameter = size.minDimension - inset * 2
        val arcSize = androidx.compose.ui.geometry.Size(diameter,diameter)
        drawArc(track, -90f, 360f, false, Offset(inset,inset), arcSize, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
        if (animated > 0) drawArc(accent, -90f,
            360f * animated, false, Offset(inset,inset), arcSize, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable fun FloatingNavigation(selected: String, destinations: List<String>, labels: List<String>,
    icons: List<androidx.compose.ui.graphics.vector.ImageVector>, onSelect: (String) -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = WellnessSpace.page, vertical = 8.dp)
        .shadow(WellnessGlass.shadow, shape,ambientColor=ShadowColor.copy(alpha=WellnessGlass.shadowOpacity),spotColor=ShadowColor.copy(alpha=WellnessGlass.shadowOpacity)).clip(shape)
        .glassBackdrop(material=WellnessGlassMaterial.Navigation)
        .border(.5.dp, GlassEdge.copy(alpha=.4f), shape).padding(horizontal=4.dp,vertical=2.dp)) {
        destinations.forEachIndexed { i, route ->
            val active = route == selected
            val interactions=remember {androidx.compose.foundation.interaction.MutableInteractionSource()}
            val pressed by interactions.collectIsPressedAsState()
            val scale by animateFloatAsState(if(pressed) .96f else 1f,tween(WellnessMotion.press),label="navigation press")
            val selectedColor = androidx.compose.animation.animateColorAsState(if(active) SelectedSurface else Color.Transparent,tween(WellnessMotion.press),label="navigation selection")
            Column(Modifier.weight(1f).heightIn(min = 60.dp).clip(RoundedCornerShape(22.dp))
                .selectable(active,interactionSource=interactions,indication=null,role = Role.Tab, onClick = { onSelect(route) }).padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(42.dp,28.dp).graphicsLayer {scaleX=scale;scaleY=scale}
                    // Read animation state in drawing, keeping text/layout out of each color frame.
                    .drawBehind {
                        val radius = 12.dp.toPx()
                        drawRoundRect(selectedColor.value, cornerRadius = CornerRadius(radius, radius))
                    },contentAlignment=Alignment.Center) {
                    Icon(icons[i], null, modifier = Modifier.size(23.dp), tint = if (active) AccentBrown else TextSecondary)
                }
                Text(labels[i], color = if (active) AccentBrown else TextSecondary, fontSize = 11.sp, lineHeight = 16.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Coefficients stay internal. Other is entered as a percentage of the original serving. */
@Composable fun EatenPortionSelector(value: String, update: (String) -> Unit, enabled: Boolean = true,
    title: String = stringResource(R.string.eaten_portion), semanticPrefix: String = title, component: Boolean = false) {
    val number = value.replace(',', '.').toDoubleOrNull()
    var custom by rememberSaveable { mutableStateOf(number !in listOf(1.0, .5, .25)) }
    fun percentText() = number?.takeIf { it.isFinite() }?.let {
        java.math.BigDecimal.valueOf(it).multiply(java.math.BigDecimal(100)).stripTrailingZeros().toPlainString()
    } ?: value
    var input by rememberSaveable { mutableStateOf(percentText()) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value, focused) { if (!focused) input = percentText() }
    val percentLabel = stringResource(R.string.eaten_percent)
    Text(title, style = MaterialTheme.typography.titleMedium)
    val options = listOf(1.0 to R.string.portion_all, .5 to R.string.portion_half, .25 to R.string.portion_quarter, null to R.string.portion_other)
    val columns = if (LocalDensity.current.fontScale > 1.15f) 2 else 4
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      options.chunked(columns).forEach { group ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          group.forEach { (amount, text) ->
            val selected = if (amount == null) custom else !custom && number == amount
            val label = stringResource(text)
            val shape = RoundedCornerShape(14.dp)
            Box(Modifier.weight(1f).heightIn(min = 52.dp).clip(shape)
                .background(if (selected) MaterialTheme.colorScheme.primary else if (!enabled) DisabledSurface else SecondarySurface)
                .border(.5.dp, if (selected) Color.Transparent else BorderSoft, shape)
                .selectable(selected, enabled, role = Role.RadioButton, onClick = {
                    custom = amount == null
                    if (amount != null) update(amount.toString()) else input = percentText()
                }).semantics { contentDescription = "$semanticPrefix: $label" }.padding(horizontal = 3.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center) {
                Text(label, fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else if (!enabled) TextSecondary else TextPrimary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        }
      }
    }
    if (custom) {
        WellnessTextField(input, { text -> input = text; val parsed = text.replace(',', '.').toDoubleOrNull()
            update(if (parsed == null) text else (parsed / 100.0).toString()) },
            label = { Text(percentLabel) }, enabled = enabled, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = "$semanticPrefix: $percentLabel" },
            isError = number == null || !number.isFinite() || number !in (if (component) 0.0 else .05)..20.0,
            supportingText = { Text(stringResource(if (component) R.string.component_percent_hint else R.string.eaten_percent_hint)) }, colors = wellnessFieldColors())
    }
}
