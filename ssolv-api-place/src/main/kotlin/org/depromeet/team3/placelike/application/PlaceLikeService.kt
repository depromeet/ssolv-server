package org.depromeet.team3.placelike.application

import kotlinx.coroutines.withContext
import org.depromeet.team3.common.util.CoroutineDispatchers
import org.depromeet.team3.place.application.execution.MeetingPlaceSearchService
import org.depromeet.team3.meeting.MeetingQuery
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

@Service
class PlaceLikeService(
    private val coroutineDispatchers: CoroutineDispatchers,
    private val redisTemplate: StringRedisTemplate,
    private val searchService: MeetingPlaceSearchService,
    private val meetingQuery: MeetingQuery
) {

    private val logger = org.slf4j.LoggerFactory.getLogger(PlaceLikeService::class.java)
    private val likeScoreMultiplier = 50.0
    private val DEFAULT_TTL = 604800L // 7 days fallback

    private val toggleScript = DefaultRedisScript("""
        local likeKey = KEYS[1]
        local meetingKey = KEYS[2]
        local userId = ARGV[1]
        local placeId = ARGV[2]
        local bonus = tonumber(ARGV[3])
        local ttl = tonumber(ARGV[4])

        local added = redis.call('SADD', likeKey, userId)
        local isLiked = 0
        local scoreDelta = 0

        if added == 1 then
            isLiked = 1
            scoreDelta = bonus
        else
            redis.call('SREM', likeKey, userId)
            isLiked = 0
            scoreDelta = -bonus
        end

        redis.call('ZINCRBY', meetingKey, scoreDelta, placeId)
        if ttl > 0 then
            redis.call('EXPIRE', likeKey, ttl)
            redis.call('EXPIRE', meetingKey, ttl)
        end
        
        local count = redis.call('SCARD', likeKey)
        return {isLiked, count}
    """.trimIndent(), List::class.java)

    suspend fun toggle(meetingId: Long, userId: Long, placeId: Long): PlaceLikeResult = withContext(coroutineDispatchers.VT) {
        logger.debug("Toggle Like Request (Atomic Lua) - meetingId: {}, userId: {}, placeId: {}", meetingId, userId, placeId)
        
        val likeKey = searchService.getLikeKey(meetingId, placeId)
        val meetingKey = searchService.getMeetingKey(meetingId)
        
        // Dynamic TTL calculation based on meeting endAt
        val ttlSeconds = calculateMeetingTTL(meetingId)
        
        val result = redisTemplate.execute(
            toggleScript,
            listOf(likeKey, meetingKey),
            userId.toString(),
            placeId.toString(),
            likeScoreMultiplier.toString(),
            ttlSeconds.toString()
        ) as List<*>

        val isLiked = (result[0] as Number).toLong() == 1L
        val likeCount = (result[1] as Number).toLong()

        // 랭킹 변동 알림 발행 (Pub/Sub)
        // 채널명: meeting:updates:{meetingId}
        // 메시지: 업데이트된 장소 ID
        val channel = "meeting:updates:$meetingId"
        runCatching {
            redisTemplate.convertAndSend(channel, placeId.toString())
        }.onFailure { ex ->
            logger.warn("Failed to publish place update - meetingId: {}, placeId: {}", meetingId, placeId, ex)
        }

        PlaceLikeResult(
            isLiked = isLiked,
            likeCount = likeCount.toInt()
        )
    }

    private suspend fun calculateMeetingTTL(meetingId: Long): Long {
        val meeting = meetingQuery.findById(meetingId)
        val endAt = meeting?.endAt ?: return DEFAULT_TTL
        
        val now = LocalDateTime.now(ZoneId.of("Asia/Seoul"))
        val duration = Duration.between(now, endAt)
        
        return if (duration.isNegative || duration.isZero) {
            3600L // 1 hour if already expired but somehow still active
        } else {
            duration.seconds
        }
    }

    data class PlaceLikeResult(
        val isLiked: Boolean,
        val likeCount: Int
    )
}