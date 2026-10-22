package com.tamaqai.ui.components

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import com.tamaqai.ui.theme.*
import dev.chrisbanes.haze.*

/** Shared GPU layers. Inline surfaces never read the captured ancestor containing themselves. */
val LocalWellnessBackdrop = staticCompositionLocalOf<HazeState?> { null }
val LocalWellnessOverlayBackdrop = staticCompositionLocalOf<HazeState?> { null }
val LocalWellnessOverlay = staticCompositionLocalOf { false }
val LocalWellnessModalToken = staticCompositionLocalOf<Any?> { null }
private val LocalWellnessModalStack = staticCompositionLocalOf<androidx.compose.runtime.snapshots.SnapshotStateList<Any>?> { null }
private val LocalWellnessOverlayCount = staticCompositionLocalOf<MutableState<Int>?> { null }
object WellnessBackdropKeys {
    const val Page = "wellness-page"
    const val Content = "wellness-content"
}
enum class WellnessGlassMaterial { Card, Navigation, Panel, Field }

@Composable fun WellnessGlassHost(content: @Composable () -> Unit) {
    val state = rememberWellnessBackdrop()
    // Cross-window invalidation belongs only to the captured content. Cards and
    // fields must never observe HazeDialog's forced ticks on the page source.
    val overlayState = remember(state) { if (state != null) HazeState() else null }
    val overlays = remember { mutableIntStateOf(0) }
    val modals = remember { mutableStateListOf<Any>() }
    CompositionLocalProvider(LocalWellnessBackdrop provides state,
        LocalWellnessOverlayBackdrop provides overlayState, LocalWellnessOverlayCount provides overlays,
        LocalWellnessModalStack provides modals) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.matchParentSize()
                .then(if (state != null) Modifier.hazeSource(state, zIndex = 0f, key = WellnessBackdropKeys.Page) else Modifier)
                .background(PageBackground))
            content()
        }
    }
}

/** The content layer is needed by visible overlays, not by inline cards on a flat page. */
@Composable fun wellnessNeedsContentBackdrop(): Boolean = (LocalWellnessOverlayCount.current?.value ?: 0) > 0 ||
    LocalWellnessModalStack.current?.isNotEmpty() == true

/** A token belongs to a real modal window, not to an inline drawer or card. */
@Composable fun rememberWellnessModalToken(): Any {
    val token = remember { Any() }
    val stack = LocalWellnessModalStack.current
    DisposableEffect(stack, token) {
        stack?.add(token)
        onDispose { stack?.remove(token) }
    }
    return token
}

/** Gate the source capture too, so fallback devices allocate no blur layer. */
@Composable fun rememberWellnessBackdrop():HazeState? {
    val context=LocalContext.current
    val view=LocalView.current
    var hardware by remember(view) {mutableStateOf(view.isHardwareAccelerated)}
    DisposableEffect(view) {
        // A ComposeView may first compose before attachment. Do not cache that
        // temporary false value for the entire Activity lifetime.
        val listener=object:android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v:android.view.View) {hardware=v.isHardwareAccelerated}
            override fun onViewDetachedFromWindow(v:android.view.View) {hardware=false}
        }
        view.addOnAttachStateChangeListener(listener)
        view.post {if(view.isAttachedToWindow) hardware=view.isHardwareAccelerated}
        onDispose {view.removeOnAttachStateChangeListener(listener)}
    }
    val device=remember(context) {
        val manager=context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory=ActivityManager.MemoryInfo().also {manager.getMemoryInfo(it)}
        Pair(manager.isLowRamDevice,memory.totalMem)
    }
    val supported=supportsWellnessBlur(Build.VERSION.SDK_INT,hardware,device.first,device.second)
    return remember(supported) {if(supported) HazeState() else null}
}

@Composable fun Modifier.matteSurface(emphasized: Boolean = false, shape: Shape = RoundedCornerShape(WellnessSpace.card)): Modifier {
    return shadow(if (emphasized) WellnessGlass.shadow else 2.dp, shape,
        ambientColor = ShadowColor.copy(alpha = WellnessGlass.shadowOpacity),
        spotColor = ShadowColor.copy(alpha = WellnessGlass.shadowOpacity))
        .clip(shape).glassBackdrop(material = WellnessGlassMaterial.Card)
        .border(.5.dp, GlassEdge.copy(alpha=.48f), shape)
}

