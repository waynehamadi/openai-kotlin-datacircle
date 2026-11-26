package dev.datacircle.openaikotlin

import com.aallam.openai.api.ExperimentalOpenAI
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.chat.ToolCall
import com.aallam.openai.api.core.Parameters
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import com.aallam.openai.client.OpenAIHost
import com.aallam.openai.client.extension.mergeToChatMessage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * The tools in openai-kotlin's own chat completions, plain and streamed, on a scripted model that calls the four tools
 * and an app's own tool in one turn, then reads their answers.
 */
@OptIn(ExperimentalOpenAI::class)
class ChatLoopTest {

    private val standIn = StandIn(
        listOf(
            "datacircle_list_apis" to """{"query": "email"}""",
            "datacircle_best_plans" to """{"api": "email-finder", "limit": 2}""",
            "datacircle_search_plans" to """{"api": "Employee email", "query": "hunter", "sort": "price", "limit": 1, "offset": 3}""",
            "datacircle_provider" to """{"provider": "Bright Data"}""",
            "get_time" to """{"city": "Paris"}""",
        ),
    ).apply {
        routes["/apis"] = 200 to DatacircleToolsTest.APIS
        routes["/apis/email-finder/best?limit=2"] = 200 to DatacircleToolsTest.BEST
        routes["/plans?sort=price&limit=1&offset=3&api=Employee+email&query=hunter"] = 200 to DatacircleToolsTest.SEARCH
        routes["/providers/bright-data"] = 200 to DatacircleToolsTest.PROVIDER
    }
    private val openAI = OpenAI(OpenAIConfig(token = "local", host = OpenAIHost(baseUrl = standIn.modelUrl)))
    private val datacircle = DatacircleTools(standIn.apiUrl, 5.seconds)
    private val getTime = Tool.function(
        name = "get_time",
        description = "The time in a city",
        parameters = Parameters.fromJsonString("""{"type": "object", "properties": {"city": {"type": "string"}}}"""),
    )

    @AfterTest
    fun stop() {
        openAI.close()
        standIn.close()
    }

    @Test
    fun chatCompletionCallsEachToolBesideTheAppsOwn() = runBlocking {
        val messages = mutableListOf(ChatMessage(role = ChatRole.User, content = "Who is best for email-finder?"))
        val message = openAI.chatCompletion(request(messages)).choices.first().message
        assertEquals(5, message.toolCalls!!.size)
        messages += message
        for (toolCall in message.toolCalls.orEmpty()) {
            messages += datacircle.answer(toolCall) ?: ownAnswer(toolCall)
        }
        val final = openAI.chatCompletion(request(messages)).choices.first().message
        assertEquals("Done.", final.content)
        checkTheModelReadEveryAnswer()
    }

    @Test
    fun aStreamedTurnMergedByTheSdkIsAnsweredWhole() = runBlocking {
        val messages = mutableListOf(ChatMessage(role = ChatRole.User, content = "Who is best for email-finder?"))
        val message = openAI.chatCompletions(request(messages)).toList().flatMap { it.choices }.mergeToChatMessage()
        assertEquals(5, message.toolCalls!!.size)
        messages += message
        messages += datacircle.answerAll(message) { call ->
            if (call.function.name == "get_time") "12:00 in Paris" else "Error: no tool is named ${call.function.name}."
        }
        val final = openAI.chatCompletions(request(messages)).toList().flatMap { it.choices }.mergeToChatMessage()
        assertEquals("Done.", final.content)
        checkTheModelReadEveryAnswer()
    }

    private fun request(messages: List<ChatMessage>) =
        ChatCompletionRequest(model = ModelId("scripted"), messages = messages.toList(), tools = datacircle.tools + getTime)

    private fun ownAnswer(toolCall: ToolCall): ChatMessage =
        ChatMessage.Tool(content = "12:00 in Paris", toolCallId = (toolCall as ToolCall.Function).id)

    /** The model got the five tools each turn, and its second turn holds an answer for each of its five calls. */
    private fun checkTheModelReadEveryAnswer() {
        assertEquals(2, standIn.modelBodies.size)
        for (body in standIn.modelBodies) {
            val tools = (body["tools"] as List<*>).map { it as Map<*, *> }
            assertEquals(DatacircleTools.NAMES.toList() + "get_time", tools.map { (it["function"] as Map<*, *>)["name"] })
            assertTrue(tools.all { it["type"] == "function" && ((it["function"] as Map<*, *>)["parameters"] as Map<*, *>)["type"] == "object" })
        }
        val messages = (standIn.modelBodies[1]["messages"] as List<*>).map { it as Map<*, *> }
        assertEquals(listOf("user", "assistant", "tool", "tool", "tool", "tool", "tool"), messages.map { it["role"] })
        val calls = (messages[1]["tool_calls"] as List<*>).map { it as Map<*, *> }
        assertEquals((0..4).map { "call_$it" }, calls.map { it["id"] })
        assertEquals(
            """{"api": "Employee email", "query": "hunter", "sort": "price", "limit": 1, "offset": 3}""",
            (calls[2]["function"] as Map<*, *>)["arguments"],
        )
        assertEquals((0..4).map { "call_$it" }, messages.drop(2).map { it["tool_call_id"] })
        val starts = listOf(
            "- Employee email (email-finder): 5,444 plans",
            "Employee email (email-finder): the best 2 of 5,444 plans.",
            "Found 7 plans; showing 4 to 4.",
            "Bright Data (https://brightdata.com) sells 2 APIs.",
            "12:00 in Paris",
        )
        for ((message, start) in messages.drop(2).zip(starts)) {
            assertTrue((message["content"] as String).startsWith(start), "${message["tool_call_id"]}: ${message["content"]}")
        }
        assertEquals(
            listOf(
                "/apis",
                "/apis/email-finder/best?limit=2",
                "/plans?sort=price&limit=1&offset=3&api=Employee+email&query=hunter",
                "/providers/bright-data",
            ),
            standIn.apiRequests,
        )
    }
}
