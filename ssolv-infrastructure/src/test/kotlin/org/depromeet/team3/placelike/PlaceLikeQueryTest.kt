package org.depromeet.team3.placelike

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.depromeet.team3.mapper.PlaceLikeMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*

@ExtendWith(MockitoExtension::class)
class PlaceLikeQueryTest {

    @Mock
    private lateinit var placeLikeJpaRepository: PlaceLikeJpaRepository

    @Mock
    private lateinit var placeLikeMapper: PlaceLikeMapper

    private lateinit var placeLikeQuery: PlaceLikeQuery

    @BeforeEach
    fun setUp() {
        placeLikeQuery = PlaceLikeQuery(
            placeLikeJpaRepository = placeLikeJpaRepository,
            placeLikeMapper = placeLikeMapper,
        )
    }

    @Test
    fun `좋아요 저장 성공`() {
        runBlocking {
            // given
            val placeLike = PlaceLike(
                meetingPlaceId = 1L,
                userId = 100L
            )

            val savedEntity = PlaceLikeEntity(
                id = 1L,
                meetingPlaceId = 1L,
                userId = 100L
            )

            val expectedDomain = PlaceLike(
                id = 1L,
                meetingPlaceId = 1L,
                userId = 100L
            )

            whenever(placeLikeMapper.toEntity(placeLike)).thenReturn(savedEntity)
            whenever(placeLikeJpaRepository.save(savedEntity)).thenReturn(savedEntity)
            whenever(placeLikeMapper.toDomain(savedEntity)).thenReturn(expectedDomain)

            // when
            val result = placeLikeQuery.save(placeLike)

            // then
            assertThat(result).isEqualTo(expectedDomain)
            verify(placeLikeJpaRepository).save(savedEntity)
        }
    }

    @Test
    fun `좋아요 조회 - MeetingPlaceId와 UserId로 조회`() {
        runBlocking {
            // given
            val meetingPlaceId = 1L
            val userId = 100L

            val entity = PlaceLikeEntity(
                id = 1L,
                meetingPlaceId = meetingPlaceId,
                userId = userId
            )

            val expectedDomain = PlaceLike(
                id = 1L,
                meetingPlaceId = meetingPlaceId,
                userId = userId
            )

            whenever(placeLikeJpaRepository.findByMeetingPlaceIdAndUserId(meetingPlaceId, userId))
                .thenReturn(entity)
            whenever(placeLikeMapper.toDomain(entity)).thenReturn(expectedDomain)

            // when
            val result = placeLikeQuery.findByMeetingPlaceIdAndUserId(meetingPlaceId, userId)

            // then
            assertThat(result).isEqualTo(expectedDomain)
        }
    }

    @Test
    fun `좋아요 조회 - 존재하지 않는 경우 null 반환`() {
        runBlocking {
            // given
            val meetingPlaceId = 1L
            val userId = 100L

            whenever(placeLikeJpaRepository.findByMeetingPlaceIdAndUserId(meetingPlaceId, userId))
                .thenReturn(null)

            // when
            val result = placeLikeQuery.findByMeetingPlaceIdAndUserId(meetingPlaceId, userId)

            // then
            assertThat(result).isNull()
        }
    }

    @Test
    fun `좋아요 삭제 성공`() {
        runBlocking {
            // given
            val meetingPlaceId = 1L
            val userId = 100L

            val existingEntity = PlaceLikeEntity(
                id = 1L,
                meetingPlaceId = meetingPlaceId,
                userId = userId
            )

            whenever(placeLikeJpaRepository.findByMeetingPlaceIdAndUserId(meetingPlaceId, userId))
                .thenReturn(existingEntity)

            // when
            placeLikeQuery.deleteByMeetingPlaceIdAndUserId(meetingPlaceId, userId)

            // then
            verify(placeLikeJpaRepository).findByMeetingPlaceIdAndUserId(meetingPlaceId, userId)
            verify(placeLikeJpaRepository).delete(existingEntity)
        }
    }

    @Test
    fun `좋아요 삭제 - 존재하지 않는 경우 아무것도 삭제하지 않음`() {
        runBlocking {
            // given
            val meetingPlaceId = 1L
            val userId = 100L

            whenever(placeLikeJpaRepository.findByMeetingPlaceIdAndUserId(meetingPlaceId, userId))
                .thenReturn(null)

            // when
            placeLikeQuery.deleteByMeetingPlaceIdAndUserId(meetingPlaceId, userId)

            // then
            verify(placeLikeJpaRepository).findByMeetingPlaceIdAndUserId(meetingPlaceId, userId)
            verify(placeLikeJpaRepository, never()).delete(any())
        }
    }

    @Test
    fun `좋아요 수 조회`() {
        runBlocking {
            // given
            val meetingPlaceId = 1L
            val expectedCount = 5L

            whenever(placeLikeJpaRepository.countByMeetingPlaceId(meetingPlaceId))
                .thenReturn(expectedCount)

            // when
            val result = placeLikeQuery.countByMeetingPlaceId(meetingPlaceId)

            // then
            assertThat(result).isEqualTo(expectedCount)
        }
    }

    @Test
    fun `여러 MeetingPlace의 좋아요 조회`() {
        runBlocking {
            // given
            val meetingPlaceIds = listOf(1L, 2L, 3L)

            val entities = listOf(
                PlaceLikeEntity(id = 1L, meetingPlaceId = 1L, userId = 100L),
                PlaceLikeEntity(id = 2L, meetingPlaceId = 1L, userId = 200L),
                PlaceLikeEntity(id = 3L, meetingPlaceId = 2L, userId = 100L)
            )

            val expectedDomains = listOf(
                PlaceLike(id = 1L, meetingPlaceId = 1L, userId = 100L),
                PlaceLike(id = 2L, meetingPlaceId = 1L, userId = 200L),
                PlaceLike(id = 3L, meetingPlaceId = 2L, userId = 100L)
            )

            whenever(placeLikeJpaRepository.findByMeetingPlaceIdIn(meetingPlaceIds))
                .thenReturn(entities)
            whenever(placeLikeMapper.toDomain(any())).thenAnswer { invocation ->
                val entity = invocation.getArgument<PlaceLikeEntity>(0)
                expectedDomains.find { it.id == entity.id }
            }

            // when
            val result = placeLikeQuery.findByMeetingPlaceIds(meetingPlaceIds)

            // then
            assertThat(result).hasSize(3)
            assertThat(result.map { it.meetingPlaceId }).containsExactlyInAnyOrder(1L, 1L, 2L)
        }
    }

    @Test
    fun `빈 MeetingPlace 목록으로 조회하면 빈 리스트 반환`() {
        runBlocking {
            // given
            val meetingPlaceIds = emptyList<Long>()

            // when
            val result = placeLikeQuery.findByMeetingPlaceIds(meetingPlaceIds)

            // then
            assertThat(result).isEmpty()
            verify(placeLikeJpaRepository, never()).findByMeetingPlaceIdIn(any())
        }
    }
}
