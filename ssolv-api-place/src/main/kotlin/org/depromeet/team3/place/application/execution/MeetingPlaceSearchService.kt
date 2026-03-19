package org.depromeet.team3.place.application.execution

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.withContext
import org.depromeet.team3.common.GooglePlacesApiProperties
import org.depromeet.team3.common.util.CoroutineDispatchers
import org.depromeet.team3.meeting.MeetingQuery
import org.depromeet.team3.place.PlaceQuery
import org.depromeet.team3.place.dto.response.PlacesSearchResponse
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * 모임별 장소 검색 결과를 Redis에 저장/조회
 * 
 * - 검색 결과 중 장소 상세 정보를 개별적으로 캐싱 (place:details:{id}, TTL 30일)
 * - 모임에는 검색된 장소의 ID만 저장 (meeting:places:{meetingId}, ZSET 형태 혹은 List)
 * - 재요청 시 Redis에서 10개의 상세 정보를 한번에 가져오고, 누락된 건만 재조회
 */
@Service
class MeetingPlaceSearchService(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val placeQuery: PlaceQuery,
    private val googlePlacesApiProperties: GooglePlacesApiProperties,
    private val coroutineDispatchers: CoroutineDispatchers,
    private val meetingQuery: MeetingQuery
) {

    private val MEETING_KEY_PREFIX = "meeting:places:"
    private val PLACE_KEY_PREFIX = "place:details:"
    private val LIKE_KEY_TEMPLATE = "meeting:%d:place:%d:likes"
    private val DEFAULT_TTL = 604800L // 7 days fallback

    /**
     * 검색 결과 저장 (Redis 기반 - ZSET 및 개별 상세정보 캐싱)
     */
    suspend fun save(
        meetingId: Long, 
        result: PlacesSearchResponse,
        scores: Map<Long, Double> = emptyMap()
    ) = withContext(coroutineDispatchers.VT) {
        val meetingKey = "$MEETING_KEY_PREFIX$meetingId"
        
        // 1. 기존 모임 결과 지우고 새로 저장 (ZSET)
        redisTemplate.delete(meetingKey)
        
        if (result.items.isNotEmpty()) {
            val ttlSeconds = calculateMeetingTTL(meetingId)
            result.items.forEach { item ->
                val score = scores[item.placeId] ?: 0.0
                redisTemplate.opsForZSet().add(meetingKey, item.placeId.toString(), score)
            }
            if (ttlSeconds > 0) {
                redisTemplate.expire(meetingKey, ttlSeconds, TimeUnit.SECONDS)
            }
        }

        // 2. 장소별 상세정보 캐싱 (상세 정보 원본만 저장, 좋아요 정보는 조회 시점에 Redis Set에서 결합)
        result.items.forEach { item ->
            val placeKey = "$PLACE_KEY_PREFIX${item.placeId}"
            // 캐시에는 좋아요 상태를 포함하지 않고 원본 정보만 저장 (나중에 MGET 후 결합)
            val itemToCache = item.copy(likeCount = 0, isLiked = false)
            val json = objectMapper.writeValueAsString(itemToCache)
            redisTemplate.opsForValue().set(placeKey, json, 30, TimeUnit.DAYS)
        }
    }

    private suspend fun calculateMeetingTTL(meetingId: Long): Long {
        val meeting = meetingQuery.findById(meetingId)
        val endAt = meeting?.endAt ?: return DEFAULT_TTL
        
        val now = LocalDateTime.now(ZoneId.of("Asia/Seoul"))
        val duration = Duration.between(now, endAt)
        
        return if (duration.isNegative || duration.isZero) {
            3600L // 1 hour fallback
        } else {
            duration.seconds
        }
    }

    /**
     * 검색 결과 조회 (Redis 기반 MGET + 좋아요 실시간 결합)
     */
    suspend fun find(meetingId: Long, userId: Long? = null): PlacesSearchResponse? = withContext(coroutineDispatchers.VT) {
        val meetingKey = "$MEETING_KEY_PREFIX$meetingId"
        
        // 1. ZSET에서 점수 높은 순으로 상위 10개 ID 가져오기...
        val placeIds = redisTemplate.opsForZSet().reverseRange(meetingKey, 0, 9)
        
        if (placeIds.isNullOrEmpty()) {
            return@withContext null
        }

        // 2. 장소 상세 정보 (Global Cache) 일괄 조회
        val placeKeys = placeIds.map { "$PLACE_KEY_PREFIX$it" }
        val cachedJsons = redisTemplate.opsForValue().multiGet(placeKeys) ?: return@withContext null

        val items = mutableListOf<PlacesSearchResponse.PlaceItem>()
        val missingIndices = mutableListOf<Int>()
        val missingPlaceIds = mutableListOf<Long>()

        for (i in placeIds.indices) {
            val json = cachedJsons[i]
            if (!json.isNullOrBlank()) {
                items.add(objectMapper.readValue(json, PlacesSearchResponse.PlaceItem::class.java))
            } else {
                val dbId = placeIds.elementAt(i).toLong()
                missingPlaceIds.add(dbId)
                missingIndices.add(i)
                // Placeholder
                items.add(PlacesSearchResponse.PlaceItem(
                    placeId = -1L, name = "", address = "", rating = null, userRatingsTotal = null,
                    openNow = null, photos = null, link = "", weekdayText = null, topReview = null,
                    priceRange = null, addressDescriptor = null
                ))
            }
        }

        // 3. Cache Miss 복구
        if (missingPlaceIds.isNotEmpty()) {
            val entities = placeQuery.findByIds(missingPlaceIds)
            val entityMap = entities.associateBy { it.id }

            for ((idxInMissing, dbId) in missingPlaceIds.withIndex()) {
                val entity = entityMap[dbId]
                if (entity != null) {
                    val recovered = PlacesSearchResponse.PlaceItem(
                        placeId = entity.id!!,
                        name = org.depromeet.team3.place.util.PlaceFormatter.extractKoreanName(entity.name),
                        address = entity.address.replace("대한민국 ", "").replace(" South Korea", "").replace(", South Korea", ""),
                        rating = entity.rating,
                        userRatingsTotal = entity.userRatingsTotal,
                        openNow = entity.openNow,
                        photos = entity.photos?.split(",")?.map { photoName ->
                            "https://places.googleapis.com/v1/$photoName/media?key=${googlePlacesApiProperties.apiKey}&maxHeightPx=1000&maxWidthPx=1000"
                        },
                        link = entity.link ?: "",
                        weekdayText = entity.weekdayText?.split("\n"),
                        topReview = run {
                            val reviewRating = entity.topReviewRating
                            val reviewText = entity.topReviewText
                            if (reviewRating != null && reviewText != null) {
                                PlacesSearchResponse.PlaceItem.Review(rating = reviewRating.toInt(), text = reviewText)
                            } else null
                        },
                        priceRange = null,
                        addressDescriptor = entity.addressDescriptor?.let { desc ->
                            PlacesSearchResponse.PlaceItem.AddressDescriptor(description = org.depromeet.team3.place.util.PlaceFormatter.extractKoreanName(desc))
                        }
                    )
                    items[missingIndices[idxInMissing]] = recovered
                    redisTemplate.opsForValue().set("$PLACE_KEY_PREFIX$dbId", objectMapper.writeValueAsString(recovered), 30, TimeUnit.DAYS)
                }
            }
        }

        // 4. 실시간 좋아요 정보 결합 (Redis Pipeline 사용: N번의 RTT -> 1번의 RTT)
        val finalItemsToProcess = items.filter { it.placeId != -1L }
        if (finalItemsToProcess.isEmpty()) return@withContext null

        val pipelineResults = redisTemplate.executePipelined { connection ->
            finalItemsToProcess.forEach { item ->
                val likeKey = String.format(LIKE_KEY_TEMPLATE, meetingId, item.placeId).toByteArray()
                connection.setCommands().sCard(likeKey) // 좋아요 수 조회
                if (userId != null) {
                    connection.setCommands().sIsMember(likeKey, userId.toString().toByteArray()) // 내 좋아요 여부 조회
                }
            }
            null
        }

        var resIdx = 0
        val finalItems = finalItemsToProcess.map { item ->
            val likeCount = (pipelineResults[resIdx++] as? Long) ?: 0L
            val isLiked = if (userId != null) (pipelineResults[resIdx++] as? Boolean) ?: false else false
            
            item.copy(
                likeCount = likeCount.toInt(),
                isLiked = isLiked
            )
        }
        PlacesSearchResponse(finalItems)
    }

    fun getLikeKey(meetingId: Long, placeId: Long): String = String.format(LIKE_KEY_TEMPLATE, meetingId, placeId)
    fun getMeetingKey(meetingId: Long): String = "$MEETING_KEY_PREFIX$meetingId"
}