package org.depromeet.team3.placelike.application

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.depromeet.team3.meetingplace.MeetingPlaceRepository
import org.depromeet.team3.place.application.execution.MeetingPlaceSearchService
import org.depromeet.team3.placelike.PlaceLikeRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import org.springframework.data.redis.core.SetOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ZSetOperations
import org.springframework.transaction.support.TransactionTemplate
import org.depromeet.team3.common.util.CoroutineDispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.mockito.Mockito.lenient

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@ExtendWith(MockitoExtension::class)
class PlaceLikeServiceTest {

    private lateinit var meetingPlaceRepository: MeetingPlaceRepository
    private lateinit var placeLikeRepository: PlaceLikeRepository
    private lateinit var transactionTemplate: TransactionTemplate
    private lateinit var coroutineDispatchers: CoroutineDispatchers
    private lateinit var redisTemplate: StringRedisTemplate
    private lateinit var searchService: MeetingPlaceSearchService

    private lateinit var setOps: SetOperations<String, String>
    private lateinit var zSetOps: ZSetOperations<String, String>

    private lateinit var service: PlaceLikeService

    @BeforeEach
    fun setup() {
        meetingPlaceRepository = mock()
        placeLikeRepository = mock()
        transactionTemplate = mock {
            on { execute<Any>(any()) } doAnswer { invocation ->
                val callback = invocation.arguments[0] as org.springframework.transaction.support.TransactionCallback<Any>
                callback.doInTransaction(mock())
            }
        }
        coroutineDispatchers = mock()
        lenient().whenever(coroutineDispatchers.VT).thenReturn(UnconfinedTestDispatcher())
        
        redisTemplate = mock()
        searchService = mock {
            on { getLikeKey(any(), any()) } doAnswer { "meeting:${it.arguments[0]}:place:${it.arguments[1]}:likes" }
            on { getMeetingKey(any()) } doAnswer { "meeting:places:${it.arguments[0]}" }
        }

        setOps = mock()
        zSetOps = mock()
        whenever(redisTemplate.opsForSet()).thenReturn(setOps)
        whenever(redisTemplate.opsForZSet()).thenReturn(zSetOps)

        service = PlaceLikeService(
            meetingPlaceRepository = meetingPlaceRepository,
            placeLikeRepository = placeLikeRepository,
            transactionTemplate = transactionTemplate,
            coroutineDispatchers = coroutineDispatchers,
            redisTemplate = redisTemplate,
            searchService = searchService
        )
    }

    @Test
    @DisplayName("좋아요 토글 - 신규 좋아요 시 Redis Set 추가 및 ZSET 점수 증가")
    fun `신규 좋아요 시 Redis SADD 및 ZINCRBY가 호출된다`() = runTest {
        // given
        val meetingId = 1L
        val userId = 99L
        val placeId = 101L
        
        // SADD 성공 (신규 추가)
        whenever(setOps.add(any(), any())).thenReturn(1L)
        whenever(setOps.size(any())).thenReturn(1L)
        whenever(meetingPlaceRepository.findIdByMeetingIdAndPlaceId(meetingId, placeId)).thenReturn(500L)

        // when
        val result = service.toggle(meetingId, userId, placeId)

        // then
        assertThat(result.isLiked).isTrue()
        assertThat(result.likeCount).isEqualTo(1)
        
        verify(setOps).add("meeting:1:place:101:likes", "99")
        verify(zSetOps).incrementScore("meeting:places:1", "101", 50.0)
        verify(placeLikeRepository).save(any())
    }

    @Test
    @DisplayName("좋아요 토글 - 좋아요 취소 시 Redis Set 삭제 및 ZSET 점수 감소")
    fun `좋아요 취소 시 Redis SREM 및 ZINCRBY(마이너스)가 호출된다`() = runTest {
        // given
        val meetingId = 1L
        val userId = 99L
        val placeId = 101L
        
        // SADD 실패 (이미 존재함)
        whenever(setOps.add(any(), any())).thenReturn(0L)
        whenever(setOps.remove(any(), any())).thenReturn(1L)
        whenever(setOps.size(any())).thenReturn(0L)
        whenever(meetingPlaceRepository.findIdByMeetingIdAndPlaceId(meetingId, placeId)).thenReturn(500L)
        whenever(placeLikeRepository.findByMeetingPlaceIdAndUserId(any(), any())).thenReturn(mock())

        // when
        val result = service.toggle(meetingId, userId, placeId)

        // then
        assertThat(result.isLiked).isFalse()
        assertThat(result.likeCount).isEqualTo(0)
        
        verify(setOps).remove("meeting:1:place:101:likes", "99")
        verify(zSetOps).incrementScore("meeting:places:1", "101", -50.0)
        verify(placeLikeRepository).deleteByMeetingPlaceIdAndUserId(eq(500L), eq(99L))
    }
}