@OptIn(ExperimentalHazeApi::class)
@Composable fun Modifier.glassBackdrop(dialog: Boolean = false, material: WellnessGlassMaterial = WellnessGlassMaterial.Panel): Modifier {
    val dark = PageBackground.luminance() < .5f
    val opacity = when (material) {
        WellnessGlassMaterial.Card -> if (dark) WellnessGlass.darkCardOpacity else WellnessGlass.cardOpacity
        WellnessGlassMaterial.Navigation -> if (dark) WellnessGlass.darkNavigationOpacity else WellnessGlass.navigationOpacity
        WellnessGlassMaterial.Panel -> if (dark) WellnessGlass.darkPanelOpacity else WellnessGlass.panelOpacity
        WellnessGlassMaterial.Field -> if (dark) WellnessGlass.darkFieldOpacity else WellnessGlass.fieldOpacity
    }
    val wantsOverlay = (dialog || LocalWellnessOverlay.current) && material in listOf(WellnessGlassMaterial.Panel, WellnessGlassMaterial.Navigation)
    val topModal = LocalWellnessModalStack.current?.lastOrNull()
    val localModal = LocalWellnessModalToken.current
    // Covered windows keep their warm page blur, but stop observing forced
    // content invalidations from the top modal. Otherwise their redraw would
    // trigger HazeDialog's parent pre-draw listener again, forming a loop.
    val overlay = wantsOverlay && (topModal == null || topModal === localModal)
    // The fallback permits isolated diagnostic compositions with a manually
    // provided single source, without coupling the production host's two states.
    val state = if (overlay) LocalWellnessOverlayBackdrop.current ?: LocalWellnessBackdrop.current
        else LocalWellnessBackdrop.current
    val consumers = LocalWellnessOverlayCount.current
    DisposableEffect(consumers, overlay, state) {
        if (overlay && state != null && consumers != null) consumers.value++
        onDispose { if (overlay && state != null && consumers != null) consumers.value-- }
    }
    return if (state != null) hazeEffect(state, HazeStyle(
        backgroundColor = PageBackground,
        // HazeDialog requires the tint on its surface, not on the captured window.
        tint = HazeTint(if(dialog) Color.Transparent else CardSurface.copy(alpha = opacity)),
        blurRadius = if (material == WellnessGlassMaterial.Card || material == WellnessGlassMaterial.Field) 16.dp else WellnessGlass.blur, noiseFactor = 0f,
        fallbackTint = HazeTint(CardSurface),
    )) {
        inputScale=HazeInputScale.Auto
        // The page is a separate shared layer. A card/field must never sample
        // the NavHost ancestor containing its own sharp text or its own effect.
        canDrawArea = { area -> overlay || area.key != WellnessBackdropKeys.Content }
    }
        .then(if(dialog) Modifier.background(CardSurface.copy(alpha=opacity)) else Modifier)
    else background(CardSurface)
}

