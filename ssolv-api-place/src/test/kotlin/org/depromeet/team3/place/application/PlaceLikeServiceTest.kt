package org.depromeet.team3.place.application

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.meetingplace.MeetingPlace
import org.depromeet.team3.meetingplace.MeetingPlaceRepository
import org.depromeet.team3.meetingplace.exception.MeetingPlaceException
import org.depromeet.team3.placelike.PlaceLike
import org.depromeet.team3.placelike.PlaceLikeRepository
import org.depromeet.team3.placelike.application.PlaceLikeService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import org.springframework.dao.DataIntegrityViolationException

@ExtendWith(MockitoExtension::class)
class PlaceLikeServiceTest {

    @Mock
    private lateinit var meetingPlaceRepository: MeetingPlaceRepository

    @Mock
    private lateinit var placeLikeRepository: PlaceLikeRepository

    private lateinit var placeLikeService: PlaceLikeService

    @BeforeEach
    fun setUp() {
        placeLikeService = PlaceLikeService(
            meetingPlaceRepository = meetingPlaceRepository,
            placeLikeRepository = placeLikeRepository
        )
    }

    @Test
    fun `좋아요 토글 - 처음 좋아요 추가`() {
        runBlocking {
            // given
            val meetingId = 1L
            val userId = 100L
            val placeId = 200L
            val meetingPlaceId = 10L

            val savedPlaceLike = PlaceLike(
                id = 1L,
                meetingPlaceId = meetingPlaceId,
                userId = userId
            )

            whenever(meetingPlaceRepository.findIdByMeetingIdAndPlaceId(meetingId, placeId))
                .thenReturn(meetingPlaceId)
            whenever(placeLikeRepository.save(any()))
                .thenReturn(savedPlaceLike)
            whenever(placeLikeRepository.countByMeetingPlaceId(meetingPlaceId))
                .thenReturn(1L)

            // when
            val result = placeLikeService.toggle(meetingId, userId, placeId)

            // then
            assertThat(result.isLiked).isTrue()
            assertThat(result.likeCount).isEqualTo(1)

            verify(placeLikeRepository).save(
                argThat { placeLike ->
                    placeLike.meetingPlaceId == meetingPlaceId && placeLike.userId == userId 
                }
            )
            verify(placeLikeRepository, never()).deleteByMeetingPlaceIdAndUserId(any(), any())
        }
    }


    @Test
    fun `좋아요 토글 - 기존 좋아요 취소`() {
        runBlocking {
            // given
            val meetingId = 1L
            val userId = 100L
            val placeId = 200L
            val meetingPlaceId = 10L

            whenever(meetingPlaceRepository.findIdByMeetingIdAndPlaceId(meetingId, placeId))
                .thenReturn(meetingPlaceId)
            whenever(placeLikeRepository.save(any()))
                .thenThrow(DataIntegrityViolationException("Duplicate entry"))
            whenever(placeLikeRepository.countByMeetingPlaceId(meetingPlaceId))
                .thenReturn(0L)

            // when
            val result = placeLikeService.toggle(meetingId, userId, placeId)

            // then
            assertThat(result.isLiked).isFalse()
            assertThat(result.likeCount).isEqualTo(0)

            verify(placeLikeRepository).save(
                argThat { placeLike ->
                    placeLike.meetingPlaceId == meetingPlaceId && placeLike.userId == userId 
                }
            )
            verify(placeLikeRepository).deleteByMeetingPlaceIdAndUserId(meetingPlaceId, userId)
        }
    }

