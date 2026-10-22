package com.tamaqai.ui.components

import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.tamaqai.R
import com.tamaqai.ui.theme.*
import dev.chrisbanes.haze.HazeDialog

val LocalWellnessSheetClose=staticCompositionLocalOf<(() -> Unit)?> {null}

/** A modal, source-backed sheet. HazeDialog keeps the source in the other window fresh. */
@Composable fun WellnessGlassBottomSheet(
    title: String?,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(WellnessSpace.card),
    floating: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val haze=LocalWellnessOverlayBackdrop.current ?: LocalWellnessBackdrop.current
    val token=rememberWellnessModalToken()
    val visible=remember {MutableTransitionState(false)}
    var opened by remember {mutableStateOf(false)}
    val dismiss by rememberUpdatedState(onDismissRequest)
    LaunchedEffect(Unit) {visible.targetState=true;opened=true}
    LaunchedEffect(visible.isIdle,visible.currentState,visible.targetState,opened) {
        if(opened && visible.isIdle && !visible.currentState && !visible.targetState) dismiss()
    }
    val close={visible.targetState=false}
    val dialogContent:@Composable ()->Unit={CompositionLocalProvider(LocalWellnessModalToken provides token) {
        val view=LocalView.current
        val window=(view.parent as? DialogWindowProvider)?.window
        val light=PageBackground.luminance()>.5f
        val closeLabel=stringResource(R.string.close)
        SideEffect {window?.let {
            // The single controlled scrim below must not be doubled by Android's dim layer.
            it.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            it.setDimAmount(0f)
            WindowCompat.setDecorFitsSystemWindows(it,false)
            WindowCompat.getInsetsController(it,it.decorView).apply {
                isAppearanceLightStatusBars=light;isAppearanceLightNavigationBars=light
            }
            if(Build.VERSION.SDK_INT>=29) it.isNavigationBarContrastEnforced=false
        }}
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val sheetMaximum=maxHeight*.87f
            AnimatedVisibility(visible,enter=fadeIn(tween(WellnessMotion.enter)),exit=fadeOut(tween(WellnessMotion.exit))) {
                Box(Modifier.fillMaxSize().background(ShadowColor.copy(alpha=WellnessGlass.scrimOpacity))
                    .testTag("nutrient-dismiss")
                    .clickable(interactionSource=remember {MutableInteractionSource()},indication=null,onClick=close)
                    .semantics {contentDescription=closeLabel;isTraversalGroup=true;traversalIndex=1f})
            }
            AnimatedVisibility(visible,modifier=Modifier.align(Alignment.BottomCenter),
                enter=slideInVertically(tween(WellnessMotion.enter)) {it}+fadeIn(tween(WellnessMotion.enter)),
                exit=slideOutVertically(tween(WellnessMotion.exit)) {it}+fadeOut(tween(WellnessMotion.exit))) {
                Column(modifier.navigationBarsPadding().imePadding()
                    .then(if(floating) Modifier.padding(horizontal=12.dp,vertical=12.dp) else Modifier)
                    .heightIn(max=sheetMaximum).fillMaxWidth()
                    .shadow(WellnessGlass.shadow,shape).clip(shape).glassBackdrop(dialog=true)
                    .border(.5.dp,GlassEdge.copy(alpha=.4f),shape)
                    .testTag("nutrient-glass-sheet").semantics {paneTitle=title?:closeLabel;isTraversalGroup=true;traversalIndex=0f;dismiss {close();true}}) {
                    var drag by remember {mutableFloatStateOf(0f)}
                    val threshold=with(LocalDensity.current) {48.dp.toPx()}
                    Row(Modifier.fillMaxWidth().pointerInput(threshold) {
                        detectVerticalDragGestures(onDragStart={drag=0f},onDragEnd={if(drag>threshold) close()},
                            onVerticalDrag={change,amount->change.consume();drag+=amount})
                    }.padding(start=20.dp,end=8.dp,top=8.dp,bottom=4.dp),
                        verticalAlignment=Alignment.CenterVertically) {
                        if(title!=null) Text(title,Modifier.weight(1f).semantics {heading()},style=MaterialTheme.typography.headlineSmall)
                        else Spacer(Modifier.weight(1f))
                        IconButton(close,Modifier.size(48.dp).testTag("nutrient-close")) {Icon(Icons.Outlined.Close,closeLabel)}
                    }
                    CompositionLocalProvider(LocalWellnessSheetClose provides close) {content()}
                }
            }
        }
    }}
    val properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)
    if(haze!=null) HazeDialog(haze,close,properties,dialogContent)
    else Dialog(close,properties,dialogContent)
}
