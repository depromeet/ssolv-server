package org.depromeet.team3.place.application.execution

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.withContext
import org.depromeet.team3.common.GooglePlacesApiProperties
import org.depromeet.team3.common.util.CoroutineDispatchers
import org.depromeet.team3.place.PlaceQuery
import org.depromeet.team3.place.dto.response.PlacesSearchResponse
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
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
    private val coroutineDispatchers: CoroutineDispatchers
) {

    private val MEETING_KEY_PREFIX = "meeting:places:"
    private val PLACE_KEY_PREFIX = "place:details:"

    /**
     * 검색 결과 저장 (Redis 기반)
     */
    suspend fun save(meetingId: Long, result: PlacesSearchResponse) = withContext(coroutineDispatchers.VT) {
        val meetingKey = "$MEETING_KEY_PREFIX$meetingId"
        
        // 1. 기존 모임 결과 지우고 새로 저장
        redisTemplate.delete(meetingKey)
        val placeIds = result.items.map { it.placeId.toString() }
        
        if (placeIds.isNotEmpty()) {
            redisTemplate.opsForList().rightPushAll(meetingKey, *placeIds.toTypedArray())
            redisTemplate.expire(meetingKey, 7, TimeUnit.DAYS) // 모임 검색 결과 리스트는 적당한 기간 만료
        }

        // 2. 장소별 상세정보 캐싱 (실시간 데이터인 좋아요 0으로 초기화하여 저장)
        result.items.forEach { item ->
            val placeKey = "$PLACE_KEY_PREFIX${item.placeId}"
            val itemToCache = item.copy(likeCount = 0, isLiked = false) // 캐시에는 좋아요 상태를 포함하지 않음
            val json = objectMapper.writeValueAsString(itemToCache)
            redisTemplate.opsForValue().set(placeKey, json, 30, TimeUnit.DAYS) // 구글 API 약관: 30일
        }
    }

    /**
     * 검색 결과 조회 (Redis 기반 MGET 최적화)
     */
    suspend fun find(meetingId: Long): PlacesSearchResponse? = withContext(coroutineDispatchers.VT) {
        val meetingKey = "$MEETING_KEY_PREFIX$meetingId"
        val placeIds = redisTemplate.opsForList().range(meetingKey, 0, -1)
        
        if (placeIds.isNullOrEmpty()) {
            return@withContext null
        }

        val placeKeys = placeIds.map { "$PLACE_KEY_PREFIX$it" }
        val cachedJsons = redisTemplate.opsForValue().multiGet(placeKeys) ?: return@withContext null

        val items = mutableListOf<PlacesSearchResponse.PlaceItem>()
        val missingPlaceIds = mutableListOf<Long>()
        val missingIndices = mutableListOf<Int>()

        for (i in placeIds.indices) {
            val json = cachedJsons[i]
            if (!json.isNullOrBlank()) {
                items.add(objectMapper.readValue(json, PlacesSearchResponse.PlaceItem::class.java))
            } else {
                missingPlaceIds.add(placeIds[i].toLong())
                missingIndices.add(i)
                // 나중에 채워넣을 플레이스홀더 추가
                items.add(PlacesSearchResponse.PlaceItem(
                    placeId = -1L, name = "", address = "", rating = null, userRatingsTotal = null,
                    openNow = null, photos = null, link = "", weekdayText = null, topReview = null,
                    priceRange = null, addressDescriptor = null
                ))
            }
        }

        // 3. 누락된 캐시 복원 (Cache Miss) - 30일이 지난 데이터거나 서버 재시작으로 지워진 경우
        if (missingPlaceIds.isNotEmpty()) {
            val missingEntities = placeQuery.findByIds(missingPlaceIds)
            val missingEntityMap = missingEntities.associateBy { it.id }

            for ((listIdx, dbId) in missingPlaceIds.withIndex()) {
                val entity = missingEntityMap[dbId]
                if (entity != null) {
                    val placeKey = "$PLACE_KEY_PREFIX$dbId"
                    val recoveredItem = PlacesSearchResponse.PlaceItem(
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
                                PlacesSearchResponse.PlaceItem.Review(
                                    rating = reviewRating.toInt(),
                                    text = reviewText
                                )
                            } else null
                        },
                        priceRange = null,
                        addressDescriptor = entity.addressDescriptor?.let { desc ->
                            PlacesSearchResponse.PlaceItem.AddressDescriptor(
                                description = org.depromeet.team3.place.util.PlaceFormatter.extractKoreanName(desc)
                            )
                        }
                    )
                    
                    val actualIdx = missingIndices[listIdx]
                    items[actualIdx] = recoveredItem

                    // 복원한 항목 다시 캐시에 저장 (30일 TTL 갱신)
                    val json = objectMapper.writeValueAsString(recoveredItem)
                    redisTemplate.opsForValue().set(placeKey, json, 30, TimeUnit.DAYS)
                }
            }
        }
        
        // 데이터 정합성 실패로 남은 dummy 항목 제거 (안전 장치)
        val finalItems = items.filter { it.placeId != -1L }
        
        if (finalItems.isEmpty()) {
            return@withContext null
        }

        PlacesSearchResponse(finalItems)
    }
}