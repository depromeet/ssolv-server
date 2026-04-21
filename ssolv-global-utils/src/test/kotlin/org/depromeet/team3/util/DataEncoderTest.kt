package org.depromeet.team3.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DataEncoderTest {

    @Test
    fun `구분자로 연결된 데이터 인코딩 디코딩 테스트`() {
        val data = arrayOf("meeting", "123", "user456")
        val separator = ":"

        val encoded = DataEncoder.encodeWithSeparator(separator, *data)
        val decoded = DataEncoder.decodeWithSeparator(encoded, separator)

        assertNotNull(encoded)
        assertNotNull(decoded)
        assertEquals(data.toList(), decoded)
    }

    @Test
    fun `잘못된 구분자 데이터 디코딩 테스트`() {
        val invalidData = "invalid_data_without_separator"
        val decoded = DataEncoder.decodeWithSeparator(invalidData, ":")

        assertNull(decoded)
    }
}