/** The public Material decoration API keeps the glass inside the field container only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun WellnessTextField(
    value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, singleLine: Boolean = false, isError: Boolean = false,
    label: (@Composable () -> Unit)? = null, supportingText: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null, trailingIcon: (@Composable () -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    colors: TextFieldColors = wellnessFieldColors(),
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val shape = RoundedCornerShape(WellnessSpace.control)
    val textColor = LocalTextStyle.current.color.takeOrElse {
        when {
            !enabled -> colors.disabledTextColor
            isError -> colors.errorTextColor
            focused -> colors.focusedTextColor
            else -> colors.unfocusedTextColor
        }
    }
    val defaultError = stringResource(androidx.compose.ui.R.string.default_error_message)
    // This is the same 8 sp reservation used by Material3 1.3.2 for the
    // floating label. Its upper half must remain outside the glass clip.
    val labelPadding = with(LocalDensity.current) { 8.sp.toDp() }
    CompositionLocalProvider(LocalTextSelectionColors provides colors.textSelectionColors) {
        BasicTextField(value = value, onValueChange = onValueChange,
            modifier = modifier.heightIn(min = 56.dp)
                .then(if (label != null) Modifier.semantics(mergeDescendants = true) {}.padding(top = labelPadding) else Modifier)
                .semantics { if (isError) error(defaultError) }
                .defaultMinSize(minWidth = OutlinedTextFieldDefaults.MinWidth, minHeight = OutlinedTextFieldDefaults.MinHeight),
            enabled = enabled, singleLine = singleLine,
            textStyle = LocalTextStyle.current.merge(TextStyle(color = textColor)),
            cursorBrush = SolidColor(if (isError) colors.errorCursorColor else colors.cursorColor),
            interactionSource = interactions, keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            decorationBox = { innerTextField ->
                OutlinedTextFieldDefaults.DecorationBox(value = value, innerTextField = innerTextField,
                    enabled = enabled, singleLine = singleLine, visualTransformation = visualTransformation,
                    interactionSource = interactions, isError = isError, label = label,
                    supportingText = supportingText, placeholder = placeholder,
                    leadingIcon = leadingIcon, trailingIcon = trailingIcon, colors = colors,
                    container = {
                        OutlinedTextFieldDefaults.Container(enabled = enabled, isError = isError,
                            interactionSource = interactions, colors = colors, shape = shape,
                            modifier = Modifier.clip(shape).glassBackdrop(material = WellnessGlassMaterial.Field))
                    })
            })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun WellnessDialog(
    onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier, dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null, text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
    contentPadding: androidx.compose.ui.unit.Dp = 24.dp,
) {
    // Compose UI 1.9 applies ADJUST_NOTHING to this edge-to-edge dialog on
    // Android 12+. Handle safe/IME insets once in composition instead of relying
    // on a device-specific platform resize/pan that can hide the action row.
    val insetAwareProperties = DialogProperties(
        dismissOnBackPress = properties.dismissOnBackPress,
        dismissOnClickOutside = properties.dismissOnClickOutside,
        securePolicy = properties.securePolicy,
        usePlatformDefaultWidth = properties.usePlatformDefaultWidth,
        decorFitsSystemWindows = false,
        windowTitle = properties.windowTitle,
    )
    WellnessBackdropDialog(onDismissRequest, insetAwareProperties) {
        val dialogLabel = stringResource(androidx.compose.material3.R.string.m3c_dialog)
        BoxWithConstraints(Modifier.safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
            val dialogMaximum = maxHeight
            val shape=RoundedCornerShape(WellnessSpace.card)
            Surface(modifier.widthIn(min=280.dp,max=560.dp).heightIn(max=maxHeight).clip(shape)
                .glassBackdrop(dialog=true).border(.5.dp,GlassEdge.copy(alpha=.48f),shape)
                .semantics { paneTitle = dialogLabel },
                color=Color.Transparent,shape=shape,tonalElevation=0.dp) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(contentPadding),
                    verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    title?.let { headingContent -> Box(Modifier.semantics { heading() }) {
                        ProvideTextStyle(MaterialTheme.typography.headlineSmall) { headingContent() }
                    } }
                    text?.let { bodyContent ->
                        // Existing dialog bodies may already own a scroll container.
                        // Keep their measurement finite inside the outer action-row scroll.
                        Box(Modifier.heightIn(max=(dialogMaximum-contentPadding*2).coerceAtLeast(0.dp))) {
                            CompositionLocalProvider(LocalContentColor provides TextSecondary) {
                                ProvideTextStyle(MaterialTheme.typography.bodyMedium) { bodyContent() }
                            }
                        }
                    }
                    FlowRow(Modifier.fillMaxWidth().then(if(contentPadding==0.dp) Modifier.padding(horizontal=16.dp,vertical=8.dp) else Modifier),
                        horizontalArrangement=Arrangement.spacedBy(8.dp,Alignment.End)) {
                        dismissButton?.invoke();confirmButton()
                    }
                }
            }
        }
    }
}

/** HazeDialog updates the Activity source from the separate modal window. */
@Composable private fun WellnessBackdropDialog(onDismissRequest:()->Unit,properties:DialogProperties,
    content:@Composable ()->Unit) {
    val state=LocalWellnessOverlayBackdrop.current ?: LocalWellnessBackdrop.current
    val token=rememberWellnessModalToken()
    val context=LocalContext.current
    val sourceActivity=remember(context) {context.wellnessActivity()}
    val body:@Composable ()->Unit={CompositionLocalProvider(LocalWellnessOverlay provides true,
        LocalWellnessModalToken provides token) {
        val view=LocalView.current
        val window=(view.parent as? DialogWindowProvider)?.window
        val fallbackLight=PageBackground.luminance()>.5f
        SideEffect {window?.let {
            // Read the parent's current flags at commit, after SystemPanels has
            // applied the theme/camera state. Caching palette-based flags here
            // would put dark icons over the camera's dark controls in Light mode.
            val parent=sourceActivity?.window?.let {parentWindow ->
                androidx.core.view.WindowCompat.getInsetsController(parentWindow,parentWindow.decorView)
            }
            androidx.core.view.WindowCompat.getInsetsController(it,it.decorView).apply {
                isAppearanceLightStatusBars=parent?.isAppearanceLightStatusBars ?: fallbackLight
                isAppearanceLightNavigationBars=parent?.isAppearanceLightNavigationBars ?: fallbackLight
            }
            if(Build.VERSION.SDK_INT>=29) it.isNavigationBarContrastEnforced=false
        }}
        content()
    }}
    if(state!=null) HazeDialog(state,onDismissRequest,properties,body)
    else Dialog(onDismissRequest,properties,body)
}

private fun Context.wellnessActivity(): Activity? {
    var current: Context = this
    repeat(16) {
        if(current is Activity) return current as Activity
        val next=(current as? ContextWrapper)?.baseContext ?: return null
        if(next===current) return null
        current=next
    }
    return null
}

