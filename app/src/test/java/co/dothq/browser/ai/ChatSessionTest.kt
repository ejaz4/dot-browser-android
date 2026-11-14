package co.dothq.browser.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a reply dodges a question Chat could have looked up, it searches instead. */
class ChatSessionTest {
    @Test fun refusalsFromLackOfAccessTriggerASearch() {
        // Qwen3.5 2B's replies from a real phone, before Chat had native tool calls.
        assertTrue(ChatSession.soundsUnsure(
            "I don't have access to personal information about Jensen Huang's affairs. For privacy reasons, I can't share private details of individuals like this.",
        ))
        assertTrue(ChatSession.soundsUnsure(
            "I can't confirm specific details about Jensen Huang's personal life, including any affairs, as that information is private and not publicly available.",
        ))
        assertTrue(ChatSession.soundsUnsure("As of my last update, the CEO was Sundar Pichai."))
        assertTrue(ChatSession.soundsUnsure("I don’t have real-time information about the weather."))
        assertTrue(ChatSession.soundsUnsure("I'm not aware of any recent news about that."))
    }

    @Test fun realAnswersAreLeftAlone() {
        assertFalse(ChatSession.soundsUnsure("17 times 23 equals 391."))
        assertFalse(ChatSession.soundsUnsure("The capital of Australia is Canberra, according to Wikipedia."))
        assertFalse(ChatSession.soundsUnsure("Here's a short poem about autumn leaves drifting down."))
        assertFalse(ChatSession.soundsUnsure(""))
    }
}
