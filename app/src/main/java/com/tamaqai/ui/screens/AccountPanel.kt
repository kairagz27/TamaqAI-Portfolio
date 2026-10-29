package com.tamaqai.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import com.tamaqai.MainActivity
import com.tamaqai.R
import com.tamaqai.TamaqApplication
import com.tamaqai.data.remote.*
import com.tamaqai.ui.components.*
import com.tamaqai.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.tamaqai.domain.usecase.AccountValidation

enum class AccountForm { Profile, Edit, Login, Register, Forgot, Reset, Verify, Delete, Link }

@Composable fun AccountPanel(initialForm:AccountForm=AccountForm.Profile,onBack:()->Unit={},onPhone:()->Unit={}) {
    val context=LocalContext.current
    val container=(context.applicationContext as TamaqApplication).container
    val standalone=container.standalone!=null
    // Offline demos have a local profile, never a simulated server account.
    if(standalone && initialForm !in listOf(AccountForm.Profile,AccountForm.Edit)) {
        WellnessCard(Modifier.fillMaxWidth()) {
            BackButton(onBack)
            Text(stringResource(R.string.standalone_local_profile),style=MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.standalone_infrastructure),color=TextSecondary)
        }
        return
    }
    val repository=container.account
    val guest=container.owner=="guest"
    val profile by repository.profileState.collectAsStateWithLifecycle()
    var form by remember(repository) { mutableStateOf(initialForm) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var fieldErrors by remember { mutableStateOf<Map<String,Int>>(emptyMap()) }
    var notice by remember { mutableStateOf<Int?>(null) }
    var name by remember { mutableStateOf(profile.name) }
    var email by remember { mutableStateOf(profile.email) }
    // RAM only: retain on failure; clear after success, form change or disposal.
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var height by remember { mutableStateOf(profile.height) }
    var current by remember { mutableStateOf(profile.currentWeight) }
    var target by remember { mutableStateOf(profile.targetWeight) }
    val scope=rememberCoroutineScope()
    var avatarActions by remember {mutableStateOf(false)}
    fun show(next: AccountForm) { if (!busy && (!standalone || next in listOf(AccountForm.Profile,AccountForm.Edit))) { password="";confirm="";code="";invite="";visible=false;error=null;fieldErrors=emptyMap();notice=null;form=next } }
    fun execute(work: suspend ()->Unit) {
        if (busy) return
        busy=true;error=null;fieldErrors=emptyMap();notice=null
        scope.launch {
            try { work();password="";confirm="";code="";visible=false }
            catch(c:CancellationException) { throw c }
            catch(e:Exception) {
                fieldErrors=if(e is ApiFailure && e.status==422) e.fields.mapValues { (field,_) -> accountFieldError(field,form) } else emptyMap()
                error=if(fieldErrors.isEmpty()) accountError(e) else null
            }
            finally { busy=false }
        }
    }
    fun changed() { (context as MainActivity).identityChanged() }
    val avatarPicker=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {uri ->
        if(uri!=null) execute {
            val image=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val temporary=java.io.File.createTempFile("avatar_",".image",context.cacheDir)
                try {
                    context.contentResolver.openInputStream(uri)?.use {input -> temporary.outputStream().use {output ->
                        val buffer=ByteArray(8192);var total=0
                        while(true) {val count=input.read(buffer);if(count<0) break;total+=count;require(total<=20*1024*1024);output.write(buffer,0,count)}
                    }} ?: error("invalid_avatar")
                    PhotoProcessor.downsampleToJpeg(temporary)
                } finally {temporary.delete()}
            }
            repository.uploadAvatar(image);notice=R.string.profile_saved
        }
    }
    LaunchedEffect(repository) {
        if(initialForm==AccountForm.Verify && repository.deliveryFailed) error=R.string.email_delivery_failed
        try { repository.load() } catch(c:CancellationException) { throw c } catch(e:Exception) { error=accountError(e) }
    }
    androidx.activity.compose.BackHandler(form!=AccountForm.Profile) { if (!busy) {if(initialForm!=AccountForm.Profile) onBack() else show(AccountForm.Profile)} }
    AnimatedContent(form,transitionSpec={fadeIn(tween(WellnessMotion.enter)) togetherWith fadeOut(tween(WellnessMotion.exit))},label="account form") { selected ->
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        if(selected==AccountForm.Profile) {
            WellnessCard(Modifier.fillMaxWidth(),emphasized=true) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                    ProfileAvatar(profile)
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(profile.name.ifBlank { stringResource(if(guest) R.string.account_guest else R.string.account_title) },style=MaterialTheme.typography.titleLarge.copy(fontFamily=BodyFont))
                        Text(if(standalone) stringResource(R.string.standalone_local_profile) else if(guest) stringResource(R.string.account_guest) else profile.email.ifBlank {profile.phone},style=MaterialTheme.typography.bodySmall,color=TextSecondary)
                        if(!standalone && !guest && profile.contactVerified) Text(stringResource(R.string.contact_verified),style=MaterialTheme.typography.labelSmall,color=SuccessColor)
                    }
                }
                Text(stringResource(if(standalone) R.string.standalone_local_only else if(guest) R.string.guest_diary_hint else R.string.account_local_diary_hint),style=MaterialTheme.typography.bodySmall,color=TextSecondary)
                WellnessTextButton({ name=profile.name;height=profile.height;current=profile.currentWeight;target=profile.targetWeight;show(AccountForm.Edit) }) { Text(stringResource(R.string.edit_profile)) }
                if(!standalone && !guest) WellnessOutlinedButton({avatarActions=true},enabled=!busy) {Text(stringResource(R.string.edit_avatar))}
                if(standalone) {
                    Text(stringResource(R.string.standalone_infrastructure),style=MaterialTheme.typography.bodySmall,color=TextSecondary)
                } else if(guest) {
                    PrimaryButton(stringResource(R.string.account_login),{show(AccountForm.Login)},enabled=!busy)
                    WellnessOutlinedButton({show(AccountForm.Register)},Modifier.fillMaxWidth(),enabled=!busy) {Text(stringResource(R.string.account_register))}
                    WellnessTextButton(onPhone,enabled=!busy) {Text(stringResource(R.string.phone_method))}
                } else {
                    if(container.sessions.sessions.access.isBlank()) PrimaryButton(stringResource(R.string.account_login_again),{show(AccountForm.Login)},enabled=!busy)
                    if(profile.email.isNotBlank() && !profile.verified) {
                        Text(stringResource(R.string.email_unverified),color=WarningColor)
                        WellnessOutlinedButton({show(AccountForm.Verify)},Modifier.fillMaxWidth(),enabled=!busy) {Text(stringResource(R.string.verify_email))}
                    }
                    val pending=container.sessions.sessions.snapshot().optString("link_user")==container.owner
                    if(!profile.pilotLinked || pending) WellnessOutlinedButton({show(AccountForm.Link)},Modifier.fillMaxWidth(),enabled=!busy && profile.contactVerified) {Text(stringResource(if(pending) R.string.finish_link else R.string.link_guest))}
                    WellnessTextButton({execute { val revoked=repository.logout(); if(!revoked) android.widget.Toast.makeText(context,R.string.logout_local_only,android.widget.Toast.LENGTH_LONG).show(); changed() }},enabled=!busy) {Text(stringResource(R.string.account_logout))}
                    WellnessTextButton({show(AccountForm.Delete)},enabled=!busy) {Text(stringResource(R.string.delete_account),color=MaterialTheme.colorScheme.error)}
                }
                Row(horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.weight(1f)) {Text(stringResource(R.string.profile_height),style=MaterialTheme.typography.labelSmall,color=TextSecondary);Text(if(profile.height.isBlank()) "—" else stringResource(R.string.centimeters,com.tamaqai.ui.displayNumber(profile.height)))}
                    Column(Modifier.weight(1f)) {Text(stringResource(R.string.profile_weight),style=MaterialTheme.typography.labelSmall,color=TextSecondary);Text(if(profile.currentWeight.isBlank()) "—" else stringResource(R.string.kilograms,com.tamaqai.ui.displayNumber(profile.currentWeight)))}
                }
                Text(stringResource(R.string.profile_target_weight)+": "+com.tamaqai.ui.displayNumber(profile.targetWeight).ifBlank { "—" },style=MaterialTheme.typography.bodySmall,color=TextSecondary)
            }
        } else {
            WellnessCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    IconButton({if(initialForm!=AccountForm.Profile) onBack() else show(AccountForm.Profile)},modifier=Modifier.size(48.dp),enabled=!busy) {Icon(Icons.AutoMirrored.Outlined.ArrowBack,stringResource(R.string.back))}
                    Text(stringResource(when(selected) {
                        AccountForm.Login->R.string.account_login;AccountForm.Register->R.string.account_register;AccountForm.Edit->R.string.edit_profile
                        AccountForm.Forgot->R.string.forgot_password;AccountForm.Reset->R.string.reset_password;AccountForm.Verify->R.string.verify_email
                        AccountForm.Link->R.string.link_guest;else->R.string.delete_account
                    }),style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
                }
                if(selected==AccountForm.Edit || selected==AccountForm.Register)
                    WellnessTextField(name,{name=it;fieldErrors=fieldErrors-"name"},label={Text(stringResource(R.string.profile_name))},enabled=!busy,modifier=Modifier.fillMaxWidth(),singleLine=true,
                        isError=fieldErrors.containsKey("name") || selected==AccountForm.Register && name.isNotEmpty() && !AccountValidation.name(name),
                        supportingText=if(selected==AccountForm.Register || fieldErrors.containsKey("name")) ({Text(stringResource(fieldErrors["name"] ?: R.string.name_requirement))}) else null,colors = wellnessFieldColors())
                if(selected in listOf(AccountForm.Login,AccountForm.Register,AccountForm.Forgot))
                    WellnessTextField(email,{email=it;fieldErrors=fieldErrors-"email"},label={Text(stringResource(R.string.account_email))},enabled=!busy,modifier=Modifier.fillMaxWidth(),singleLine=true,
                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email),isError=fieldErrors.containsKey("email") || email.isNotEmpty() && !AccountValidation.email(email),
                        supportingText=({Text(stringResource(fieldErrors["email"] ?: if(email.isNotEmpty() && !AccountValidation.email(email)) R.string.email_format_error else R.string.email_requirement))}),colors = wellnessFieldColors())
                if(selected in listOf(AccountForm.Login,AccountForm.Register,AccountForm.Reset) || selected==AccountForm.Delete && !profile.phoneVerified) {
                    val toggle=stringResource(if(visible) R.string.hide_password else R.string.show_password)
                    WellnessTextField(password,{password=it;fieldErrors=fieldErrors-"password"},label={Text(stringResource(R.string.account_password))},enabled=!busy,modifier=Modifier.fillMaxWidth(),singleLine=true,
                        isError=fieldErrors.containsKey("password") || selected in listOf(AccountForm.Register,AccountForm.Reset) && password.isNotEmpty() && !AccountValidation.registrationPassword(password),
                        supportingText=fieldErrors["password"]?.let {id -> ({Text(stringResource(id))})},
                        visualTransformation=if(visible) VisualTransformation.None else PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),
                        trailingIcon={IconButton({visible=!visible},modifier=Modifier.size(48.dp),enabled=!busy) {Icon(if(visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,toggle)}}, colors = wellnessFieldColors())
                    if(selected in listOf(AccountForm.Register,AccountForm.Reset)) {
                        Text(stringResource(R.string.password_live,AccountValidation.passwordLength(password)),style=MaterialTheme.typography.bodySmall,color=if(AccountValidation.registrationPassword(password)) SuccessColor else TextSecondary)
                        WellnessTextField(confirm,{confirm=it},label={Text(stringResource(R.string.confirm_password))},enabled=!busy,modifier=Modifier.fillMaxWidth(),singleLine=true,
                            supportingText=({Text(stringResource(if(confirm.isNotEmpty() && confirm!=password) R.string.password_mismatch else R.string.confirm_requirement))}),
                            visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),isError=confirm.isNotEmpty() && confirm!=password, colors = wellnessFieldColors())
                    }
                }
                if(selected==AccountForm.Verify || selected==AccountForm.Reset) WellnessTextField(code,{code=it;fieldErrors=fieldErrors-"token"},label={Text(stringResource(R.string.email_code))},enabled=!busy,modifier=Modifier.fillMaxWidth(),singleLine=true,
                    isError=fieldErrors.containsKey("token"),supportingText=fieldErrors["token"]?.let {id -> ({Text(stringResource(id))})}, colors = wellnessFieldColors())
                if(selected==AccountForm.Edit) {
                    listOf(Triple(height,R.string.profile_height,{v:String->height=v}),Triple(current,R.string.profile_weight,{v:String->current=v}),Triple(target,R.string.profile_target_weight,{v:String->target=v})).forEach { (value,label,update) ->
                        WellnessTextField(value,update,label={Text(stringResource(label))},enabled=!busy,modifier=Modifier.fillMaxWidth(),singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal), colors = wellnessFieldColors())
                    }
                }
                if(selected==AccountForm.Link) {
                    Text(stringResource(R.string.link_guest_warning),style=MaterialTheme.typography.bodyMedium)
                    if(container.sessions.sessions.guestToken.isBlank() && container.sessions.sessions.snapshot().optString("link_operation").isBlank())
                        WellnessTextField(invite,{invite=it},label={Text(stringResource(R.string.invite))},enabled=!busy,modifier=Modifier.fillMaxWidth(),visualTransformation=PasswordVisualTransformation(),singleLine=true, colors = wellnessFieldColors())
                }
                if(selected==AccountForm.Delete) Text(stringResource(R.string.delete_account_warning),color=TextSecondary)
                val valid=when(selected) {
                    AccountForm.Login->AccountValidation.email(email) && password.isNotEmpty() && AccountValidation.passwordLength(password)<=128
                    AccountForm.Register->AccountValidation.name(name) && AccountValidation.email(email) && AccountValidation.registrationPassword(password) && password==confirm
                    AccountForm.Forgot->AccountValidation.email(email)
                    AccountForm.Reset->code.trim().length>=32 && AccountValidation.registrationPassword(password) && password==confirm
                    AccountForm.Verify->code.trim().length>=32
                    AccountForm.Delete->password.isNotEmpty() || profile.phoneVerified
                    AccountForm.Link->profile.contactVerified
                    else->true
                }
                PrimaryButton(stringResource(if(selected==AccountForm.Delete) R.string.delete_account else R.string.continue_action),{
                    // Capture an immutable request before launching the coroutine.
                    val secret=password;val emailCode=code;val submittedEmail=email;val submittedName=name
                    execute {
                        when(selected) {
                            AccountForm.Login->{repository.login(submittedEmail,secret);changed()}
                            AccountForm.Register->{repository.register(submittedEmail,secret,submittedName);changed()}
                            AccountForm.Forgot->{repository.forgot(submittedEmail);form=AccountForm.Reset;notice=R.string.email_sent_hint}
                            AccountForm.Reset->{repository.reset(emailCode,secret);form=AccountForm.Login;notice=R.string.password_reset_done}
                            AccountForm.Verify->{repository.verify(emailCode);repository.load();form=AccountForm.Profile;notice=R.string.email_verified_done}
                            AccountForm.Edit->{repository.save(profile.copy(name=name,height=height,currentWeight=current,targetWeight=target));form=AccountForm.Profile;notice=R.string.profile_saved}
                            AccountForm.Link->{repository.linkGuest(invite.trim());changed()}
                            AccountForm.Delete->{repository.delete(secret);changed()}
                            else->Unit
                        }
                    }
                },enabled=!busy && valid)
                if(selected==AccountForm.Register && !valid && !busy) Text(stringResource(R.string.missing_registration_fields),style=MaterialTheme.typography.bodySmall,color=TextSecondary)
                if(selected==AccountForm.Login) {
                    WellnessTextButton({show(AccountForm.Register)},enabled=!busy) {Text(stringResource(R.string.account_register))}
                    WellnessTextButton({show(AccountForm.Forgot)},enabled=!busy) {Text(stringResource(R.string.forgot_password))}
                }
                if(selected==AccountForm.Verify) WellnessTextButton({execute {repository.resend();notice=R.string.email_sent_hint}},enabled=!busy) {Text(stringResource(R.string.resend_email))}
            }
        }
        if(busy) Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {WellnessLoadingIndicator(Modifier.size(24.dp));Text(stringResource(R.string.account_working))}
        FeedbackPanel(error?.let {WellnessFeedback(stringResource(it),error=true)}
            ?: notice?.let {WellnessFeedback(stringResource(it))})
    }
    }
    if(avatarActions) WellnessDialog(onDismissRequest={avatarActions=false},title={Text(stringResource(R.string.edit_avatar))},
        text={Column {
            WellnessTextButton({avatarActions=false;avatarPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))}) {Text(stringResource(if(profile.avatarVersion.isBlank()) R.string.choose_avatar else R.string.replace_avatar))}
            if(profile.avatarVersion.isNotBlank()) WellnessTextButton({avatarActions=false;execute {repository.removeAvatar();notice=R.string.profile_saved}}) {Text(stringResource(R.string.remove_avatar))}
        }},confirmButton={WellnessTextButton({avatarActions=false}) {Text(stringResource(R.string.cancel))}})
}

