package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
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
        composeTestRule.onNodeWithContentDescription("Find Network Cameras").assertIsDisplayed()

        // Toggle live to Turkish via quick badge
        composeTestRule.onNodeWithTag("quick_language_toggle_btn").performClick()
        composeTestRule.waitForIdle()

        // Turkish navigation item and live screen content should both update immediately without restarting
        composeTestRule.onNodeWithText("Canlı").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Ağdaki Kameraları Bul").assertIsDisplayed()
        composeTestRule.onNodeWithTag("live_multicam_screen").assertIsDisplayed()

        // Toggle back to English
        composeTestRule.onNodeWithTag("quick_language_toggle_btn").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Live").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Find Network Cameras").assertIsDisplayed()

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

        // Complete app exit button opens confirmation dialog and can be cancelled safely
        composeTestRule.onNodeWithTag("exit_app_btn").assertIsDisplayed()
        composeTestRule.onNodeWithTag("exit_app_btn").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("exit_app_confirm_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("confirm_exit_app_btn").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun mainActivity_addingSecondCamera_mountsBothCardsWithoutCrash() {
        composeTestRule.waitForIdle()

        // Add first camera
        composeTestRule.onNodeWithTag("add_camera_fab").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("camera_settings_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("cam_name_input").performTextReplacement("CamOne")
        composeTestRule.onNodeWithTag("cam_rtsp_url_input").performTextInput("rtsp://192.168.1.50:554/stream1")
        composeTestRule.onNodeWithTag("save_camera_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        // Add second camera
        composeTestRule.onNodeWithTag("add_camera_fab").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("camera_settings_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("cam_name_input").performTextReplacement("CamTwo")
        composeTestRule.onNodeWithTag("cam_rtsp_url_input").performTextInput("rtsp://192.168.1.51:554/stream1")
        composeTestRule.onNodeWithTag("save_camera_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("camera_matrix_grid").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("CamOne").onFirst().assertIsDisplayed()
        composeTestRule.onAllNodesWithText("CamTwo").onFirst().assertIsDisplayed()
        composeTestRule.onAllNodesWithText("2 Cams", substring = true).onFirst().assertIsDisplayed()
    }
}
