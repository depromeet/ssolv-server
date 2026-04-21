package org.depromeet.team3.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

data class TestData(val id: Long, val name: String, val active: Boolean)

class GenericDataEncoderTest {

    @Test
    fun `객체 인코딩 디코딩 테스트`() {
        val originalData = TestData(123L, "Test User", true)

        val encoded = GenericDataEncoder.encodeObject(originalData)
        val decoded = GenericDataEncoder.decodeObject(encoded!!, TestData::class.java)

        assertNotNull(encoded)
        assertNotNull(decoded)
        assertEquals(originalData, decoded)
    }

    @Test
    fun `Map 데이터 인코딩 디코딩 테스트`() {
        val originalData = mapOf(
            "name" to "Test Map",
            "active" to true,
        )

        val encoded = GenericDataEncoder.encodeMap(originalData)
        val decoded = GenericDataEncoder.decodeMap(encoded!!)

        assertNotNull(encoded)
        assertNotNull(decoded)
        assertEquals(originalData.size, decoded.size)
        assertEquals(originalData["name"], decoded["name"])
        assertEquals(originalData["active"], decoded["active"])
    }

    @Test
    fun `리스트 데이터 인코딩 디코딩 테스트`() {
        val originalData = listOf(
            TestData(1L, "User 1", true),
            TestData(2L, "User 2", false),
            TestData(3L, "User 3", true),
        )

        val encoded = GenericDataEncoder.encodeList(originalData)
        val decoded = GenericDataEncoder.decodeList(encoded!!, TestData::class.java)

        assertNotNull(encoded)
        assertNotNull(decoded)
        assertEquals(originalData, decoded)
    }

    @Test
    fun `잘못된 데이터로 객체 디코딩 테스트`() {
        val invalidData = "invalid_json_data"

        val decoded = GenericDataEncoder.decodeObject(invalidData, TestData::class.java)

        assertNull(decoded)
    }
}
