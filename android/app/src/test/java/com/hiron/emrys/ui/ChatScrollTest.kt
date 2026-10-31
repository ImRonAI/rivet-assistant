package com.hiron.emrys.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import com.hiron.emrys.core.ChatMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatScrollTest {
    @get:Rule
    val compose = createComposeRule()

    private fun conversation(n: Int) = List(n) { i ->
        ChatMessage("m$i", fromUser = i % 2 == 0, text = "msg-%02d ".format(i) + "words ".repeat(12))
    }

    @Test
    fun aLongChatOpensAtTheNewestMessageAndScrollsBackToTheFirst() {
        compose.setContent {
            EmrysTheme { Box(Modifier.size(360.dp, 320.dp)) { Chat(conversation(40), null, Modifier.fillMaxSize()) } }
        }
        compose.onNodeWithText("msg-39", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(CHAT_TAG).performScrollToNode(hasText("msg-00", substring = true))
        compose.onNodeWithText("msg-00", substring = true).assertIsDisplayed()
    }
}
