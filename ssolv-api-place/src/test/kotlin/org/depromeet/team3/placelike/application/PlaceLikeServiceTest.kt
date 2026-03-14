package org.depromeet.team3.placelike.application

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.depromeet.team3.place.application.execution.MeetingPlaceSearchService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import org.springframework.data.redis.core.StringRedisTemplate
import org.depromeet.team3.common.util.CoroutineDispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.mockito.Mockito.lenient

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@ExtendWith(MockitoExtension::class)
class PlaceLikeServiceTest {

    private lateinit var coroutineDispatchers: CoroutineDispatchers
    private lateinit var redisTemplate: StringRedisTemplate
    private lateinit var searchService: MeetingPlaceSearchService

    private lateinit var service: PlaceLikeService

    @BeforeEach
    fun setup() {
        coroutineDispatchers = mock()
        lenient().whenever(coroutineDispatchers.VT).thenReturn(UnconfinedTestDispatcher())
        
        redisTemplate = mock()
        searchService = mock {
            on { getLikeKey(any(), any()) } doAnswer { "meeting:${it.arguments[0]}:place:${it.arguments[1]}:likes" }
            on { getMeetingKey(any()) } doAnswer { "meeting:places:${it.arguments[0]}" }
        }

        service = PlaceLikeService(
            coroutineDispatchers = coroutineDispatchers,
            redisTemplate = redisTemplate,
            searchService = searchService
        )
    }

    @Test
    @DisplayName("좋아요 토글 - 신규 좋아요 시 Lua 스크립트를 호출한다")
    fun `신규 좋아요 시 Lua 스크립트를 호출한다`() = runTest {
        // given
        val meetingId = 1L
        val userId = 99L
        val placeId = 101L
        
        // Lua return: [isLiked(1), likeCount(1)]
        whenever(redisTemplate.execute(
            any<org.springframework.data.redis.core.script.RedisScript<List<*>>>(),
            any<List<String>>(),
            any<String>(),
            any<String>(),
            any<String>()
        )).thenReturn(listOf(1L, 1L))

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
            eq("50.0")
        )
    }

    @Test
    @DisplayName("좋아요 토글 - 좋아요 취소 시 Lua 스크립트 결과가 반영된다")
    fun `좋아요 취소 시 Lua 스크립트 결과 반영`() = runTest {
        // given
        val meetingId = 1L
        val userId = 99L
        val placeId = 101L
        
        // Lua return: [isLiked(0), likeCount(0)]
        whenever(redisTemplate.execute(
            any<org.springframework.data.redis.core.script.RedisScript<List<*>>>(),
            any<List<String>>(),
            any<String>(),
            any<String>(),
            any<String>()
        )).thenReturn(listOf(0L, 0L))

        // when
        val result = service.toggle(meetingId, userId, placeId)

        // then
        assertThat(result.isLiked).isFalse()
        assertThat(result.likeCount).isEqualTo(0)
    }
}   