package org.depromeet.team3.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MeetingIdParserTest {

    @Test
    fun `숫자로 된 문자열이 들어오면 해당 숫자를 Long으로 반환한다`() {
        val identifier = "123"
        val result = MeetingIdParser.parse(identifier)
        assertEquals(123L, result)
    }

    @Test
    fun `인코딩된 토큰이 들어오면 첫 번째 값인 meetingId를 추출하여 반환한다`() {
        // "123:1738222000000"를 Base64 인코딩한 값
        val meetingId = 123L
        val expiry = 1738222000000L // 실제 타임스탬프 형식
        val token = DataEncoder.encodeWithSeparator(":", meetingId.toString(), expiry.toString())

        val result = MeetingIdParser.parse(token)

        assertEquals(meetingId, result)
    }

    @Test
    fun `전체 초대 URL이 들어오면 토큰 부분만 추출하여 meetingId를 반환한다`() {
        val meetingId = 555L
        val expiry = 1738222000000L
        val encodedData = DataEncoder.encodeWithSeparator(":", meetingId.toString(), expiry.toString())
        val url = "https://api.ssolv.site/api/v1/meetings/validate-invite?token=$encodedData"

        val result = MeetingIdParser.parse(url)

        assertEquals(meetingId, result)
    }

    @Test
    fun `잘못된 형식의 토큰이 들어오면 IllegalArgumentException을 발생시킨다`() {
        val invalidToken = "!!invalid!!"

        assertThrows<IllegalArgumentException> {
            MeetingIdParser.parse(invalidToken)
        }
    }

    @Test
    fun `토큰은 유효하지만 meetingId 부분이 숫자가 아니면 IllegalArgumentException을 발생시킨다`() {
        // "abc:123"를 Base64 인코딩
        val token = DataEncoder.encodeWithSeparator(":", "abc", "123")

        assertThrows<IllegalArgumentException> {
            MeetingIdParser.parse(token)
        }
    }
}