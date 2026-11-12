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
// Robolectric does not support the API 36 runtime used by the app's compile SDK.
@Config(sdk = [35])
class MainActivityRobolectricTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun resetLocale() {
        AppLocaleManager.setLanguage(
            composeTestRule.activity,
            AppLocaleManager.LANG_ENGLISH
        )
        composeTestRule.waitForIdle()
    }

    @Test
    fun mainActivity_launchesWithoutActivityResultRegistryCrash_andSwitchesLanguageLive() {
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("live_multicam_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("quick_language_toggle_btn").assertIsDisplayed()

        composeTestRule.onNodeWithTag("quick_language_toggle_btn").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Canlı").assertIsDisplayed()
        composeTestRule.onNodeWithTag("live_multicam_screen").assertIsDisplayed()

        composeTestRule.onNodeWithTag("quick_language_toggle_btn").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Live").assertIsDisplayed()
    }
}
