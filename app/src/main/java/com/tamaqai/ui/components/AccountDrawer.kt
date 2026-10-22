package com.tamaqai.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.RestaurantMenu
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tamaqai.BuildConfig
import com.tamaqai.R
import com.tamaqai.data.local.ThemeMode
import com.tamaqai.data.remote.AccountProfile
import com.tamaqai.domain.model.DailyGoals
import com.tamaqai.ui.theme.*

/** Drawer geometry stays consistent in both themes; all text can increase its height. */
private object AccountDrawerGeometry {
    val avatar = 60.dp
    val row = 56.dp
    val iconField = 36.dp
    val icon = 20.dp
    val goalRadius = 22.dp
}

/**
 * Account/settings hub backed only by the current owner's profile, goals and device preferences.
 * The caller owns navigation, logout, the glass sheet and its modal focus/Back behavior.
 * No account contact information is shown in this surface or retained as drawer state.
 */
@Composable
fun AccountDrawer(
    profile: AccountProfile,
    isGuest: Boolean,
    goals: DailyGoals,
    theme: ThemeMode,
    languageCode: String,
    currentRoute: String,
    onNavigate: (String) -> Unit,
    onClose: () -> Unit,
    onLogout: () -> Unit,
    onGuestLogin: () -> Unit,
    modifier: Modifier = Modifier,
    closeFocusRequester: FocusRequester? = null,
) {
    val paneLabel = stringResource(R.string.drawer_account_and_settings)
    val displayName = profile.name.trim().ifBlank {
        stringResource(if (isGuest) R.string.account_guest else R.string.account_title)
    }
    val themeLabel = stringResource(when (theme) {
        ThemeMode.System -> R.string.theme_system
        ThemeMode.Light -> R.string.theme_light
        ThemeMode.Dark -> R.string.theme_dark
    })
    val languageLabel = stringResource(when (languageCode) {
        "ru" -> R.string.language_ru
        "kk" -> R.string.language_kk
        else -> R.string.language_en
    })
    Column(
        modifier.fillMaxHeight().testTag("drawer-content")
            .semantics { paneTitle = paneLabel; isTraversalGroup = true }
            .focusProperties {onExit={cancelFocusChange()}}
            .focusGroup()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = WellnessSpace.page, vertical = WellnessSpace.compact),
    ) {
        Row(
            Modifier.fillMaxWidth().testTag("drawer-header"),
            horizontalArrangement = Arrangement.spacedBy(WellnessSpace.compact),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.testTag("drawer-avatar")) { ProfileAvatar(profile, AccountDrawerGeometry.avatar) }
            Column(Modifier.weight(1f).padding(top = 3.dp)) {
                Text(
                    displayName, Modifier.testTag("drawer-name").semantics { heading() },
                    style = MaterialTheme.typography.titleLarge, color = TextPrimary,
                )
                TextButton(
                    onClick = { if (isGuest) onGuestLogin() else onNavigate("profile") },
                    modifier = Modifier.testTag("drawer-profile").heightIn(min = WellnessSpace.touch),
                    // A zero horizontal inset clips multiline labels against
                    // Material's button outline at 130% text size.
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
                    shape = RoundedCornerShape(WellnessSpace.control),
                    colors = ButtonDefaults.textButtonColors(contentColor = AccentBrown),
                ) {
                    Text(
                        stringResource(if (isGuest) R.string.account_login else R.string.drawer_view_profile),
                        modifier = Modifier.weight(1f).then(if (isGuest) Modifier.testTag("drawer-login") else Modifier),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp))
                }
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(WellnessSpace.touch).testTag("drawer-close")
                    .then(closeFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
            ) {
                Box(
                    Modifier.size(AccountDrawerGeometry.iconField).background(SecondarySurface, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.close), Modifier.size(20.dp), tint = TextPrimary)
                }
            }
        }

        Spacer(Modifier.height(WellnessSpace.gap))
        CurrentGoalCard(goals) { onNavigate("goals") }
        Spacer(Modifier.height(WellnessSpace.section))
        DrawerGroupTitle(stringResource(R.string.drawer_nutrition_group))
        DrawerDestinationRow(
            "goals", stringResource(R.string.drawer_my_goals), Icons.Outlined.Flag,
            selected = currentRoute == "goals", iconSurface = Pistachio, iconContent = PastelContent,
        ) { onNavigate("goals") }
        DrawerDestinationRow(
            "plan", stringResource(R.string.meal_plan), Icons.Outlined.RestaurantMenu,
            selected = currentRoute == "plan", iconSurface = Apricot, iconContent = PastelContent,
        ) { onNavigate("plan") }

        Spacer(Modifier.height(WellnessSpace.section))
        DrawerGroupTitle(stringResource(R.string.drawer_application_group))
        DrawerDestinationRow(
            "appearance", stringResource(R.string.appearance), Icons.Outlined.Palette, themeLabel,
            selected = currentRoute == "appearance", iconSurface = Apricot, iconContent = PastelContent,
        ) { onNavigate("appearance") }
        DrawerDestinationRow(
            "language", stringResource(R.string.language), Icons.Outlined.Language, languageLabel,
            selected = currentRoute == "language", iconSurface = ProteinPastel, iconContent = PastelContent,
        ) { onNavigate("language") }
        DrawerDestinationRow(
            "settings", stringResource(R.string.settings), Icons.Outlined.Settings,
            selected = currentRoute == "settings", iconSurface = SecondarySurface,
        ) { onNavigate("settings") }
        DrawerDestinationRow(
            "about", stringResource(R.string.about_app), Icons.Outlined.Info,
            selected = currentRoute == "about", iconSurface = SecondarySurface,
        ) { onNavigate("about") }

        Spacer(Modifier.height(WellnessSpace.section))
        HorizontalDivider(color = BorderSoft.copy(alpha = .6f))
        if (!isGuest) {
            DrawerDestinationRow(
                "logout", stringResource(R.string.account_logout), Icons.AutoMirrored.Outlined.Logout,
                iconSurface = SecondarySurface, showChevron = false, onClick = onLogout,
            )
        }
        Text(
            stringResource(R.string.drawer_version, BuildConfig.VERSION_NAME),
            Modifier.testTag("drawer-version").padding(top = WellnessSpace.compact, bottom = WellnessSpace.small),
            color = TextSecondary, style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun CurrentGoalCard(goals: DailyGoals, onClick: () -> Unit) {
    val shape = RoundedCornerShape(AccountDrawerGeometry.goalRadius)
    Column(
        Modifier.fillMaxWidth().testTag("drawer-goal").clip(shape)
            .background(SecondarySurface.copy(alpha = .82f))
            .clickable(role = Role.Button, onClick = onClick).padding(WellnessSpace.gap),
        verticalArrangement = Arrangement.spacedBy(WellnessSpace.small),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WellnessSpace.small)) {
            Box(
                Modifier.size(32.dp).background(Pistachio, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.TrackChanges, null, Modifier.size(18.dp), tint = PastelContent)
            }
            Text(stringResource(R.string.drawer_daily_target), Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge, color = TextSecondary)
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = TextSecondary)
        }
        if (goals.isEmpty) {
            Text(stringResource(R.string.goal_not_set), color = TextPrimary, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.drawer_set_goal), color = AccentBrown, style = MaterialTheme.typography.labelLarge)
        } else {
            goals.calories?.let {
                Text(stringResource(R.string.calories_value, it), Modifier.testTag("drawer-goal-value"), color = TextPrimary,
                    style = MaterialTheme.typography.headlineSmall.copy(fontFamily = BodyFont))
            }
            val macroGoals = listOf(R.string.protein to goals.protein, R.string.fat to goals.fat, R.string.carbs to goals.carbs)
                .mapNotNull { (label, value) -> value?.let { stringResource(R.string.drawer_macro_target, stringResource(label), it) } }
            if (macroGoals.isNotEmpty()) {
                Text(macroGoals.joinToString(" · "), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.drawer_change_goal), color = AccentBrown, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun DrawerGroupTitle(title: String) {
    Text(title, Modifier.padding(start = 4.dp, bottom = 5.dp).semantics { heading() },
        color = TextSecondary, style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun DrawerDestinationRow(
    destination: String,
    title: String,
    icon: ImageVector,
    value: String? = null,
    selected: Boolean = false,
    iconSurface: Color,
    iconContent: Color = TextPrimary,
    showChevron: Boolean = true,
    onClick: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) .98f else 1f, tween(WellnessMotion.press), label = "drawer press")
    val stackedValue = LocalDensity.current.fontScale > 1.15f
    val shape = RoundedCornerShape(WellnessSpace.control)
    Row(
        Modifier.fillMaxWidth().testTag("drawer-$destination").heightIn(min = AccountDrawerGeometry.row)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }.clip(shape)
            .background(if (selected) SelectedSurface else Color.Transparent)
            .clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick)
            .then(if (selected) Modifier.semantics { this.selected = true } else Modifier)
            .padding(horizontal = 4.dp, vertical = WellnessSpace.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WellnessSpace.compact),
    ) {
        Box(
            Modifier.size(AccountDrawerGeometry.iconField).background(iconSurface.copy(alpha = .55f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(AccountDrawerGeometry.icon), tint = iconContent)
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            if (stackedValue && value != null) Text(value, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        if (!stackedValue && value != null) {
            Text(value, Modifier.widthIn(max = 100.dp), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        if (showChevron) Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = TextSecondary)
    }
}
