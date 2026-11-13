package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.i18n.AppLocaleManager
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MainActivityRobolectricTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun resetLocale() {
        AppLocaleManager.setLanguage(composeTestRule.activity, AppLocaleManager.LANG_ENGLISH)
        com.example.service.FloatingOverlayStateHolder.setGlobalMuted(composeTestRule.activity, false)
        composeTestRule.waitForIdle()
    }

    @Test
    fun mainActivity_launchesWithoutActivityResultRegistryCrash_andSwitchesLanguageLive() {
        composeTestRule.waitForIdle()

        // Verify LiveMultiCamScreen mounted (with rememberLauncherForActivityResult) cleanly
        composeTestRule.onNodeWithTag("live_multicam_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("quick_language_toggle_btn").assertIsDisplayed()

        // Toggle live to Turkish via quick badge
        composeTestRule.onNodeWithTag("quick_language_toggle_btn").performClick()
        composeTestRule.waitForIdle()

        // Turkish navigation item should now be visible without crashing or restarting
        composeTestRule.onNodeWithText("Canlı").assertIsDisplayed()
        composeTestRule.onNodeWithTag("live_multicam_screen").assertIsDisplayed()

        // Toggle back to English
        composeTestRule.onNodeWithTag("quick_language_toggle_btn").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Live").assertIsDisplayed()

        // Toggle global mute from live header and verify shared FloatingOverlayStateHolder stays muted
        composeTestRule.onNodeWithTag("global_mute_toggle_btn").assertIsDisplayed()
        composeTestRule.onNodeWithTag("global_mute_toggle_btn").performClick()
        composeTestRule.waitForIdle()
        org.junit.Assert.assertTrue(com.example.service.FloatingOverlayStateHolder.isGlobalMuted.value)
        composeTestRule.onNodeWithText("Muted").assertIsDisplayed()

        // Unmute and verify state flips back cleanly
        composeTestRule.onNodeWithTag("global_mute_toggle_btn").performClick()
        composeTestRule.waitForIdle()
        org.junit.Assert.assertFalse(com.example.service.FloatingOverlayStateHolder.isGlobalMuted.value)
        composeTestRule.onNodeWithText("Sound").assertIsDisplayed()
    }
}