private fun accountFieldError(field:String,form:AccountForm):Int=when(field) {
    "name"->R.string.name_requirement
    "email"->R.string.email_format_error
    "password"->if(form in listOf(AccountForm.Register,AccountForm.Reset)) R.string.password_requirement else R.string.login_password_requirement
    "token"->R.string.invalid_email_code
    else->R.string.account_invalid_input
}

fun accountError(error:Exception):Int=when(error.message) {
    "standalone_infrastructure_unavailable"->R.string.standalone_infrastructure
    "backend_not_configured"->R.string.auth_backend_not_configured
    "backend_https_required"->R.string.ui_error_016
    "sms_unavailable","sms_delivery_unavailable"->R.string.sms_unavailable
    "invalid_phone"->R.string.invalid_phone
    "sms_cooldown"->R.string.sms_cooldown
    "invalid_sms_code"->R.string.invalid_sms_code
    "invalid_avatar","avatar_too_large","avatar_not_found"->R.string.avatar_error
    "invalid_credentials"->R.string.invalid_account_credentials
    "registration_credentials_conflict"->R.string.registration_credentials_conflict
    "session_expired","unauthorized","token_expired"->R.string.account_expired
    "auth_rate_limit"->R.string.account_rate_limited
    "invalid_input"->R.string.account_invalid_input
    "invalid_email_code"->R.string.invalid_email_code
    "email_delivery_unavailable"->R.string.email_delivery_failed
    "reauthentication_required"->R.string.reauthentication_required
    "link_conflict","local_link_conflict","planning_link_conflict"->R.string.link_conflict
    "invalid_guest_session","invite_required","invitation_already_linked"->R.string.link_invitation_error
    else->when {
        error is ApiFailure && error.status==429->R.string.account_rate_limited
        error is ApiFailure && error.status>=500->R.string.account_server_error
        error is IllegalArgumentException->R.string.account_invalid_input
        else->R.string.account_connection_error
    }
}
