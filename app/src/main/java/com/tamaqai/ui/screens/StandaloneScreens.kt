package com.tamaqai.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tamaqai.R
import com.tamaqai.data.AppContainer
import com.tamaqai.data.standalone.StandaloneDemoController
import com.tamaqai.ui.components.*
import com.tamaqai.ui.theme.*
import kotlinx.coroutines.*
import java.time.LocalDate

@Composable fun StandaloneEntry(container:AppContainer,content:@Composable ()->Unit) {
    val demo=checkNotNull(container.standalone)
    val started by demo.started.collectAsStateWithLifecycle()
    var ready by remember(container) {mutableStateOf(false)}
    var failed by remember(container) {mutableStateOf(false)}
    LaunchedEffect(container) {
        try {container.ready.await();ready=true} catch(c:CancellationException) {throw c} catch(_:Exception) {failed=true}
    }
    if(ready && started) content() else WellnessBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement=Arrangement.spacedBy(20.dp)) {
            Text("TamaqAI",style=MaterialTheme.typography.displaySmall)
            Text(stringResource(R.string.standalone_welcome),style=MaterialTheme.typography.headlineLarge)
            WellnessCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.standalone_local_only))
                Text(stringResource(R.string.standalone_fake_ai),color=TextSecondary)
                Text(stringResource(R.string.standalone_infrastructure),style=MaterialTheme.typography.bodySmall,color=TextSecondary)
            }
            if(!ready && !failed) WellnessLoadingIndicator()
            if(failed) Text(stringResource(R.string.standalone_reset_error),color=MaterialTheme.colorScheme.error)
            PrimaryButton(stringResource(R.string.standalone_try),{demo.start()},enabled=ready,modifier=Modifier.testTag("standalone-start"))
            LanguageOptions()
        }
    }
}

@Composable fun StandaloneInfrastructureScreen(onBack:()->Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        BackButton(onBack)
        Text(stringResource(R.string.standalone_infrastructure),style=MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.standalone_manual_photo_hint),color=TextSecondary)
    }
}

@Composable fun StandaloneExamplesScreen(demo:StandaloneDemoController,date:LocalDate,onBack:()->Unit,onOpen:(String)->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var busy by remember {mutableStateOf(false)}
    var failed by remember {mutableStateOf(false)}
    var preparedUid by rememberSaveable {mutableStateOf<String?>(null)}
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        BackButton(onBack)
        Text(stringResource(R.string.standalone_example),style=MaterialTheme.typography.headlineLarge)
        Text(stringResource(R.string.standalone_example_hint),color=TextSecondary)
        demo.samples().forEach {sample ->
            WellnessCard(Modifier.fillMaxWidth()) {
                val bitmap by produceState<android.graphics.Bitmap?>(null,sample.assetPath) {
                    value=withContext(Dispatchers.IO) {context.assets.open(sample.assetPath).use {BitmapFactory.decodeStream(it)}}
                }
                bitmap?.let {Image(it.asImageBitmap(),sample.name,Modifier.fillMaxWidth().height(210.dp),contentScale=ContentScale.Crop)}
                Text(sample.name,style=MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.standalone_sample_explanation),style=MaterialTheme.typography.bodySmall,color=TextSecondary)
                PrimaryButton(stringResource(R.string.standalone_try),{
                    if(!busy) {busy=true;failed=false;scope.launch {
                        try {
                            val uid=preparedUid ?: demo.createSample(sample.id,date).also {preparedUid=it}
                            withContext(Dispatchers.Main.immediate) {onOpen(uid)}
                        }
                        catch(c:CancellationException) {throw c}
                        catch(error:Exception) {
                            // Classes and locations only; no submitted values or image content.
                            android.util.Log.e("TamaqStandalone",error.javaClass.simpleName+"\n"+error.stackTrace.take(12).joinToString("\n"))
                            failed=true
                        }
                        finally {busy=false}
                    }}
                },enabled=!busy,modifier=Modifier.testTag("standalone-sample-"+sample.id))
            }
        }
        if(busy) WellnessLoadingIndicator()
        if(failed) Text(stringResource(R.string.standalone_example_error),color=MaterialTheme.colorScheme.error)
    }
}
