package com.tamaqai.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.tamaqai.data.local.ThemeMode
import com.tamaqai.data.remote.AccountProfile
import com.tamaqai.domain.model.DailyGoals
import com.tamaqai.ui.components.AccountDrawer
import com.tamaqai.ui.theme.TamaqTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Isolated component checks: no account, database, network or device preferences are changed. */
class DrawerInlineTest {
    @get:Rule val rule = createComposeRule()

    @Test fun themeAndLanguageStayInlineAndOnlyOneSectionExpands() {
        var route: String? = null
        var selectedTheme = ThemeMode.Light
        var selectedLanguage = "en"
        rule.setContent {
            var theme by remember { mutableStateOf(ThemeMode.Light) }
            var language by remember { mutableStateOf("en") }
            TamaqTheme(darkTheme = theme == ThemeMode.Dark) {
                AccountDrawer(AccountProfile(name = "Demo"), true, DailyGoals(), theme, language, "home",
                    onNavigate = { route = it }, onClose = {}, onLogout = {}, onGuestLogin = {},
                    onThemeChange = { theme = it; selectedTheme = it },
                    onLanguageChange = { language = it; selectedLanguage = it })
            }
        }
        rule.onNodeWithTag("drawer-appearance").performScrollTo().performClick()
        rule.onNodeWithTag("drawer-theme-Dark").performScrollTo().performClick().assertIsSelected()
        rule.onNodeWithTag("drawer-content").assertExists()
        rule.runOnIdle { assertEquals(ThemeMode.Dark, selectedTheme); assertEquals(null, route) }
        rule.onNodeWithTag("drawer-language").performScrollTo().performClick()
        rule.onNodeWithTag("drawer-language-kk").performScrollTo().performClick().assertIsSelected()
        rule.onNodeWithTag("drawer-theme-Dark").assertDoesNotExist()
        rule.runOnIdle { assertEquals("kk", selectedLanguage); assertEquals(null, route) }
        rule.onNodeWithTag("drawer-language").performScrollTo().performClick()
        rule.onNodeWithTag("drawer-language-kk").assertDoesNotExist()
    }
}