package com.tamaqai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.*
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.*
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.tamaqai.ui.TamaqViewModelFactory
import com.tamaqai.ui.screens.*
import com.tamaqai.ui.theme.TamaqTheme
import com.tamaqai.ui.components.*
import com.tamaqai.ui.theme.*
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.luminance
import dev.chrisbanes.haze.hazeSource
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavBackStackEntry
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private var identityRecreation=false
    override fun onCreate(savedInstanceState: Bundle?) {
        val reset = intent.getBooleanExtra("identity_changed",false)
        intent.removeExtra("identity_changed")
        super.onCreate(if (reset) null else savedInstanceState)
        val initialDark = when ((application as TamaqApplication).preferences.theme.value) {
            com.tamaqai.data.local.ThemeMode.Dark -> true
            com.tamaqai.data.local.ThemeMode.Light -> false
            com.tamaqai.data.local.ThemeMode.System -> resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        val bars = if (initialDark) androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            else androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        setContent { TamaqTheme {
            if ((application as TamaqApplication).container.standalone!=null || BuildConfig.PORTFOLIO_DEMO) Column(Modifier.fillMaxSize().background(PageBackground)) {
                Surface(color=MaterialTheme.colorScheme.primary, contentColor=MaterialTheme.colorScheme.onPrimary) {
                    Text(if((application as TamaqApplication).container.standalone!=null) stringResource(R.string.standalone_badge) else "PORTFOLIO DEMO · FAKE AI · LOCAL BACKEND", style=MaterialTheme.typography.labelSmall,
                        modifier=Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=12.dp,vertical=6.dp))
                }
                Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.statusBars)) {WellnessGlassHost {TamaqEntry()}}
            } else WellnessGlassHost { TamaqEntry() }
        } }
    }

    fun identityChanged() {
        if(identityRecreation || isFinishing || isDestroyed) return
        identityRecreation=true
        intent.putExtra("identity_changed",true)
        window.decorView.post {if(!isFinishing && !isDestroyed) recreate()}
    }

    override fun onDestroy() {
        // Lifecycle destruction detaches Compose/NavHost first. Manual disposal
        // during account recomposition can corrupt Compose's slot table.
        super.onDestroy()
        if(identityRecreation) {
            // A recreation retains the store; discard the old owner's models
            // only after its navigation graph has detached, before onCreate.
            viewModelStore.clear()
            (application as TamaqApplication).reloadIdentity()
        }
    }
}

@Composable private fun SystemPanels(camera:Boolean=false) {
    val activity=requireNotNull(androidx.activity.compose.LocalActivity.current)
    val light=!camera && MaterialTheme.colorScheme.background.luminance()>.5f
    val banner=(activity.application as TamaqApplication).container.standalone!=null || BuildConfig.PORTFOLIO_DEMO
    SideEffect {
        androidx.core.view.WindowCompat.getInsetsController(activity.window,activity.window.decorView).apply {
            isAppearanceLightStatusBars=light && !banner;isAppearanceLightNavigationBars=light
        }
    }
}

