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

    private val logger = org.slf4j.LoggerFactory.getLogger(PlaceLikeService::class.java)

    suspend fun toggle(meetingId: Long, userId: Long, placeId: Long): PlaceLikeResult = withContext(coroutineDispatchers.VT) {
        logger.info("Toggle Like Request - meetingId: {}, userId: {}, placeId: {}", meetingId, userId, placeId)
        transactionTemplate.execute {
            runBlocking {
                val meetingPlaceId = getMeetingPlaceId(meetingId, placeId)
                val isLiked = toggleLikeStatus(meetingPlaceId, userId)
                val likeCount = placeLikeRepository.countByMeetingPlaceId(meetingPlaceId).toInt()
                logger.info("Toggle result - isLiked: {}, likeCount: {}", isLiked, likeCount)

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
        val existing = placeLikeRepository.findByMeetingPlaceIdAndUserId(meetingPlaceId, userId)
        return if (existing != null) {
            placeLikeRepository.deleteByMeetingPlaceIdAndUserId(meetingPlaceId, userId)
            false
        } else {
            try {
                placeLikeRepository.save(
                    PlaceLike(
                        meetingPlaceId = meetingPlaceId,
                        userId = userId
                    )
                )
                true
            } catch (e: DataIntegrityViolationException) {
                placeLikeRepository.deleteByMeetingPlaceIdAndUserId(meetingPlaceId, userId)
                false
            }
        }
    }

    data class PlaceLikeResult(
        val isLiked: Boolean,
        val likeCount: Int
    )
}