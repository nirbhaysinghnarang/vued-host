package com.nsn8.vued

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MicStatusPhysicalEdgeSwipeDetectorTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun leftSwipeFromPhysicalEdgeOpensDrawer() {
        var opened = false
        composeRule.setContent {
            Box(Modifier.fillMaxSize()) {
                MicStatusPhysicalEdgeSwipeDetector(
                    onOpen = { opened = true },
                )
            }
        }

        composeRule
            .onNodeWithContentDescription("Swipe left to open microphone statuses")
            .performTouchInput {
                swipe(
                    start = Offset(width - 1f, height / 2f),
                    end = Offset(-width.toFloat(), height / 2f),
                    durationMillis = 300L,
                )
            }

        composeRule.runOnIdle { assertTrue(opened) }
    }
}