/** Fullscreen editors fill their own system-bar regions with the current surface. */
@Composable fun WellnessFullScreenDialog(onDismissRequest:()->Unit,content:@Composable ()->Unit) {
    WellnessBackdropDialog(onDismissRequest,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        val view=LocalView.current
        val window=(view.parent as? DialogWindowProvider)?.window
        val light=CardSurface.luminance()>.5f
        SideEffect {window?.let {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(it,false)
            androidx.core.view.WindowCompat.getInsetsController(it,it.decorView).apply {
                isAppearanceLightStatusBars=light;isAppearanceLightNavigationBars=light
            }
            if(Build.VERSION.SDK_INT>=29) it.isNavigationBarContrastEnforced=false
        }}
        Surface(color=Color.Transparent,modifier=Modifier.fillMaxSize().glassBackdrop(dialog=true)) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {content()}
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun WellnessBottomSheet(
    onDismissRequest: () -> Unit, modifier: Modifier = Modifier,
    containerColor: Color = CardSurface,
    shape: Shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    WellnessGlassBottomSheet(null,onDismissRequest,modifier=modifier,shape=shape,floating=false,content=content)
}

enum class CeramicScene { Welcome, Diary, History, Plan }

data class WellnessFeedback(val message: String, val error: Boolean = false)

/** Stable state-keyed transition; announcements expose the message, never secrets. */
@Composable fun FeedbackPanel(feedback: WellnessFeedback?) {
    AnimatedContent(feedback,transitionSpec={fadeIn(tween(WellnessMotion.enter)) togetherWith fadeOut(tween(WellnessMotion.exit))},label="feedback state") { value ->
        if(value!=null) Surface(color=Color.Transparent,
            shape=RoundedCornerShape(WellnessSpace.control),
            modifier=Modifier.fillMaxWidth().clip(RoundedCornerShape(WellnessSpace.control))
                .glassBackdrop(material=WellnessGlassMaterial.Field).semantics {liveRegion=LiveRegionMode.Polite},
            border=BorderStroke(1.dp,if(value.error) ErrorColor.copy(alpha=.5f) else BorderSoft)) {
            Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Icon(if(value.error) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle,null,
                    tint=if(value.error) ErrorColor else SuccessColor,modifier=Modifier.size(24.dp))
                Text(value.message,style=MaterialTheme.typography.bodyMedium,
                    color=if(value.error) ErrorColor else TextPrimary)
            }
        }
    }
}

/** Lightweight native decoration, never used as a photo of a diary record. */
@Composable fun CeramicIllustration(scene: CeramicScene, modifier: Modifier = Modifier) {
    val ceramic = CardSurface
    val linen = SecondarySurface
    val edge = BorderSoft
    val glaze = GlassEdge
    val caramel = Caramel
    val leaf = CarbColor
    Canvas(modifier.clearAndSetSemantics {}) {
        val w = size.width
        val h = size.height
        val r = minOf(w * .30f, h * .44f)
        val center = Offset(w * .52f, h * .48f)
        drawRoundRect(linen, Offset(w * .16f, h * .28f), Size(w * .68f, h * .60f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(24.dp.toPx()))
        if (scene == CeramicScene.History) {
            drawCircle(edge, r, center + Offset(-12.dp.toPx(), 12.dp.toPx()))
            drawCircle(ceramic, r, center + Offset(-8.dp.toPx(), 8.dp.toPx()))
        }
        drawCircle(edge.copy(alpha = .6f), r, center + Offset(0f, 5.dp.toPx()))
        drawCircle(ceramic, r, center)
        drawCircle(glaze, r - 2.dp.toPx(), center, style = Stroke(2.dp.toPx()))
        drawCircle(edge, r * .72f, center, style = Stroke(1.dp.toPx()))
        drawCircle(caramel.copy(alpha = .12f), r * .67f, center)
        if (scene == CeramicScene.Welcome) {
            drawOval(leaf, center - Offset(r * .25f, r * .30f), Size(r * .70f, r * .48f))
            drawOval(leaf.copy(alpha = .65f), center - Offset(r * .48f, r * .05f), Size(r * .65f, r * .45f))
            repeat(5) { i ->
                val x = center.x + (i % 3 - 1) * r * .30f
                val y = center.y + r * .12f + (i / 3) * r * .27f
                drawCircle(caramel, r * .13f, Offset(x, y))
                drawCircle(glaze.copy(alpha = .45f), r * .04f, Offset(x - r * .03f, y - r * .04f))
            }
        } else {
            // Empty plate: no invented meal or nutrition.
            drawArc(caramel.copy(alpha = .6f), 25f, 70f, false,
                center - Offset(r * .44f, r * .44f), Size(r * .88f, r * .88f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        }
        if (scene == CeramicScene.Plan) drawRoundRect(caramel, Offset(w * .85f, h * .25f),
            Size(5.dp.toPx(), h * .5f), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
    }
}