@Composable private fun TamaqEntry() {
    val app=LocalContext.current.applicationContext as TamaqApplication
    val activity=androidx.activity.compose.LocalActivity.current as MainActivity
    val container=app.container
    if(container.standalone!=null) {
        SystemPanels()
        StandaloneEntry(container) {TamaqRootNavigation()}
        return
    }
    val identity by container.sessions.sessions.changes.collectAsStateWithLifecycle()
    val mealFlow=remember(container) {container.mealRepository.getAllMeals()}
    val entries by mealFlow.collectAsStateWithLifecycle(initialValue=emptyList())
    val goals by container.settings.dailyGoals.collectAsStateWithLifecycle()
    var checked by remember(container) {mutableStateOf(false)}
    var restoreError by remember(container) {mutableStateOf(false)}
    var restoreRetry by remember(container) {mutableIntStateOf(0)}
    val fitness by container.nutrition.state.collectAsStateWithLifecycle()
    val profile by container.account.profileState.collectAsStateWithLifecycle()
    var continued by remember {mutableStateOf(app.preferences.guestContinued)}
    LaunchedEffect(container,restoreRetry) {
        checked=false
        container.ready.await()
        if(container.owner!="guest") restoreError=runCatching {container.account.load();container.nutrition.load()}.isFailure
        checked=true
    }
    val existingGuest=identity.pendingLink || identity.hasGuest || entries.isNotEmpty() || container.account.localProfile().name.isNotBlank() ||
        goals!=com.tamaqai.domain.model.DailyGoals()
    if(!checked || identity.owner!=container.owner) {
        SystemPanels()
        EntryContentBackdrop {
        WellnessBackground(Modifier.fillMaxSize()) {Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            WellnessLoadingIndicator();Text(stringResource(R.string.session_restoring))
        }}
        }
    } else if(identity.owner=="guest" && !continued && !existingGuest || identity.owner!="guest" && !identity.hasAccess && !identity.hasRefresh) {
        SystemPanels()
        EntryContentBackdrop {
        StartScreen {
            app.preferences.continueGuest();continued=true
            if(identity.owner!="guest") {
                container.sessions.sessions.logout()
                activity.identityChanged()
            }
        }
        }
    } else if(container.owner!="guest" && restoreError && !fitness.loaded) {
        SystemPanels()
        EntryContentBackdrop {
        WellnessBackground(Modifier.fillMaxSize()) {Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.account_connection_error));PrimaryButton(stringResource(R.string.retry),{restoreRetry++})
            WellnessTextButton({container.sessions.sessions.logout();activity.identityChanged()}) {Text(stringResource(R.string.account_logout))}
        }}
        }
    } else if(container.owner!="guest" && profile.email.isNotBlank() && !profile.contactVerified) {
        SystemPanels()
        EntryContentBackdrop {
        WellnessBackground(Modifier.fillMaxSize()) {Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.verify_required),color=TextSecondary)
            AccountPanel(initialForm=AccountForm.Verify)
            WellnessTextButton({container.sessions.sessions.logout();activity.identityChanged()}) {Text(stringResource(R.string.account_logout))}
        }}
        }
    } else if(container.owner!="guest" && fitness.loaded && !fitness.document.completed) {
        SystemPanels()
        EntryContentBackdrop {
        WellnessBackground(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {QuestionnaireScreen(onDone={},required=true)}
        }
        }
    } else TamaqRootNavigation()
}

/** Entry screens have no NavHost source; their language/account modal still needs one.
 * Keep this outside the root navigation branch so drawer/navigation never capture themselves.
 */
