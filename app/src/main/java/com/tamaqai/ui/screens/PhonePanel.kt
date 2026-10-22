package com.tamaqai.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tamaqai.MainActivity
import com.tamaqai.TamaqApplication
import com.tamaqai.R
import com.tamaqai.ui.components.*
import com.tamaqai.ui.theme.*
import kotlinx.coroutines.*

@Composable fun PhonePanel(onBack:()->Unit) {
    val context=LocalContext.current
    val repository=(context.applicationContext as TamaqApplication).container.account
    var available by remember {mutableStateOf<Boolean?>(null)}
    var development by remember {mutableStateOf(false)}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<Int?>(null)}
    var number by remember {mutableStateOf("")}
    var name by remember {mutableStateOf("")}
    var code by remember {mutableStateOf("")}
    var region by remember {mutableStateOf("KZ")}
    var countryMenu by remember {mutableStateOf(false)}
    var challenge by remember {mutableStateOf("")}
    var resendAt by remember {mutableLongStateOf(0L)}
    var remaining by remember {mutableIntStateOf(0)}
    val scope=rememberCoroutineScope()
    fun execute(work:suspend ()->Unit) {if(!busy) {busy=true;error=null;scope.launch {
        try {work()} catch(c:CancellationException) {throw c} catch(e:Exception) {error=accountError(e)} finally {busy=false}
    }}}
    LaunchedEffect(repository) {execute {val methods=repository.methods();available=methods.getBoolean("phone");development=methods.optBoolean("sms_development")}}
    LaunchedEffect(resendAt) {while(true) {remaining=((resendAt-android.os.SystemClock.elapsedRealtime()+999)/1000).toInt().coerceAtLeast(0);if(remaining==0) break;delay(250)}}
    val countries=listOf("KZ" to R.string.country_kz,"RU" to R.string.country_ru,"US" to R.string.country_us,"GB" to R.string.country_gb,"DE" to R.string.country_de,"TR" to R.string.country_tr,"UZ" to R.string.country_uz,"KG" to R.string.country_kg)
    WellnessCard(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.phone_title),style=MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.phone_hint),color=TextSecondary)
        if(available==false) Text(stringResource(R.string.sms_unavailable),color=WarningColor)
        if(development) Text(stringResource(R.string.sms_development),color=WarningColor)
        Box {
            WellnessOutlinedButton({countryMenu=true},enabled=!busy && challenge.isBlank()) {Text(stringResource(countries.first {it.first==region}.second))}
            DropdownMenu(expanded=countryMenu,onDismissRequest={countryMenu=false}) {
                countries.forEach {(tag,label)->DropdownMenuItem(text={Text(stringResource(label))},onClick={region=tag;countryMenu=false})}
            }
        }
        WellnessTextField(number,{number=it.take(32)},label={Text(stringResource(R.string.phone_number))},singleLine=true,
            enabled=!busy && challenge.isBlank(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Phone),modifier=Modifier.fillMaxWidth(),colors=wellnessFieldColors())
        if(challenge.isNotBlank()) {
            WellnessTextField(name,{name=it.take(80)},label={Text(stringResource(R.string.profile_name))},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy,colors=wellnessFieldColors())
            WellnessTextField(code,{code=it.filter(Char::isDigit).take(6)},label={Text(stringResource(R.string.sms_code))},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy,
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),colors=wellnessFieldColors())
            Text(stringResource(R.string.sms_expires),style=MaterialTheme.typography.bodySmall,color=TextSecondary)
            PrimaryButton(stringResource(R.string.sms_confirm),{val submitted=code;code="";execute {repository.verifyPhone(challenge,submitted,name);(context as MainActivity).identityChanged()}},enabled=!busy && code.length==6)
        }
        PrimaryButton(stringResource(if(remaining>0) R.string.sms_wait else if(challenge.isBlank()) R.string.send_sms else R.string.sms_resend,remaining),{
            code="";execute {val response=repository.requestPhone(number,region);challenge=response.getString("challenge_id");resendAt=android.os.SystemClock.elapsedRealtime()+response.getInt("resend_after")*1000L}
        },enabled=!busy && available==true && remaining==0 && number.length>=3)
        if(available==null && !busy) WellnessTextButton({execute {available=repository.methods().getBoolean("phone")}}) {Text(stringResource(R.string.retry))}
        if(busy) WellnessLoadingIndicator(Modifier.size(24.dp))
        FeedbackPanel(error?.let {WellnessFeedback(stringResource(it),error=true)})
        WellnessTextButton(onBack,enabled=!busy) {Text(stringResource(R.string.back))}
    }
}