    @Test
    fun `좋아요 토글 - MeetingPlace가 존재하지 않으면 예외 발생`() {
        runBlocking {
            // given
            val meetingId = 1L
            val userId = 100L
            val placeId = 200L

            whenever(meetingPlaceRepository.findIdByMeetingIdAndPlaceId(meetingId, placeId))
                .thenReturn(null)

            // when & then
            assertThatThrownBy {
                runBlocking {
                    placeLikeService.toggle(meetingId, userId, placeId)
                }
            }.isInstanceOf(MeetingPlaceException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEETING_PLACE_NOT_FOUND)

            verify(placeLikeRepository, never()).save(any())
            verify(placeLikeRepository, never()).deleteByMeetingPlaceIdAndUserId(any(), any())
        }
    }

    @Test
    fun `좋아요 토글 - 여러 사용자가 좋아요한 경우 카운트 정확`() {
        runBlocking {
            // given
            val meetingId = 1L
            val userId = 100L
            val placeId = 200L
            val meetingPlaceId = 10L

            val savedPlaceLike = PlaceLike(
                id = 1L,
                meetingPlaceId = meetingPlaceId,
                userId = userId
            )

            whenever(meetingPlaceRepository.findIdByMeetingIdAndPlaceId(meetingId, placeId))
                .thenReturn(meetingPlaceId)
            whenever(placeLikeRepository.save(any()))
                .thenReturn(savedPlaceLike)
            whenever(placeLikeRepository.countByMeetingPlaceId(meetingPlaceId))
                .thenReturn(5L) // 다른 사용자들이 이미 4개 좋아요

            // when
            val result = placeLikeService.toggle(meetingId, userId, placeId)

            // then
            assertThat(result.isLiked).isTrue()
            assertThat(result.likeCount).isEqualTo(5) // 4 + 1 = 5
        }
    }

    @Test
    fun `좋아요 토글 - try-catch 로직 동작 확인`() {
        runBlocking {
            // given
            val meetingId = 1L
            val userId = 100L
            val placeId = 200L
            val meetingPlaceId = 10L

            val savedPlaceLike = PlaceLike(
                id = 1L,
                meetingPlaceId = meetingPlaceId,
                userId = userId
            )

            whenever(meetingPlaceRepository.findIdByMeetingIdAndPlaceId(meetingId, placeId))
                .thenReturn(meetingPlaceId)
            whenever(placeLikeRepository.save(any()))
                .thenReturn(savedPlaceLike)
            whenever(placeLikeRepository.countByMeetingPlaceId(meetingPlaceId))
                .thenReturn(1L)

            // when
            placeLikeService.toggle(meetingId, userId, placeId)

            // then
            // save 메서드가 호출되었는지 확인
            verify(placeLikeRepository).save(
                argThat { placeLike ->
                    placeLike.meetingPlaceId == meetingPlaceId && placeLike.userId == userId 
                }
            )
            // delete는 호출되지 않아야 함
            verify(placeLikeRepository, never()).deleteByMeetingPlaceIdAndUserId(any(), any())
        }
    }

    @Test
    fun `좋아요 토글 - DataIntegrityViolationException 발생 시 삭제 로직 확인`() {
        runBlocking {
            // given
            val meetingId = 1L
            val userId = 100L
            val placeId = 200L
            val meetingPlaceId = 10L

            whenever(meetingPlaceRepository.findIdByMeetingIdAndPlaceId(meetingId, placeId))
                .thenReturn(meetingPlaceId)
            whenever(placeLikeRepository.save(any()))
                .thenThrow(DataIntegrityViolationException("Duplicate entry"))
            whenever(placeLikeRepository.countByMeetingPlaceId(meetingPlaceId))
                .thenReturn(0L)

            // when
            placeLikeService.toggle(meetingId, userId, placeId)

            // then
            // save 메서드가 호출되었는지 확인
            verify(placeLikeRepository).save(
                argThat { placeLike ->
                    placeLike.meetingPlaceId == meetingPlaceId && placeLike.userId == userId 
                }
            )
            // 예외 발생 시 delete가 호출되었는지 확인
            verify(placeLikeRepository).deleteByMeetingPlaceIdAndUserId(meetingPlaceId, userId)
        }
    }
}