@Composable private fun EntryContentBackdrop(content: @Composable () -> Unit) {
    val state=LocalWellnessOverlayBackdrop.current ?: LocalWellnessBackdrop.current
    val needed=wellnessNeedsContentBackdrop()
    Box(Modifier.fillMaxSize().then(if(needed && state!=null)
        Modifier.hazeSource(state,zIndex=1f,key=WellnessBackdropKeys.Content) else Modifier)) { content() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TamaqRootNavigation() {
    val context = LocalContext.current
    val application = context.applicationContext as TamaqApplication
    val container = application.container
    if (container.owner=="guest" && container.sessions.sessions.snapshot().optString("link_user").isNotBlank()) {
        SystemPanels()
        EntryContentBackdrop {
        WellnessBackground(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.pending_link_warning),color=WarningColor)
                AccountPanel()
            }
        }
        }
        return
    }
    val factory = remember(container) { TamaqViewModelFactory(container) }
    val home: HomeViewModel = viewModel(factory = factory)
    val daily: DailyViewModel = viewModel(factory = factory)
    val scanner: ScannerViewModel = viewModel(factory = factory)
    val homeState by home.uiState.collectAsStateWithLifecycle()
    val dailyState by daily.state.collectAsStateWithLifecycle()
    val scannerState by scanner.state.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "home"
    // NavHost remembers destination lambdas; capture a live State instead of
    // the route String from the composition that first created each screen.
    val presentedRoute=rememberUpdatedState(route)
    var cameraPermission by remember { mutableStateOf(androidx.core.content.ContextCompat.checkSelfPermission(context,
        android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    DisposableEffect(lifecycle,context) {
        val observer=androidx.lifecycle.LifecycleEventObserver { _,event ->
            if(event==Lifecycle.Event.ON_RESUME) cameraPermission=androidx.core.content.ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        lifecycle.addObserver(observer)
        onDispose {lifecycle.removeObserver(observer)}
    }
    val cameraBackground = route in listOf("scanner","barcode") && (scannerState is ScannerState.Loading ||
        cameraPermission)
    SystemPanels(cameraBackground)
    val drawerState=rememberDrawerState(DrawerValue.Closed)
    val backdrop=LocalWellnessOverlayBackdrop.current ?: LocalWellnessBackdrop.current
    val overlayNeedsBackdrop=wellnessNeedsContentBackdrop()
    val drawerPresented=drawerState.isOpen || drawerState.targetValue==DrawerValue.Open
    val menuFocus=remember {FocusRequester()}
    val drawerCloseFocus=remember {FocusRequester()}
    val backgroundFocus=remember {FocusRequester()}
    var drawerPreviouslyShown by remember {mutableStateOf(false)}
    LaunchedEffect(drawerPresented, drawerState.currentValue) {
        if(drawerPresented) {
            drawerPreviouslyShown=true
            // Material moves focus to the drawer's first child when opening
            // settles. Request our close action afterwards, rather than at
            // targetValue=Open where Material would overwrite it a few frames later.
            if(drawerState.isOpen) {
                withFrameNanos { }
                drawerCloseFocus.requestFocus()
            }
        } else if(drawerPreviouslyShown) {
            drawerPreviouslyShown=false
            withFrameNanos { }
            runCatching {if(route=="home") menuFocus.requestFocus() else backgroundFocus.requestFocus()}
        }
    }
    val scope=rememberCoroutineScope()
    val profile by container.account.profileState.collectAsStateWithLifecycle()
    val appearance by application.preferences.theme.collectAsStateWithLifecycle()
    val language=androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language
    val roots = listOf("home", "history", "progress", "profile")
    fun navigateRoot(destination:String, selectToday:Boolean=true) {
        if(destination=="home" && selectToday) home.today()
        nav.navigate(destination) {
            popUpTo(nav.graph.findStartDestination().id) {saveState=true}
            launchSingleTop=true;restoreState=true
        }
    }
    fun navigateDrawer(destination:String) {scope.launch {
        drawerState.close()
        if(destination in roots) navigateRoot(destination) else nav.navigate(destination) {launchSingleTop=true}
    }}
    val labels = listOf(R.string.today, R.string.history, R.string.progress, R.string.profile)
    val icons = listOf(Icons.Outlined.Home, Icons.Outlined.CalendarMonth, Icons.AutoMirrored.Outlined.TrendingUp, Icons.Outlined.Person)
    var addSheet by remember { mutableStateOf(false) }
    var catalogQuery by remember {mutableStateOf("")}
    var catalogBarcode by remember {mutableStateOf<String?>(null)}
    var catalogSaving by remember {mutableStateOf(false)}
    val snackbar = remember { SnackbarHostState() }
    fun edit(uid: String) {
        val current = nav.currentBackStackEntry
        if (current?.destination?.route == "meal/{uid}" && current.arguments?.getString("uid") == uid) return
        nav.navigate("meal/$uid") { launchSingleTop = true }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { scanner.importPhoto(it, context.contentResolver, homeState.date) }
    }
    fun gallery() { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    LaunchedEffect(scanner, nav, lifecycle) {
        // A persisted photo may emit during Activity recreation. Consume only after NavHost is ready.
        nav.currentBackStackEntryFlow.first()
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { scanner.openEditor.collect { edit(it) } }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { dailyState.error ?: dailyState.notice }.filterNotNull().collect { id ->
            daily.dismissNotice()
            snackbar.showSnackbar(context.getString(id))
        }
    }
    LaunchedEffect(route) { snackbar.currentSnackbarData?.dismiss() }
    LaunchedEffect(scannerState) {
        val state = scannerState
        if (state is ScannerState.Error && route != "scanner") {
            snackbar.showSnackbar(messageString(context, state.message)); scanner.dismissError()
        }
    }
    CompositionLocalProvider(LocalWellnessOverlayBackdrop provides backdrop) {
    ModalNavigationDrawer(drawerState=drawerState,gesturesEnabled=route in roots,
        scrimColor=ShadowColor.copy(alpha=WellnessGlass.scrimOpacity),drawerContent={
        ModalDrawerSheet(drawerContainerColor=Color.Transparent,drawerTonalElevation=0.dp,
            windowInsets=WindowInsets(0),
            modifier=Modifier.width(minOf(WellnessSpace.drawerMaximum, (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp*WellnessSpace.drawerFraction).dp))
                // A closed drawer lives off screen. Do not keep its full-height
                // blur target registered on every diary scroll frame.
                .then(if(drawerPresented) Modifier.glassBackdrop(dialog=true) else Modifier.background(CardSurface))) {
            if(drawerPresented) AccountDrawer(profile,container.owner=="guest",dailyState.goals,
                appearance,language,route,::navigateDrawer,{scope.launch {drawerState.close()}},
                onLogout={scope.launch {
                    drawerState.close();val revoked=container.account.logout()
                    if(!revoked) android.widget.Toast.makeText(context,R.string.logout_local_only,android.widget.Toast.LENGTH_LONG).show()
                    (context as MainActivity).identityChanged()
                }},onGuestLogin={navigateDrawer("login")},closeFocusRequester=drawerCloseFocus,localOnly=container.standalone!=null)
        }
    }) {
    WellnessBackground(Modifier.fillMaxSize()) {
    Scaffold(modifier=Modifier.focusRequester(backgroundFocus)
        .focusProperties {onEnter={if(drawerPresented) cancelFocusChange()}}
        .focusRestorer().focusGroup()
        .then(if(drawerPresented) Modifier.clearAndSetSemantics {} else Modifier),
        containerColor = Color.Transparent, snackbarHost = {
            CompositionLocalProvider(LocalWellnessOverlay provides true) {SnackbarHost(snackbar) { WellnessSnackbar(it) }}
        },
        contentWindowInsets = if (route in listOf("scanner","barcode")) WindowInsets(0) else ScaffoldDefaults.contentWindowInsets,
        bottomBar = {
            if (route in roots) CompositionLocalProvider(LocalWellnessOverlay provides true) { FloatingNavigation(route, roots, labels.map { stringResource(it) }, icons) { destination ->
                            navigateRoot(destination)
            } }
        }) { padding ->
        val contentInsets = PaddingValues(start = padding.calculateStartPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
            top = padding.calculateTopPadding(), end = padding.calculateEndPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
            bottom = if (route in roots) 0.dp else padding.calculateBottomPadding())
        val listBottom = padding.calculateBottomPadding() + 20.dp
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(contentInsets).consumeWindowInsets(contentInsets).clipToBounds()
            .then(if((route in roots || overlayNeedsBackdrop) && backdrop!=null)
                Modifier.hazeSource(backdrop,zIndex=1f,key=WellnessBackdropKeys.Content) else Modifier).background(PageBackground),
            // Tabs replace content immediately; fading two captured screens
            // beneath live glass caused a visible flash on the physical Xiaomi.
            // Secondary pages retain the short slide/fade transition.
            enterTransition = { if (initialState.destination.route in roots && targetState.destination.route in roots) EnterTransition.None else fadeIn(tween(WellnessMotion.enter)) + slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(WellnessMotion.enter),initialOffset={it/12}) },
            exitTransition = { if(initialState.destination.route in roots && targetState.destination.route in roots) ExitTransition.None else fadeOut(tween(WellnessMotion.exit)) },
            popEnterTransition = { if (initialState.destination.route in roots && targetState.destination.route in roots) EnterTransition.None else fadeIn(tween(WellnessMotion.enter)) + slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(WellnessMotion.enter),initialOffset={it/12}) },
            popExitTransition = { if(initialState.destination.route in roots && targetState.destination.route in roots) ExitTransition.None else fadeOut(tween(WellnessMotion.exit)) }) {
            rootDestination("home") { RootContent("home",presentedRoute.value) {HomeScreen(home, daily, container.photos, ::edit, { nav.navigate("goals") }, onMenu={scope.launch {drawerState.open()}},onAdd = { addSheet = true }, bottomSpace = listBottom,onPlan={nav.navigate("plan")},
                onScan={if(container.standalone!=null) nav.navigate("examples") else {scanner.captureDate(homeState.date);nav.navigate("scanner")}},
                onManual={home.createManual(::edit)},menuFocusRequester=menuFocus)} }
            rootDestination("history") { RootContent("history",presentedRoute.value) {HistoryScreen(daily, container.photos, { uid -> home.open(uid,::edit) }, { day -> home.selectDate(day); navigateRoot("home",selectToday=false) }, bottomSpace = listBottom)} }
            rootDestination("progress") { RootContent("progress",presentedRoute.value) {ProgressScreen(daily, bottomSpace = listBottom)} }
            rootDestination("profile") { RootContent("profile",presentedRoute.value) {ProfileScreen(daily, bottomSpace = listBottom,onPhone={nav.navigate("phone_auth")},onGoals={nav.navigate("goals")},onQuestionnaire={nav.navigate("questionnaire")},onPlan={nav.navigate("plan")})} }
            composable("questionnaire") {QuestionnaireScreen({nav.popBackStack()},{nav.popBackStack()})}
            composable("goals") {GoalsScreen({nav.popBackStack()},{nav.navigate("questionnaire")},{nav.navigate("plan")})}
            composable("plan") {PlanScreen({nav.popBackStack()},{nav.navigate("goals")})}
            composable("login") {Column(Modifier.fillMaxSize().imePadding().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(20.dp)) {
                AccountPanel(initialForm=AccountForm.Login,onBack={nav.popBackStack()})
            }}
            composable("phone_auth") {if(container.standalone!=null) StandaloneInfrastructureScreen {nav.popBackStack()} else Column(Modifier.fillMaxSize().imePadding().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(20.dp)) {PhonePanel {nav.popBackStack()}}}
            listOf("appearance","language","about").forEach {kind ->composable(kind) {PreferencesScreen(kind,application.preferences) {nav.popBackStack()}}}
            composable("settings") {Column {BackButton {nav.popBackStack()};SettingsScreen(daily,onGoals={nav.navigate("goals")})}}
            composable("scanner") { if(container.standalone!=null) StandaloneInfrastructureScreen {nav.popBackStack()} else ScannerScreen(scannerViewModel = scanner, onClose = { nav.popBackStack() }, onGallery = ::gallery,
                onPermissionChanged = { cameraPermission = it }) }
            composable("barcode") {ScannerScreen(scannerViewModel=scanner,onClose={nav.popBackStack()},
                onGallery={catalogBarcode=null;catalogQuery="";nav.navigate("catalog/product") {popUpTo("barcode") {inclusive=true}}},
                onPermissionChanged={cameraPermission=it},barcode=true,onBarcode={code ->
                    catalogBarcode=code.takeIf {it.isNotBlank()};catalogQuery=""
                    nav.navigate("catalog/product") {popUpTo("barcode") {inclusive=true}}
                })}
            composable("catalog/{kind}") {e ->CatalogScreen(repository=container.catalog,
                kind=e.arguments?.getString("kind") ?: "product",initialQuery=catalogQuery,initialBarcode=catalogBarcode,
                onBack={nav.popBackStack()},onScanBarcode={nav.navigate("barcode")},
                onManualEntry={nav.popBackStack();home.createManual(::edit)},onSelected={selection ->
                    if(!catalogSaving) {
                        catalogSaving=true
                        scope.launch {
                            try {
                                container.ready.await()
                                val timestamp=homeState.date.atTime(java.time.LocalTime.now()).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                                val meal=com.tamaqai.domain.usecase.CatalogMealFactory.create(selection,timestamp)
                                container.mealRepository.saveDraft(meal)
                                // Room may complete on its transaction executor. Navigation
                                // mutates LifecycleRegistry and must explicitly return to Main.
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                                    nav.popBackStack();edit(meal.uid)
                                }
                            } catch(cancel:kotlinx.coroutines.CancellationException) {throw cancel}
                            catch(_:Exception) {snackbar.showSnackbar(context.getString(R.string.catalog_add_failed))}
                            finally {catalogSaving=false}
                        }
                    }
                })}
            composable("examples") {
                container.standalone?.let {demo ->StandaloneExamplesScreen(demo,homeState.date,{nav.popBackStack()}) {uid ->
                    nav.popBackStack();edit(uid)
                }}
            }
            composable("favorites") { FavoritesScreen(daily, container.photos, homeState.date, ::edit, { nav.popBackStack() }) }
            composable("meal/{uid}") { e -> MealEditorScreen(requireNotNull(e.arguments?.getString("uid")), {
                nav.popBackStack()
                if (nav.currentBackStackEntry?.destination?.route == "scanner") nav.popBackStack()
            }) }
        }
    }
    if (scannerState is ScannerState.Loading && route != "scanner") WellnessDialog(onDismissRequest = {},
        properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        text = { Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            WellnessLoadingIndicator(); Text(stringResource(R.string.preparing_photo))
        } }, confirmButton = {})
    if (addSheet) WellnessBottomSheet(onDismissRequest = { addSheet = false }, containerColor = CardSurface, shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)) {
        Column(Modifier.fillMaxWidth().heightIn(max=(androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp*.82f).dp)
            .verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.add_sheet_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.add_sheet_hint), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Spacer(Modifier.height(8.dp))
            if(container.standalone!=null) {
                PrimaryButton(stringResource(R.string.standalone_example),{addSheet=false;nav.navigate("examples")})
                Text(stringResource(R.string.standalone_manual_photo_hint),style=MaterialTheme.typography.bodySmall,color=TextSecondary)
            } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryAddOption(R.string.take_photo, R.string.camera_option_hint, Icons.Outlined.PhotoCamera, Modifier.weight(1f), true) {
                    addSheet = false; scanner.captureDate(homeState.date); nav.navigate("scanner") }
                PrimaryAddOption(R.string.gallery, R.string.gallery_option_hint, Icons.Outlined.PhotoLibrary, Modifier.weight(1f)) { addSheet = false; gallery() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryAddOption(R.string.catalog_product_option,R.string.catalog_product_hint,Icons.Outlined.Search,Modifier.weight(1f)) {
                    addSheet=false;catalogQuery="";catalogBarcode=null;nav.navigate("catalog/product") }
                PrimaryAddOption(R.string.catalog_restaurant_option,R.string.catalog_restaurant_hint,Icons.Outlined.Restaurant,Modifier.weight(1f)) {
                    addSheet=false;catalogQuery="";catalogBarcode=null;nav.navigate("catalog/dish") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryAddOption(R.string.manual, R.string.manual_option_hint, Icons.Outlined.Edit, Modifier.weight(1f)) { addSheet = false; home.createManual(::edit) }
                PrimaryAddOption(R.string.favorites, R.string.favorite_option_hint, Icons.Outlined.BookmarkBorder, Modifier.weight(1f)) { addSheet = false; nav.navigate("favorites") }
            }
            WellnessTextButton({addSheet=false;nav.navigate("barcode")},modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.catalog_barcode_title))}
            Spacer(Modifier.height(24.dp))
        }
    }
    }
    }
    // Registered after NavHost so an open drawer takes Back before the page stack.
    androidx.activity.compose.BackHandler(drawerState.isOpen || drawerState.targetValue==DrawerValue.Open) {scope.launch {drawerState.close()}}
    }
}

