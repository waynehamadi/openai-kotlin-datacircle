package dev.datacircle.openaikotlin

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One local server on 127.0.0.1: the API's calls under `/v1`, as the API answers them, and a scripted
 * OpenAI-compatible model under `/model/v1/chat/completions`.
 *
 * The model answers its first turn with [toolCalls] (name and arguments' JSON each), split into pieces when streamed,
 * and a turn whose last message is a tool's with the text "Done.". It keeps every request's body in [modelBodies].
 */
class StandIn(private val toolCalls: List<Pair<String, String>> = emptyList()) : AutoCloseable {

    val apiRequests = CopyOnWriteArrayList<String>()
    val routes = mutableMapOf<String, Pair<Int, String>>()
    val modelBodies = CopyOnWriteArrayList<Map<*, *>>()

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v1") { exchange -> api(exchange) }
        createContext("/model/v1/chat/completions") { exchange -> model(exchange) }
        start()
    }

    val apiUrl: String = "http://127.0.0.1:${server.address.port}/v1"
    val modelUrl: String = "http://127.0.0.1:${server.address.port}/model/v1/"

    private fun api(exchange: HttpExchange) {
        val target = exchange.requestURI.rawPath.removePrefix("/v1") +
            (exchange.requestURI.rawQuery?.let { "?$it" } ?: "")
        apiRequests += target
        val (status, body) = routes[target] ?: (404 to """{"error": "no route $target"}""")
        send(exchange, status, "application/json", body)
    }

    private fun model(exchange: HttpExchange) {
        val body = Json.parse(exchange.requestBody.readBytes().toString(Charsets.UTF_8)) as Map<*, *>
        modelBodies += body
        val messages = body["messages"] as List<*>
        val toolTurn = (messages.last() as Map<*, *>)["role"] == "tool"
        val stream = body["stream"] == true
        if (!stream) {
            val message = if (toolTurn) """{"role": "assistant", "content": "Done."}""" else
                """{"role": "assistant", "content": null, "tool_calls": [""" +
                toolCalls.withIndex().joinToString(", ") { (i, call) ->
                    """{"id": "call_$i", "type": "function", "function": {"name": "${call.first}", """ +
                        """"arguments": ${quote(call.second)}}}"""
                } + "]}"
            val reason = if (toolTurn) "stop" else "tool_calls"
            send(
                exchange, 200, "application/json",
                """{"id": "chatcmpl-1", "object": "chat.completion", "created": 1, "model": "scripted", "choices": """ +
                    """[{"index": 0, "message": $message, "finish_reason": "$reason"}], "usage": """ +
                    """{"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}}""",
            )
            return
        }
        val deltas = mutableListOf<String>()
        if (toolTurn) {
            deltas += """{"role": "assistant", "content": "Do"}"""
            deltas += """{"content": "ne."}"""
        } else {
            deltas += """{"role": "assistant", "content": null}"""
            for ((i, call) in toolCalls.withIndex()) {
                deltas += """{"tool_calls": [{"index": $i, "id": "call_$i", "type": "function", "function": """ +
                    """{"name": "${call.first}", "arguments": ""}}]}"""
                // The arguments come in pieces of 7 characters, as a model streams them.
                for (piece in call.second.chunked(7)) {
                    deltas += """{"tool_calls": [{"index": $i, "function": {"arguments": ${quote(piece)}}}]}"""
                }
            }
        }
        val chunks = deltas.map { delta -> chunk("""{"index": 0, "delta": $delta, "finish_reason": null}""") } +
            chunk("""{"index": 0, "delta": {}, "finish_reason": "${if (toolTurn) "stop" else "tool_calls"}"}""")
        send(exchange, 200, "text/event-stream", chunks.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n")
    }

    private fun chunk(choice: String) =
        """{"id": "chatcmpl-1", "object": "chat.completion.chunk", "created": 1, "model": "scripted", "choices": [$choice]}"""

    private fun send(exchange: HttpExchange, status: Int, type: String, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", type)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    override fun close() {
        server.stop(0)
    }

    companion object {
        fun quote(text: String): String =
            "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    }
}
