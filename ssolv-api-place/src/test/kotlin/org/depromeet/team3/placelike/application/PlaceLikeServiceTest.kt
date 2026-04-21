package org.depromeet.team3.placelike.application
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.depromeet.team3.place.application.execution.MeetingPlaceSearchService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito.lenient
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.*
import org.mockito.quality.Strictness
import org.springframework.data.redis.core.StringRedisTemplate

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlaceLikeServiceTest {

    private lateinit var redisTemplate: StringRedisTemplate
    private lateinit var searchService: MeetingPlaceSearchService
    private lateinit var meetingQuery: org.depromeet.team3.meeting.MeetingQuery
    private lateinit var objectMapper: ObjectMapper

    private lateinit var service: PlaceLikeService

    @BeforeEach
    fun setup() {
        redisTemplate = mock()
        searchService = mock {
            on { getLikeKey(any(), any()) } doAnswer { "meeting:${it.arguments[0]}:place:${it.arguments[1]}:likes" }
            on { getMeetingKey(any()) } doAnswer { "meeting:places:${it.arguments[0]}" }
        }
        meetingQuery = mock()
        objectMapper = mock()

        lenient().whenever(redisTemplate.getExpire(any<String>())).thenReturn(0L)

        service = PlaceLikeService(
            redisTemplate = redisTemplate,
            searchService = searchService,
            meetingQuery = meetingQuery,
            objectMapper = objectMapper,
        )
    }

    @Test
    @DisplayName("좋아요 토글 - 신규 좋아요 시 Lua 스크립트를 호출한다")
    fun `신규 좋아요 시 Lua 스크립트를 호출한다`() = runTest {
        // given
        val meetingId = 1L
        val userId = 99L
        val placeId = 101L

        // ObjectMapper stubbing
        whenever(objectMapper.writeValueAsString(any())).thenReturn("""{"placeId":101,"likeCount":1}""")

        // getExpire stubbing
        whenever(redisTemplate.getExpire(any<String>())).thenReturn(0L)

        // Lua return: [isLiked(1), likeCount(1)]
        whenever(
            redisTemplate.execute<List<*>>(
                any<org.springframework.data.redis.core.script.RedisScript<List<*>>>(),
                any<List<String>>(),
                anyVararg<Any>(),
            ),
        ).thenReturn(listOf(1L, 1L))

        // when
        val result = service.toggle(meetingId, userId, placeId)

        // then
        assertThat(result.isLiked).isTrue()
        assertThat(result.likeCount).isEqualTo(1)

        verify(redisTemplate).execute(
            any<org.springframework.data.redis.core.script.RedisScript<List<*>>>(),
            eq(listOf("meeting:1:place:101:likes", "meeting:places:1")),
            eq("99"),
            eq("101"),
            any(), // TTL
        )

        verify(redisTemplate).convertAndSend(
            eq("meeting:updates:1"),
            eq("""{"placeId":101,"likeCount":1}"""),
        )
    }

    @Test
    @DisplayName("좋아요 토글 - 좋아요 취소 시 Lua 스크립트 결과 반영")
    fun `좋아요 취소 시 Lua 스크립트 결과 반영`() = runTest {
        // given
        val meetingId = 1L
        val userId = 99L
        val placeId = 101L

        whenever(objectMapper.writeValueAsString(any())).thenReturn("""{"placeId":101,"likeCount":0}""")

        // Lua return: [isLiked(0), likeCount(0)]
        whenever(
            redisTemplate.execute<List<*>>(
                any<org.springframework.data.redis.core.script.RedisScript<List<*>>>(),
                any<List<String>>(),
                anyVararg<Any>(),
            ),
        ).thenReturn(listOf(0L, 0L))

        // when
        val result = service.toggle(meetingId, userId, placeId)

        // then
        assertThat(result.isLiked).isFalse()
        assertThat(result.likeCount).isEqualTo(0)
    }

    @Test
    @DisplayName("좋아요 토글 - Pub/Sub 알림 발행 실패 시에도 좋아요 토글 결과는 정상 반환된다")
    fun `Pub Sub 알림 발행 실패 시에도 좋아요 토글 결과는 정상 반환된다`() = runTest {
        // given
        val meetingId = 1L
        val userId = 99L
        val placeId = 101L

        whenever(objectMapper.writeValueAsString(any())).thenReturn("""{"placeId":101,"likeCount":1}""")

        whenever(
            redisTemplate.execute<List<*>>(
                any<org.springframework.data.redis.core.script.RedisScript<List<*>>>(),
                any<List<String>>(),
                anyVararg<Any>(),
            ),
        ).thenReturn(listOf(1L, 1L))

        // Pub/Sub 발행 시 예외 발생 시뮬레이션
        whenever(redisTemplate.convertAndSend(any(), any())).thenThrow(RuntimeException("Redis errors"))

        // when
        val result = service.toggle(meetingId, userId, placeId)

        // then
        assertThat(result.isLiked).isTrue()
        assertThat(result.likeCount).isEqualTo(1)
    }
}
