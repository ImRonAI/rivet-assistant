package com.hiron.rivet.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import com.hiron.rivet.core.ChatMessage
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
            RivetTheme { Box(Modifier.size(360.dp, 320.dp)) { Chat(conversation(40), null, Modifier.fillMaxSize()) } }
        }
        compose.onNodeWithText("msg-39", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(CHAT_TAG).performScrollToNode(hasText("msg-00", substring = true))
        compose.onNodeWithText("msg-00", substring = true).assertIsDisplayed()
    }

    @Test
    fun streamingTextDoesNotPullYouBackDownWhileReadingHistory() {
        val messages = mutableStateOf(conversation(40))
        compose.setContent {
            RivetTheme { Box(Modifier.size(360.dp, 320.dp)) { Chat(messages.value, null, Modifier.fillMaxSize()) } }
        }
        compose.onNodeWithTag(CHAT_TAG).performScrollToNode(hasText("msg-05", substring = true))
        repeat(5) { step ->
            val last = messages.value.last()
            messages.value = messages.value.dropLast(1) + last.copy(text = last.text + " more$step", streaming = true)
            compose.waitForIdle()
        }
        compose.onNodeWithText("msg-05", substring = true).assertIsDisplayed()
    }

    @Test
    fun streamingTextStaysInViewAtTheBottom() {
        val messages = mutableStateOf(conversation(10))
        compose.setContent {
            RivetTheme { Box(Modifier.size(360.dp, 320.dp)) { Chat(messages.value, null, Modifier.fillMaxSize()) } }
        }
        repeat(8) { step ->
            val last = messages.value.last()
            messages.value = messages.value.dropLast(1) + last.copy(text = last.text + " streamed$step".repeat(3), streaming = true)
            compose.waitForIdle()
        }
        compose.onNodeWithText("streamed7", substring = true).assertIsDisplayed()
    }
}
