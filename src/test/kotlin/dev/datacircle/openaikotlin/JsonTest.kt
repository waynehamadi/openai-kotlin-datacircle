package dev.datacircle.openaikotlin

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class JsonTest {

    @Test
    fun readsObjectsArraysAndValues() {
        val value = Json.parse("""{"a": [1, 2.5, -3e2], "b": "x\"yé\n", "c": true, "d": null, "e": {}}""")
        assertEquals(
            mapOf(
                "a" to listOf(BigDecimal("1"), BigDecimal("2.5"), BigDecimal("-3e2")),
                "b" to "x\"yé\n",
                "c" to true,
                "d" to null,
                "e" to emptyMap<String, Any?>(),
            ),
            value,
        )
        assertNull(Json.parse(" null "))
        assertEquals(emptyList<Any?>(), Json.parse("[]"))
    }

    @Test
    fun refusesWhatIsNotJson() {
        for (text in listOf("", "{", "[1,]", "{\"a\" 1}", "\"open", "tru", "1 2", "<html>")) {
            assertFailsWith<IllegalArgumentException>(text) { Json.parse(text) }
        }
    }
}
