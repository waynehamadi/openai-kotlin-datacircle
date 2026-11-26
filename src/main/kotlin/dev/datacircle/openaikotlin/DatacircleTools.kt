package dev.datacircle.openaikotlin

import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.chat.ToolCall
import com.aallam.openai.api.core.Parameters
import java.math.BigDecimal
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Function tools for [openai-kotlin](https://github.com/aallam/openai-kotlin) on the open
 * [Datacircle](https://datacircle.dev) API, which benchmarks the GTM APIs (email finders, company enrichment, LinkedIn
 * data and more) and ranks every plan of each by its score out of 100 and its price per 1,000.
 *
 * Put [tools] in a chat completion request beside the app's own tools, then give the model's message to [answerAll]:
 * it answers each of the four tools' calls by the API, and the app's own by its function. [answer] answers one call.
 * The API needs no key and no account. It allows 60 calls a minute from one address. Its spec is at
 * `https://api.datacircle.dev/v1/openapi.json`.
 *
 * ```kotlin
 * val datacircle = DatacircleTools()
 * val request = ChatCompletionRequest(model = ModelId("gpt-4o-mini"), messages = messages, tools = datacircle.tools)
 * val message = openAI.chatCompletion(request).choices.first().message
 * messages += message
 * messages += datacircle.answerAll(message)
 * ```
 *
 * @param baseUrl the API's base URL, `https://api.datacircle.dev/v1` by default
 * @param timeout the connection and read timeout, 30 s by default
 */
public class DatacircleTools(
    baseUrl: String = DEFAULT_BASE_URL,
    timeout: Duration = 30.seconds,
) {

    private val client = DatacircleClient(baseUrl, timeout)

    /** The four tools, for a chat completion request's `tools` beside the app's own. */
    public val tools: List<Tool> = listOf(
        Tool.function(
            name = LIST_APIS,
            description = "List the GTM APIs that Datacircle ranks (email finder, company enrichment, LinkedIn " +
                "profile and more), each with its slug, how many plans and providers it has, and its best plan.",
            parameters = Parameters.fromJsonString(
                """{"type": "object", "properties": {"query": {"type": "string", "description": "Words that must """ +
                    """all appear in the API's name or slug, like email or linkedin. Leave empty to list every API"}}}"""
            ),
        ),
        Tool.function(
            name = BEST_PLANS,
            description = "Get the best plans of one GTM API, best first by Datacircle's score out of 100, with " +
                "each plan's price per 1,000 and its page.",
            parameters = Parameters.fromJsonString(
                """{"type": "object", "properties": {"api": {"type": "string", "description": "The API's slug, as """ +
                    """datacircle_list_apis gives it, like email-finder"}, "limit": {"type": "integer", "minimum": 1, """ +
                    """"maximum": 50, "description": "Number of plans from 1 through 50. Defaults to 10"}}, """ +
                    """"required": ["api"]}"""
            ),
        ),
        Tool.function(
            name = SEARCH_PLANS,
            description = "Search the plans of the GTM APIs on Datacircle by API, and by words in the provider's " +
                "or plan's name, sorted by score (best first), provider (A to Z) or price (lowest $ per 1,000 first).",
            parameters = Parameters.fromJsonString(
                """{"type": "object", "properties": {"api": {"type": "string", "description": "The API's name or """ +
                    """slug, like email-finder. A provider's name goes in query. Defaults to every API"}, "query": """ +
                    """{"type": "string", "description": "Words that must all appear in the provider's or plan's """ +
                    """name, like hunter"}, "sort": {"type": "string", "enum": ["score", "provider", "price"], """ +
                    """"description": "Sort: score, provider or price. Defaults to score"}, "limit": {"type": """ +
                    """"integer", "minimum": 1, "maximum": 50, "description": "Number of plans from 1 through 50. """ +
                    """Defaults to 10"}, "offset": {"type": "integer", "minimum": 0, "description": "Number of """ +
                    """found plans to skip first. Defaults to 0"}}}"""
            ),
        ),
        Tool.function(
            name = PROVIDER,
            description = "Get one provider's rank on each GTM API it sells on Datacircle, with its best plan " +
                "there. A provider is a company that sells the APIs, like Hunter or Bright Data.",
            parameters = Parameters.fromJsonString(
                """{"type": "object", "properties": {"provider": {"type": "string", "description": "The """ +
                    """provider's name or slug, like Bright Data or bright-data"}}, "required": ["provider"]}"""
            ),
        ),
    )

    /**
     * Answers one tool call of the model's message.
     *
     * @return the tool message with the tool's answer, or `null` when the call is not one of the four tools
     */
    public suspend fun answer(toolCall: ToolCall): ChatMessage? {
        val function = toolCall as? ToolCall.Function ?: return null
        val content = call(function.function.nameOrNull, function.function.argumentsOrNull) ?: return null
        return ChatMessage.Tool(content = content, toolCallId = function.id)
    }

    /**
     * Answers every tool call of the model's message, in its order: the four tools by the API, any other by [own].
     *
     * @param message the model's message, as `chatCompletion` gives it or `mergeToChatMessage()` merges it from a stream
     * @param own the answer of a tool of the app's own; by default a line that says no tool has that name
     * @return one tool message for each tool call, empty when the message calls no tool
     */
    public suspend fun answerAll(
        message: ChatMessage,
        own: suspend (ToolCall.Function) -> String = { "Error: no tool is named ${it.function.nameOrNull.orEmpty()}." },
    ): List<ChatMessage> = message.toolCalls.orEmpty().mapNotNull { toolCall ->
        val function = toolCall as? ToolCall.Function ?: return@mapNotNull null
        answer(function) ?: ChatMessage.Tool(content = own(function), toolCallId = function.id)
    }

    /**
     * Answers one function call from its name and its arguments' JSON, for a loop of the app's own.
     *
     * @return the tool's answer, or `null` when the name is none of the four tools
     */
    public suspend fun call(name: String?, arguments: String?): String? {
        if (name == null || name !in NAMES) {
            return null
        }
        val args = try {
            if (arguments.isNullOrBlank()) emptyMap<String, Any?>() else Json.parse(arguments)
        } catch (e: IllegalArgumentException) {
            return "Error: the arguments of $name are not valid JSON."
        }
        if (args !is Map<*, *>) {
            return "Error: the arguments of $name must be a JSON object."
        }
        return try {
            when (name) {
                LIST_APIS -> listApis(string(args, "query"))
                BEST_PLANS -> bestPlans(string(args, "api") ?: "", integer(args, "limit"))
                SEARCH_PLANS -> searchPlans(
                    api = string(args, "api"),
                    query = string(args, "query"),
                    sort = string(args, "sort"),
                    limit = integer(args, "limit"),
                    offset = integer(args, "offset"),
                )
                else -> provider(string(args, "provider") ?: "")
            }
        } catch (e: IllegalArgumentException) {
            Format.formatError(e)
        }
    }

    /**
     * `datacircle_list_apis`: the GTM APIs Datacircle ranks, each with its slug, its counts and its best plan.
     *
     * @param query words that must all appear in the API's name or slug, or `null` for every API
     * @return the APIs, one a line, or a line that starts with `Error:`
     */
    public suspend fun listApis(query: String? = null): String =
        respond { Format.formatApis(client.listApis(), query) }

    /**
     * `datacircle_best_plans`: one API's best plans, best first by score.
     *
     * @param api the API's slug, like `email-finder`
     * @param limit the number of plans, 1 through 50, 10 when `null`
     * @return the ranked plans, one a line, or a line that starts with `Error:`
     */
    public suspend fun bestPlans(api: String, limit: Int? = null): String = respond(
        hint = { if (Format.isStatus(it, 404)) " Call datacircle_list_apis for every API's slug." else "" },
    ) { Format.formatBestPlans(client.bestPlans(api, limit)) }

    /**
     * `datacircle_search_plans`: the plans of every API, or of one, by words in the provider's or plan's name.
     *
     * @param api the API's name or slug, or `null` for every API
     * @param query words that must all appear in the provider's or plan's name
     * @param sort `score` (best first, the default), `provider` (A to Z) or `price` (lowest first)
     * @param limit the number of plans, 1 through 50, 10 when `null`
     * @param offset the number of found plans to skip first, 0 when `null`
     * @return the plans found, one a line, or a line that starts with `Error:`
     */
    public suspend fun searchPlans(
        api: String? = null,
        query: String? = null,
        sort: String? = null,
        limit: Int? = null,
        offset: Int? = null,
    ): String = respond(
        hint = {
            val unknownApi = !api.isNullOrBlank() && (Format.isStatus(it, 400) || Format.isStatus(it, 404))
            if (unknownApi) " Call datacircle_list_apis for every API's name and slug." else ""
        },
    ) { Format.formatSearch(client.searchPlans(api, query, sort, limit, offset), offset ?: 0) }

    /**
     * `datacircle_provider`: one provider's rank on each API it sells, with its best plan there.
     *
     * @param provider the provider's name or slug, like `Bright Data` or `bright-data`
     * @return the provider's ranks, one API a line, or a line that starts with `Error:`
     */
    public suspend fun provider(provider: String): String =
        respond { Format.formatProvider(client.provider(provider)) }

    public companion object {
        /** The API's base URL. */
        public const val DEFAULT_BASE_URL: String = "https://api.datacircle.dev/v1"

        private const val LIST_APIS = "datacircle_list_apis"
        private const val BEST_PLANS = "datacircle_best_plans"
        private const val SEARCH_PLANS = "datacircle_search_plans"
        private const val PROVIDER = "datacircle_provider"

        /** The four tools' names. */
        public val NAMES: Set<String> = linkedSetOf(LIST_APIS, BEST_PLANS, SEARCH_PLANS, PROVIDER)

        /** Runs a tool, and turns its failure into a line that starts with `Error:`, so that the model can act on it. */
        private inline fun respond(hint: (RuntimeException) -> String = { "" }, block: () -> String): String =
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                Format.formatError(e) + hint(e)
            }

        private fun string(args: Map<*, *>, name: String): String? =
            when (val value = args[name]) {
                null -> null
                is String -> value
                is BigDecimal -> value.toPlainString()
                else -> throw IllegalArgumentException("$name must be a string")
            }

        private fun integer(args: Map<*, *>, name: String): Int? {
            val value = args[name]
            if (value == null || value is String && value.isBlank()) {
                return null
            }
            return try {
                (value as? BigDecimal ?: BigDecimal((value as String).trim())).intValueExact()
            } catch (e: ArithmeticException) {
                throw IllegalArgumentException("$name must be a whole number")
            } catch (e: ClassCastException) {
                throw IllegalArgumentException("$name must be a whole number")
            } catch (e: NumberFormatException) {
                throw IllegalArgumentException("$name must be a whole number")
            }
        }
    }
}