/** Root destinations must override inherited transitions, including restored stacks. */
private fun NavGraphBuilder.rootDestination(route:String,content:@Composable AnimatedContentScope.(NavBackStackEntry)->Unit) {
    composable(route,enterTransition={EnterTransition.None},exitTransition={ExitTransition.None},
        popEnterTransition={EnterTransition.None},popExitTransition={ExitTransition.None},content=content)
}

@Composable private fun RootContent(name:String,current:String,content:@Composable ()->Unit) {
    val active=name==current
    // Navigation may retain the outgoing composition for stack restoration even
    // with None transitions. Keep it composed/measured for saved state, but do
    // not place an inactive page: it cannot draw or intercept taps.
    // Alpha alone would leave the old page in hit testing during restored pops.
    Box(Modifier.fillMaxSize().layout {measurable,constraints ->
        val page=measurable.measure(constraints)
        layout(page.width,page.height) {if(active) page.placeRelative(0,0)}
    }.background(PageBackground)
        .then(if(active) Modifier.testTag("root-screen-$name") else Modifier.clearAndSetSemantics {})) {content()}
}

@Composable private fun PrimaryAddOption(label: Int, hint: Int, icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier, primary: Boolean = false, action: () -> Unit) {
    Surface(onClick = action, modifier = modifier.heightIn(min = 148.dp).matteSurface(shape=RoundedCornerShape(24.dp)), shape = RoundedCornerShape(24.dp),
        color = if (primary) SelectedSurface.copy(alpha=.35f) else Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (primary) AccentBrown else BorderSoft)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, tint = if (primary) AccentBrown else TextPrimary, modifier = Modifier.size(26.dp))
            Text(stringResource(label), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
    }
}
