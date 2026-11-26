package dev.datacircle.openaikotlin

import com.aallam.openai.api.ExperimentalOpenAI
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.ToolCall
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import com.aallam.openai.client.OpenAIHost
import com.aallam.openai.client.extension.mergeToChatMessage
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Each tool on the live API, and on a real model through openai-kotlin's chat completions.
 *
 * Runs with `DATACIRCLE_LIVE=1`; the model test also needs an Ollama API key in `OLLAMA_API_KEY`, for gpt-oss:120b on
 * ollama.com's OpenAI-compatible endpoint (`OLLAMA_BASE_URL` and `OLLAMA_MODEL` change them).
 */
@OptIn(ExperimentalOpenAI::class)
class DatacircleToolsLiveTest {

    private val tools = DatacircleTools()

    @Test
    fun eachToolAnswersOnTheLiveApi() = runBlocking {
        assumeTrue(System.getenv("DATACIRCLE_LIVE") == "1", "set DATACIRCLE_LIVE=1 to call the live API")
        val calls = listOf(
            "datacircle_list_apis" to """{"query": "email"}""",
            "datacircle_best_plans" to """{"api": "email-finder", "limit": 3}""",
            "datacircle_search_plans" to """{"query": "hunter", "sort": "price", "limit": 3}""",
            "datacircle_provider" to """{"provider": "Bright Data"}""",
        )
        for ((name, arguments) in calls) {
            val answer = tools.call(name, arguments)!!
            println("[$name $arguments]\n${answer.lines().take(4).joinToString("\n")}\n")
            assertTrue(!answer.startsWith("Error"), answer)
        }
        assertTrue(tools.bestPlans("email-finder", 3).contains("https://datacircle.dev/"))
        assertTrue(tools.bestPlans("no-such-api").contains("Call datacircle_list_apis"))
    }

    @Test
    fun aModelCallsEachTool() = runBlocking {
        assumeTrue(System.getenv("DATACIRCLE_LIVE") == "1", "set DATACIRCLE_LIVE=1 to call the live API")
        val key = System.getenv("OLLAMA_API_KEY")
        assumeTrue(!key.isNullOrBlank(), "set OLLAMA_API_KEY to run the tools on a model")
        val openAI = OpenAI(
            OpenAIConfig(
                token = key,
                host = OpenAIHost(baseUrl = System.getenv("OLLAMA_BASE_URL") ?: "https://ollama.com/v1/"),
                timeout = com.aallam.openai.api.http.Timeout(socket = 180.seconds),
            ),
        )
        val model = ModelId(System.getenv("OLLAMA_MODEL") ?: "gpt-oss:120b")
        // Each question names its tool, so that a run calls each of the four at least once; half of them streamed.
        val questions = listOf(
            "datacircle_list_apis" to "With datacircle_list_apis: which GTM APIs on Datacircle have linkedin in their name?",
            "datacircle_best_plans" to "With datacircle_best_plans: who are the 3 best providers of the email-finder API?",
            "datacircle_search_plans" to "With datacircle_search_plans: list Hunter's 3 cheapest plans, every API together.",
            "datacircle_provider" to "With datacircle_provider: on which APIs does Bright Data rank on Datacircle, and at what rank?",
        )
        val missed = mutableListOf<String>()
        for ((index, question) in questions.withIndex()) {
            val stream = index % 2 == 1
            val messages = mutableListOf(
                ChatMessage(role = ChatRole.System, content = "Answer in a few lines from the Datacircle tools' answers only."),
                ChatMessage(role = ChatRole.User, content = question.second),
            )
            val called = mutableListOf<Pair<String, String>>()
            var final = ""
            for (turn in 1..6) {
                val request = ChatCompletionRequest(model = model, messages = messages.toList(), tools = tools.tools)
                val message = if (stream) {
                    openAI.chatCompletions(request).toList().flatMap { it.choices }.mergeToChatMessage()
                } else {
                    openAI.chatCompletion(request).choices.first().message
                }
                messages += message
                val toolCalls = message.toolCalls.orEmpty()
                if (toolCalls.isEmpty()) {
                    final = message.content.orEmpty()
                    break
                }
                for (toolCall in toolCalls) {
                    val answer = tools.answer(toolCall)!!
                    called += (toolCall as ToolCall.Function).function.name to answer.content.orEmpty()
                    messages += answer
                }
            }
            println("[${if (stream) "streamed" else "plain"}: ${question.second}]")
            called.forEach { println("  ${it.first}: ${it.second.lines().first()}") }
            println("  -> ${final.lines().take(3).joinToString(" / ")}\n")
            if (called.none { it.first == question.first && !it.second.startsWith("Error") } || final.isBlank()) {
                missed += question.first
            }
        }
        openAI.close()
        assertTrue(missed.isEmpty(), "not called with an answer, or no final answer: $missed")
    }
}
