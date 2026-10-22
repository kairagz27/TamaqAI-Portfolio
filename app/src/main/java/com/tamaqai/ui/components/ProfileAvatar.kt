package com.tamaqai.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tamaqai.R
import com.tamaqai.data.remote.AccountProfile
import com.tamaqai.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun ProfileAvatar(profile:AccountProfile,size:Dp=64.dp) {
    val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null,profile.avatarFile,profile.avatarVersion) {
        value=if(profile.avatarFile.isBlank()) null else withContext(Dispatchers.IO) {
            runCatching {BitmapFactory.decodeFile(profile.avatarFile)?.asImageBitmap()}.getOrNull()
        }
    }
    Box(Modifier.size(size).clip(CircleShape).background(SelectedSurface),contentAlignment=Alignment.Center) {
        if(image!=null) Image(image!!,stringResource(R.string.avatar),Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
        else Text(profile.name.trim().split(' ').filter(String::isNotBlank).take(2).mapNotNull {it.firstOrNull()}.joinToString("").uppercase().ifBlank {"T"},
            style=MaterialTheme.typography.headlineSmall.copy(fontFamily=BodyFont),color=AccentBrown)
    }
}
