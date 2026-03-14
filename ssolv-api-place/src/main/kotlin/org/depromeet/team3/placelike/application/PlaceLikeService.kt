package org.depromeet.team3.placelike.application

import kotlinx.coroutines.withContext
import org.depromeet.team3.common.util.CoroutineDispatchers
import org.depromeet.team3.place.application.execution.MeetingPlaceSearchService
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

@Service
class PlaceLikeService(
    private val coroutineDispatchers: CoroutineDispatchers,
    private val redisTemplate: StringRedisTemplate,
    private val searchService: MeetingPlaceSearchService
) {

    private val logger = org.slf4j.LoggerFactory.getLogger(PlaceLikeService::class.java)
    private val likeScoreMultiplier = 50.0 // ExecutePlaceSearchService와 동일한 가치 부여

    suspend fun toggle(meetingId: Long, userId: Long, placeId: Long): PlaceLikeResult = withContext(coroutineDispatchers.VT) {
        logger.debug("Toggle Like Request (Redis Only) - meetingId: {}, userId: {}, placeId: {}", meetingId, userId, placeId)
        
        val likeKey = searchService.getLikeKey(meetingId, placeId)
        val meetingKey = searchService.getMeetingKey(meetingId)
        
        // Redis Set에 유저 추가/삭제 (Atomic)
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

        PlaceLikeResult(
            isLiked = isLiked,
            likeCount = likeCount.toInt()
        )
    }

    data class PlaceLikeResult(
        val isLiked: Boolean,
        val likeCount: Int
    )
}