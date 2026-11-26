package dev.datacircle.openaikotlin

import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.FunctionCall
import com.aallam.openai.api.chat.ToolCall
import com.aallam.openai.api.chat.ToolId
import com.aallam.openai.api.chat.ToolType
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The four tools' declarations, and answer(toolCall) and call(name, arguments) on a local server. */
class ToolCallsTest {

    private val standIn = StandIn()
    private val tools = DatacircleTools(standIn.apiUrl + "/", 5.seconds)

    @AfterTest
    fun stop() {
        standIn.close()
    }

    @Test
    fun declaresFourFunctionsWithTheirParameters() {
        assertEquals(DatacircleTools.NAMES.toList(), tools.tools.map { it.function.name })
        assertTrue(tools.tools.all { it.type == ToolType.Function && it.function.description!!.length > 40 })
        val schemas = tools.tools.associate { it.function.name to it.function.parameters!!.schema.jsonObject }
        assertEquals(
            mapOf(
                "datacircle_list_apis" to setOf("query"),
                "datacircle_best_plans" to setOf("api", "limit"),
                "datacircle_search_plans" to setOf("api", "query", "sort", "limit", "offset"),
                "datacircle_provider" to setOf("provider"),
            ),
            schemas.mapValues { it.value["properties"]!!.jsonObject.keys },
        )
        assertEquals(
            mapOf("datacircle_best_plans" to listOf("api"), "datacircle_provider" to listOf("provider")),
            schemas.filterValues { "required" in it }
                .mapValues { schema -> schema.value["required"]!!.jsonArray.map { it.jsonPrimitive.content } },
        )
        assertTrue(schemas.values.all { it["type"]!!.jsonPrimitive.content == "object" })
    }

    @Test
    fun answersAToolCallWithItsToolMessage() = runBlocking {
        standIn.routes["/apis/email-finder/best?limit=2"] = 200 to DatacircleToolsTest.BEST
        val message = tools.answer(call("call_7", "datacircle_best_plans", """{"api": "email-finder", "limit": 2}"""))!!
        assertEquals(ChatRole.Tool, message.role)
        assertEquals(ToolId("call_7"), message.toolCallId)
        assertTrue(message.content!!.startsWith("Employee email (email-finder): the best 2 of 5,444 plans."), message.content)
        assertNull(tools.answer(call("call_8", "get_time", """{"city": "Paris"}""")))
        assertEquals(listOf("/apis/email-finder/best?limit=2"), standIn.apiRequests)
    }

    @Test
    fun answersEveryToolCallOfAMessage() = runBlocking {
        standIn.routes["/providers/hunter"] = 200 to """{"provider": "Hunter", "slug": "hunter", "apis": []}"""
        val message = ChatMessage(
            role = ChatRole.Assistant,
            content = "",
            toolCalls = listOf(
                call("a", "get_time", """{"city": "Paris"}"""),
                call("b", "datacircle_provider", """{"provider": "Hunter"}"""),
                call("c", "no_such_tool", "{}"),
            ),
        )
        val own = tools.answerAll(message) { call ->
            if (call.function.name == "get_time") "12:00 in Paris" else "Error: no tool is named ${call.function.name}."
        }
        assertEquals(listOf("a", "b", "c").map(::ToolId), own.map { it.toolCallId })
        assertEquals(
            listOf("12:00 in Paris", "Hunter sells no API on Datacircle.", "Error: no tool is named no_such_tool."),
            own.map { it.content },
        )
        assertTrue(own.all { it.role == ChatRole.Tool })
        assertEquals(
            listOf("Error: no tool is named get_time.", "Hunter sells no API on Datacircle.", "Error: no tool is named no_such_tool."),
            tools.answerAll(message).map { it.content },
        )
        assertEquals(emptyList(), tools.answerAll(ChatMessage(role = ChatRole.Assistant, content = "Hello.")))
    }

    @Test
    fun readsTheModelsArguments() = runBlocking {
        standIn.routes["/plans?sort=price&limit=3&offset=0&query=hunter"] = 200 to DatacircleToolsTest.SEARCH
        standIn.routes["/plans?sort=score&limit=10&offset=0"] = 200 to DatacircleToolsTest.SEARCH
        standIn.routes["/apis"] = 200 to DatacircleToolsTest.APIS
        // A number given as a string, nulls and keys it doesn't know are read as a model sends them.
        val search = """{"query": "hunter", "sort": "price", "limit": "3", "offset": null, "extra": 1}"""
        assertTrue(tools.call("datacircle_search_plans", search)!!.startsWith("Found 7 plans"))
        assertTrue(tools.call("datacircle_search_plans", "")!!.startsWith("Found 7 plans"))
        assertTrue(tools.call("datacircle_list_apis", null)!!.startsWith("- Employee email"))
        assertTrue(tools.call("datacircle_list_apis", "{}")!!.startsWith("- Employee email"))
        assertEquals(
            listOf("/plans?sort=price&limit=3&offset=0&query=hunter", "/plans?sort=score&limit=10&offset=0", "/apis", "/apis"),
            standIn.apiRequests,
        )
    }

    @Test
    fun saysWhatIsWrongWithTheArguments() = runBlocking {
        assertNull(tools.call("get_time", "{}"))
        assertNull(tools.call(null, "{}"))
        assertEquals("Error: the arguments of datacircle_provider are not valid JSON.", tools.call("datacircle_provider", "{provider"))
        assertEquals("Error: the arguments of datacircle_provider must be a JSON object.", tools.call("datacircle_provider", "[1]"))
        assertEquals("Error: provider must not be empty", tools.call("datacircle_provider", "{}"))
        assertEquals("Error: provider must be a string", tools.call("datacircle_provider", """{"provider": true}"""))
        assertEquals("Error: api must not be empty", tools.call("datacircle_best_plans", """{"limit": 3}"""))
        assertEquals(
            "Error: limit must be a whole number",
            tools.call("datacircle_best_plans", """{"api": "email-finder", "limit": 2.5}"""),
        )
        assertEquals(
            "Error: limit must be between 1 and 50",
            tools.call("datacircle_best_plans", """{"api": "email-finder", "limit": 0}"""),
        )
        assertEquals(emptyList(), standIn.apiRequests)
    }

    @Test
    fun saysWhenTheApiDoesNotAnswerInTime() = runBlocking {
        // A socket that is never read: the connection opens, and no answer comes.
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { socket ->
            val slow = DatacircleTools("http://127.0.0.1:${socket.localPort}/v1", 1.seconds)
            assertEquals("Error: The Datacircle API did not answer within 1 s. Retry in a moment.", slow.listApis())
        }
        val unknownHost = DatacircleTools("https://no-such-host.invalid/v1", 5.seconds)
        assertEquals(
            "Error: The Datacircle API's host no-such-host.invalid could not be found. Check the network and the base URL.",
            unknownHost.listApis(),
        )
    }

    private fun call(id: String, name: String, arguments: String): ToolCall =
        ToolCall.Function(id = ToolId(id), function = FunctionCall(nameOrNull = name, argumentsOrNull = arguments))
}
