package com.acme.repost

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MatrixModelBehaviorTest {
    @Test
    fun `immutable model snapshots collections and preserves presence-aware value behavior`() {
        val sourceList = mutableListOf("before")
        val nestedJson = mutableListOf<Any?>("before")
        val sourceJson = linkedMapOf<String, Any?>("nested" to nestedJson)

        val absent = matrix(sourceList, sourceJson)
        sourceList += "after"
        nestedJson += "after"
        sourceJson["later"] = true

        assertEquals(listOf("before"), absent.requiredList)
        assertEquals(mapOf("nested" to listOf("before")), absent.requiredJson)
        assertFalse(absent.hasNullableValue())
        assertFalse(absent.hasDefaultedValue())
        assertFalse(absent.hasNullableDefaultedValue())
        assertFalse(absent.hasNullableJson())
        assertFalse(absent.hasNullableList())

        val explicitNull = absent.toBuilder().apply {
            nullableValue = null
            defaultedValue = "fallback"
            nullableDefaultedValue = null
            nullableJson = null
            nullableList = null
        }.build()
        assertTrue(explicitNull.hasNullableValue())
        assertTrue(explicitNull.hasDefaultedValue())
        assertTrue(explicitNull.hasNullableDefaultedValue())
        assertTrue(explicitNull.hasNullableJson())
        assertTrue(explicitNull.hasNullableList())
        assertNotEquals(absent, explicitNull)
        assertNotEquals(absent.hashCode(), explicitNull.hashCode())

        val copied = explicitNull.toBuilder().build()
        assertEquals(explicitNull, copied)
        assertEquals(explicitNull.hashCode(), copied.hashCode())
        assertTrue(copied.hasNullableValue())
        assertTrue(copied.hasDefaultedValue())
        assertTrue(copied.hasNullableDefaultedValue())
        assertTrue(copied.hasNullableJson())
        assertTrue(copied.hasNullableList())

        val expectedDebug =
            "Matrix{setFields=[requiredValue, requiredJson, requiredList, booleanValue, intValue, " +
                "floatValue, dateTimeValue, child, status]}"
        assertEquals(expectedDebug, absent.toString())
        assertFalse(absent.toString().contains("payload-secret"))
        assertFalse(absent.toString().contains("before"))
    }

    private fun matrix(sourceList: List<String>, sourceJson: Any?): Matrix = Matrix {
        requiredValue = "payload-secret"
        requiredJson = sourceJson
        requiredList = sourceList
        booleanValue = true
        intValue = 42
        floatValue = 4.2
        dateTimeValue = Instant.parse("2026-01-01T00:00:00Z")
        child = Child { name = "payload-secret" }
        status = Status.ACTIVE
    }
}
