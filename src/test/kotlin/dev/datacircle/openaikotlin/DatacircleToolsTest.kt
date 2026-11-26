package dev.datacircle.openaikotlin

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Each tool on a local server that answers as the API does. */
class DatacircleToolsTest {

    private lateinit var server: HttpServer
    private val requests = CopyOnWriteArrayList<String>()
    private val routes = mutableMapOf<String, Pair<Int, String>>()
    private lateinit var tools: DatacircleTools

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1") { exchange ->
            val target = exchange.requestURI.rawPath.removePrefix("/v1") +
                (exchange.requestURI.rawQuery?.let { "?$it" } ?: "")
            requests += target
            val (status, body) = routes[target] ?: (404 to """{"error": "no route $target"}""")
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        tools = DatacircleTools("http://127.0.0.1:${server.address.port}/v1/", 5.seconds)
    }

    @AfterTest
    fun stop() {
        server.stop(0)
    }

    @Test
    fun listsAndFiltersTheApis() = runBlocking {
        routes["/apis"] = 200 to APIS
        val all = tools.listApis()
        assertEquals(
            """
            - Employee email (email-finder): 5,444 plans from 512 providers. Best: Emelia, 100,000 credits per month, score 76.6/100, ${'$'}4.99 per 1,000.
            - Employee profile (linkedin-profile): 1 plan from 1 provider.
            2 of 2 APIs. Pass a slug to datacircle_best_plans or datacircle_search_plans.
            """.trimIndent(),
            all,
        )
        assertTrue(tools.listApis("LINKEDIN profile").startsWith("- Employee profile (linkedin-profile)"))
        assertEquals(
            "No API's name or slug holds every word of \"fax\". Leave the query empty to list 2 APIs.",
            tools.listApis(" fax "),
        )
    }

    @Test
    fun readsTheBestPlansOfOneApi() = runBlocking {
        routes["/apis/email-finder/best?limit=2"] = 200 to BEST
        assertEquals(
            """
            Employee email (email-finder): the best 2 of 5,444 plans. Sheet: https://datacircle.dev/email-finder-api-pricing
            1. Emelia, 100,000 credits per month: score 76.6/100, ${'$'}4.99 per 1,000, ${'$'}0 per 1,000 not found, monthly, median 812 ms. https://datacircle.dev/find-email-apis/emelia/19-monthly
            2. Hunter, Growth: not scored, no listed price.
            """.trimIndent(),
            tools.bestPlans(" Email-Finder ", 2),
        )
        routes["/apis/fax/best?limit=10"] = 404 to """{"error": "no API has the slug fax"}"""
        assertEquals(
            "Error: Datacircle API request failed: HTTP 404. no API has the slug fax. " +
                "Call datacircle_list_apis for every API's slug.",
            tools.bestPlans("fax"),
        )
        assertEquals("Error: limit must be between 1 and 50", tools.bestPlans("email-finder", 51))
        assertEquals("Error: api must not be empty", tools.bestPlans(" "))
    }

    @Test
    fun searchesThePlans() = runBlocking {
        routes["/plans?sort=price&limit=1&offset=3&api=Employee+email&query=hunter+%26+co"] = 200 to SEARCH
        assertEquals(
            """
            Found 7 plans; showing 4 to 4.
            - Hunter, Starter (Employee email): rank 12, score 61/100, ${'$'}24.5 per 1,000 pages, yearly.
            """.trimIndent(),
            tools.searchPlans(api = "Employee email", query = "hunter & co", sort = " PRICE ", limit = 1, offset = 3),
        )
        routes["/plans?sort=score&limit=10&offset=50"] = 200 to """{"total": 7, "offset": 50, "plans": []}"""
        assertEquals(
            "Found 7 plans, but the offset 50 is past the last one. Use an offset below 7.",
            tools.searchPlans(offset = 50),
        )
        routes["/plans?sort=score&limit=10&offset=0&api=fax"] = 400 to """{"error": "api must be one of: Employee email"}"""
        assertEquals(
            "Error: Datacircle API request failed: HTTP 400. api must be one of: Employee email. " +
                "Call datacircle_list_apis for every API's name and slug.",
            tools.searchPlans(api = "fax"),
        )
        assertEquals("Error: sort must be one of [price, provider, score]", tools.searchPlans(sort = "cheapest"))
        assertEquals("Error: offset must be 0 or more", tools.searchPlans(offset = -1))
    }

    @Test
    fun readsAProviderByItsName() = runBlocking {
        routes["/providers/bright-data"] = 200 to PROVIDER
        assertEquals(
            """
            Bright Data (https://brightdata.com) sells 2 APIs. Its rank among each API's providers:
            - Employee profile (linkedin-profile): rank 3, 2 plans. Best: Scale, score 70.3/100, ${'$'}1.5 per 1,000 records.
            - Web scraping (web-scraping): not ranked, 0 plans.
            """.trimIndent(),
            tools.provider("Bright Dátá"),
        )
        assertEquals(listOf("/providers/bright-data"), requests)
        assertTrue(tools.provider("微博").startsWith("Error: Datacircle writes each provider's name in Latin letters"))
    }

    @Test
    fun saysWhyACallFailed() = runBlocking {
        routes["/apis"] = 429 to ""
        assertEquals(
            "Error: Datacircle API request failed: HTTP 429. The API allows 60 calls a minute from one address. " +
                "Retry in a minute.",
            tools.listApis(),
        )
        routes["/apis"] = 200 to "<html>"
        assertEquals("Error: The Datacircle API returned invalid JSON.", tools.listApis())
        routes["/apis"] = 200 to """{"apis": []}"""
        assertTrue(tools.listApis().startsWith("Error: The Datacircle API returned an answer of an unexpected shape"))

        val closedPort = ServerSocket(0).use { it.localPort }
        val offline = DatacircleTools("http://127.0.0.1:$closedPort/v1", 5.seconds)
        assertEquals(
            "Error: The Datacircle API refused the connection. Check the network and the base URL.",
            offline.listApis(),
        )
    }

    internal companion object {
        val APIS = """
            [{"api": "Employee email", "slug": "email-finder", "plans": 5444, "providers": 512,
              "best": {"provider": "Emelia", "plan": "100,000 credits per month", "score": 76.62, "perThousand": 4.99}},
             {"api": "Employee profile", "slug": "linkedin-profile", "plans": 1, "providers": 1, "best": null}]
        """.trimIndent()

        val BEST = """
            {"api": "Employee email", "slug": "email-finder", "sheetPage": "https://datacircle.dev/email-finder-api-pricing",
             "total": 5444, "plans": [
              {"rank": 1, "provider": "Emelia", "plan": "100,000 credits per month", "api": "Employee email", "score": 76.62,
               "perThousand": 4.99, "notFoundPerThousand": 0, "billing": "monthly", "latencyMs": 812.4,
               "page": "https://datacircle.dev/find-email-apis/emelia/19-monthly"},
              {"rank": 2, "provider": "Hunter", "plan": "Growth", "api": "Employee email", "score": null, "perThousand": null}]}
        """.trimIndent()

        val SEARCH = """
            {"total": 7, "offset": 3, "plans": [
              {"rank": 12, "provider": "Hunter", "plan": "Starter", "api": "Employee email", "score": 61.0,
               "perThousand": 24.50, "per": "page", "billing": "yearly"}]}
        """.trimIndent()

        val PROVIDER = """
            {"provider": "Bright Data", "slug": "bright-data", "website": "https://brightdata.com", "apis": [
              {"api": "Employee profile", "slug": "linkedin-profile", "rank": 3, "plans": [
                {"plan": "Growth", "score": 64.0, "perThousand": 2.5, "per": "record"},
                {"plan": "Scale", "score": 70.34, "perThousand": 1.5, "per": "record"}]},
              {"api": "Web scraping", "slug": "web-scraping", "rank": null, "plans": []}]}
        """.trimIndent()
    }
}
