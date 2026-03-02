package org.depromeet.team3.placelike.application

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.common.util.CoroutineDispatchers
import org.depromeet.team3.meetingplace.MeetingPlaceRepository
import org.depromeet.team3.meetingplace.exception.MeetingPlaceException
import org.depromeet.team3.placelike.PlaceLike
import org.depromeet.team3.placelike.PlaceLikeRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@Service
class PlaceLikeService(
    private val meetingPlaceRepository: MeetingPlaceRepository,
    private val placeLikeRepository: PlaceLikeRepository,
    private val transactionTemplate: TransactionTemplate,
    private val coroutineDispatchers: CoroutineDispatchers
) {

    suspend fun toggle(meetingId: Long, userId: Long, placeId: Long): PlaceLikeResult = withContext(coroutineDispatchers.VT) {
        transactionTemplate.execute {
            runBlocking {
                val meetingPlaceId = getMeetingPlaceId(meetingId, placeId)
                val isLiked = toggleLikeStatus(meetingPlaceId, userId)
                val likeCount = placeLikeRepository.countByMeetingPlaceId(meetingPlaceId).toInt()

                PlaceLikeResult(
                    isLiked = isLiked,
                    likeCount = likeCount
                )
            }
        }!!
    }

    private suspend fun getMeetingPlaceId(meetingId: Long, placeId: Long): Long {
        return meetingPlaceRepository.findIdByMeetingIdAndPlaceId(meetingId, placeId)
            ?: throw MeetingPlaceException(
                errorCode = ErrorCode.MEETING_PLACE_NOT_FOUND,
                detail = mapOf("meetingId" to meetingId, "placeId" to placeId)
            )
    }

    private suspend fun toggleLikeStatus(meetingPlaceId: Long, userId: Long): Boolean {
        return try {
            placeLikeRepository.save(
                PlaceLike(
                    meetingPlaceId = meetingPlaceId,
                    userId = userId
                )
            )
            true
        } catch (e: DataIntegrityViolationException) {
            // 이미 좋아요가 존재하면 삭제 (Toggle)
            placeLikeRepository.deleteByMeetingPlaceIdAndUserId(meetingPlaceId, userId)
            false
        }
    }

    data class PlaceLikeResult(
        val isLiked: Boolean,
        val likeCount: Int
    )
}