package com.nsn8.vued

import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Rule
import org.junit.Test

class MicDisconnectedIndicatorTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun indicatorIsVisibleAndNotClickable() {
        composeRule.setContent {
            MicDisconnectedIndicator()
        }

        composeRule
            .onNodeWithContentDescription("Microphone disconnected")
            .assertIsDisplayed()
            .assertHasNoClickAction()
    }

    @Test
    fun pendingCommandIndicatorIsVisibleAndNotClickable() {
        composeRule.setContent {
            MicCommandLoadingIndicator(
                unmuted = false,
                contentDescription = "Updating Conference Room microphone",
            )
        }

        composeRule
            .onNodeWithContentDescription("Updating Conference Room microphone")
            .assertIsDisplayed()
            .assertHasNoClickAction()
    }
}
