package org.depromeet.team3.placelike.application

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.common.util.CoroutineDispatchers
import org.depromeet.team3.meetingplace.MeetingPlaceRepository
import org.depromeet.team3.meetingplace.exception.MeetingPlaceException
import org.depromeet.team3.place.application.execution.MeetingPlaceSearchService
import org.depromeet.team3.placelike.PlaceLike
import org.depromeet.team3.placelike.PlaceLikeRepository
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.TimeUnit

@Service
class PlaceLikeService(
    private val meetingPlaceRepository: MeetingPlaceRepository,
    private val placeLikeRepository: PlaceLikeRepository,
    private val transactionTemplate: TransactionTemplate,
    private val coroutineDispatchers: CoroutineDispatchers,
    private val redisTemplate: StringRedisTemplate,
    private val searchService: MeetingPlaceSearchService
) {

    private val logger = org.slf4j.LoggerFactory.getLogger(PlaceLikeService::class.java)
    private val likeScoreMultiplier = 50.0 // ExecutePlaceSearchService와 동일한 가치 부여

    suspend fun toggle(meetingId: Long, userId: Long, placeId: Long): PlaceLikeResult = withContext(coroutineDispatchers.VT) {
        logger.debug("Toggle Like Request (Redis) - meetingId: {}, userId: {}, placeId: {}", meetingId, userId, placeId)
        
        val likeKey = searchService.getLikeKey(meetingId, placeId)
        val meetingKey = searchService.getMeetingKey(meetingId)
        
        // 1. Redis Set에 유저 추가/삭제 (Atomic)
        val isNewLike = redisTemplate.opsForSet().add(likeKey, userId.toString()) ?: 0L
        val isLiked: Boolean
        
        if (isNewLike > 0) {
            // 새로 좋아요를 누름
            isLiked = true
            // ZSET 점수 증가
            redisTemplate.opsForZSet().incrementScore(meetingKey, placeId.toString(), likeScoreMultiplier)
            redisTemplate.expire(likeKey, 30, TimeUnit.DAYS)
        } else {
            // 이미 좋아요가 있음 -> 취소
            redisTemplate.opsForSet().remove(likeKey, userId.toString())
            isLiked = false
            // ZSET 점수 감소
            redisTemplate.opsForZSet().incrementScore(meetingKey, placeId.toString(), -likeScoreMultiplier)
        }

        val likeCount = redisTemplate.opsForSet().size(likeKey) ?: 0L

        // 2. DB 동기화 (Background 혹은 병행 - 여기서는 일관성을 위해 병행 수행)
        // 기존 로직 유지하여 RDBMS에도 기록 남김
        try {
            transactionTemplate.execute {
                runBlocking {
                    val meetingPlaceId = getMeetingPlaceId(meetingId, placeId)
                    syncDbLikeStatus(meetingPlaceId, userId, isLiked)
                }
            }
        } catch (e: Exception) {
            logger.error("DB 좋아요 동기화 실패 (Redis 우선 적용됨): {}", e.message)
        }

        PlaceLikeResult(
            isLiked = isLiked,
            likeCount = likeCount.toInt()
        )
    }

    private suspend fun getMeetingPlaceId(meetingId: Long, placeId: Long): Long {
        return meetingPlaceRepository.findIdByMeetingIdAndPlaceId(meetingId, placeId)
            ?: throw MeetingPlaceException(
                errorCode = ErrorCode.MEETING_PLACE_NOT_FOUND,
                detail = mapOf("meetingId" to meetingId, "placeId" to placeId)
            )
    }

    private suspend fun syncDbLikeStatus(meetingPlaceId: Long, userId: Long, shouldExist: Boolean) {
        val existing = placeLikeRepository.findByMeetingPlaceIdAndUserId(meetingPlaceId, userId)
        if (shouldExist && existing == null) {
            placeLikeRepository.save(PlaceLike(meetingPlaceId = meetingPlaceId, userId = userId))
        } else if (!shouldExist && existing != null) {
            placeLikeRepository.deleteByMeetingPlaceIdAndUserId(meetingPlaceId, userId)
        }
    }

    data class PlaceLikeResult(
        val isLiked: Boolean,
        val likeCount: Int
    )
}