# Datacircle tools for openai-kotlin

Find the best GTM APIs. We benchmark all the GTM APIs and we put the results on our website.

Our website: https://datacircle.dev

This library gives [openai-kotlin](https://github.com/aallam/openai-kotlin) four function tools that call Datacircle's open API, https://api.datacircle.dev/v1. You put them in a chat completion request beside your app's own tools. You need no key and no account.

| Tool | What the tool gives | Call |
| --- | --- | --- |
| `datacircle_list_apis` | Every GTM API on Datacircle, with the API's slug, number of plans, number of providers and best plan | `GET /apis` |
| `datacircle_best_plans` | One API's plans, sorted by score out of 100, best first. Each plan comes with a rank and a price per 1,000 calls | `GET /apis/{slug}/best` |
| `datacircle_search_plans` | Plans that match a provider or plan name, on all APIs or on one API. You can sort them by score, provider or price | `GET /plans` |
| `datacircle_provider` | The APIs a provider sells, and the provider's rank on each API | `GET /providers/{slug}` |

## Install

The library is on [JitPack](https://jitpack.io/#waynehamadi/openai-kotlin-datacircle). With Gradle:

```kotlin
repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.aallam.openai:openai-client:4.2.0")
    runtimeOnly("io.ktor:ktor-client-okhttp:3.0.0")
    implementation("com.github.waynehamadi:openai-kotlin-datacircle:0.1.0")
}
```

With Maven:

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.waynehamadi</groupId>
    <artifactId>openai-kotlin-datacircle</artifactId>
    <version>0.1.0</version>
</dependency>
```

The library uses your app's own openai-kotlin client. You need openai-kotlin 3.7.0 or later and Java 11 or later. The library adds no other dependency. In a multiplatform project, add the library to the JVM source set.

## Use the tools in a chat

```kotlin
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import dev.datacircle.openaikotlin.DatacircleTools

suspend fun main() {
    val openAI = OpenAI(System.getenv("OPENAI_API_KEY"))
    val datacircle = DatacircleTools()
    val messages = mutableListOf(
        ChatMessage(role = ChatRole.User, content = "Who are the 3 best providers for the email-finder API?"),
    )
    while (true) {
        val request = ChatCompletionRequest(
            model = ModelId("gpt-4o-mini"),
            messages = messages.toList(),
            tools = datacircle.tools,
        )
        val message = openAI.chatCompletion(request).choices.first().message
        messages += message
        val answers = datacircle.answerAll(message)
        if (answers.isEmpty()) {
            println(message.content)
            break
        }
        messages += answers
    }
}
```

`answerAll(message)` gives one tool message for each tool call in the model's message. For each call to a Datacircle tool, `answerAll` calls the Datacircle API.

## Add your own tools

Add your tools to the Datacircle tools. Then give `answerAll` your own answers for calls to your tools:

```kotlin
val getTime = Tool.function(
    name = "get_time",
    description = "The time in a city",
    parameters = Parameters.fromJsonString(
        """{"type": "object", "properties": {"city": {"type": "string"}}, "required": ["city"]}""",
    ),
)
val tools = datacircle.tools + getTime

// In the loop:
val answers = datacircle.answerAll(message) { call ->
    when (call.function.name) {
        "get_time" -> clock.timeIn(call.function.argumentsAsJson()["city"]?.jsonPrimitive?.content.orEmpty())
        else -> "Error: no tool is named ${call.function.name}."
    }
}
```

`datacircle.answer(toolCall)` answers one call and gives back the tool message to send. `datacircle.answer(toolCall)` gives `null` for a call to one of your tools. In a loop of your own, `datacircle.call(name, arguments)` takes the function's name and the function's JSON arguments. `datacircle.call` gives back the answer as text.

## Streaming

openai-kotlin's `mergeToChatMessage()` turns a stream's chunks into one message. `mergeToChatMessage()` also joins the pieces of each tool call back together. `answerAll` answers each tool call in the message that `mergeToChatMessage()` returns. This function streams one turn and merges the chunks:

```kotlin
@OptIn(ExperimentalOpenAI::class)
suspend fun streamedTurn(openAI: OpenAI, request: ChatCompletionRequest): ChatMessage =
    openAI.chatCompletions(request).toList().flatMap { it.choices }.mergeToChatMessage()
```

## Tool answers

Each tool answers in short text, one line per API or plan. Each plan's line links to the plan's page on datacircle.dev. For example, `datacircle_best_plans` with `{"api": "email-finder", "limit": 3}` answers:

```text
Employee email (email-finder): the best 3 of 5,451 plans. Sheet: https://datacircle.dev/email-finder-api-pricing
1. Emelia, 100,000 credits per month: score 76.6/100, $4.99 per 1,000 calls, $0 per 1,000 not found, monthly. https://datacircle.dev/find-email-apis/emelia/19-monthly
2. ...
```

If a call fails, the tool answers with a line that starts with `Error:` and says what to do. A slug that does not exist gets this kind of answer. The tool does not throw. The model reads the line and can try again.

## Options

`DatacircleTools(baseUrl, timeout)` sets another base URL or another timeout. The defaults are `https://api.datacircle.dev/v1` and 30 seconds.

The API allows 60 calls a minute from one address. The API's spec is at https://api.datacircle.dev/v1/openapi.json.

## Tests

```bash
./gradlew test                     # offline, on a local server and a scripted model
DATACIRCLE_LIVE=1 ./gradlew test   # also calls each tool on the live API
./gradlew test -Popenai=3.7.0      # on another openai-kotlin
```

## License

MIT
