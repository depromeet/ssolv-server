package org.depromeet.team3.place.application.search.components

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.depromeet.team3.meetingplace.MeetingPlace
import org.depromeet.team3.meetingplace.MeetingPlaceRepository
import org.depromeet.team3.place.PlaceQuery
import org.depromeet.team3.place.application.llm.model.PlaceLlmFilterResult
import org.depromeet.team3.place.dto.response.PlacesSearchResponse
import org.depromeet.team3.placelike.PlaceLikeRepository
import org.springframework.stereotype.Service
import kotlin.math.ln

/**
 * 장소의 가중치, 좋아요 및 LLM 점수를 기반으로 정렬 순위를 결정하는 서비스
 */
@Service
class RankPlaceSearchService(
    private val meetingPlaceRepository: MeetingPlaceRepository,
    private val placeLikeRepository: PlaceLikeRepository,
    private val placeQuery: PlaceQuery
) {
    private val weightScoreMultiplier = 100.0
    private val likeScoreMultiplier = 50.0

    /**
     * 가중치, 좋아요 개수, 사용자 좋아요 여부, LLM 추천 여부를 종합하여 점수를 산출하고 정렬한다.
     */
    fun rank(
        items: List<PlacesSearchResponse.PlaceItem>,
        placeWeights: Map<Long, Double>,
        filteredIds: Map<String, PlaceLlmFilterResult>,
        googleIdMap: Map<Long, String>
    ): List<PlacesSearchResponse.PlaceItem> {
        val scoreMap = items.associate { item ->
            val weight = placeWeights[item.placeId] ?: 0.0
            val likeScore = if (item.likeCount > 0) ln(item.likeCount.toDouble() + 1) * likeScoreMultiplier else 0.0
            val userLikedBoost = if (item.isLiked) 100.0 else 0.0
            val llmBoost = if (filteredIds.containsKey(googleIdMap[item.placeId])) 100.0 else 0.0
            
            item.placeId to (weight * weightScoreMultiplier + likeScore + userLikedBoost + llmBoost)
        }

        return items.sortedWith(
            compareByDescending<PlacesSearchResponse.PlaceItem> { !it.photos.isNullOrEmpty() }
                .thenByDescending { scoreMap[it.placeId] ?: 0.0 }
                .thenByDescending { it.likeCount }
        )
    }

    /**
     * 장소별 좋아요 개수 및 현재 사용자의 좋아요 여부 정보를 맵으로 생성한다.
     */
    suspend fun buildLikesMap(
        googlePlaceIds: List<String>,
        meetingPlaces: List<MeetingPlace>,
        userId: Long?
    ): Map<String, PlaceLikeInfo> = withContext(Dispatchers.IO) {
        val placeDbIds = placeQuery.findByGooglePlaceIds(googlePlaceIds).mapNotNull { 
            val gId = it.googlePlaceId ?: return@mapNotNull null
            val dbId = it.id ?: return@mapNotNull null
            gId to dbId
        }.toMap()
        val meetingPlaceIds = meetingPlaces.mapNotNull { it.id }
        if (meetingPlaceIds.isEmpty()) return@withContext emptyMap()

        val likes = placeLikeRepository.findByMeetingPlaceIds(meetingPlaceIds)
        val meetingPlaceToDbId = meetingPlaces.associate { it.id!! to it.placeId }
        val likesByDbId = likes.groupBy { meetingPlaceToDbId[it.meetingPlaceId] }

        googlePlaceIds.associateWith { gId ->
            val dbId = placeDbIds[gId]
            val placeLikes = if (dbId != null) likesByDbId[dbId] ?: emptyList() else emptyList()
            PlaceLikeInfo(placeLikes.size, userId != null && placeLikes.any { it.userId == userId })
        }
    }

    /**
     * 모임에 장소들을 등록하고 기존 등록 리스트를 반환한다.
     */
    suspend fun syncMeetingPlaces(meetingId: Long, placeDbIds: List<Long>): List<MeetingPlace> = withContext(Dispatchers.IO) {
        val existing = meetingPlaceRepository.findByMeetingId(meetingId)
        val existingIds = existing.map { it.placeId }.toSet()
        val newPlaces = placeDbIds.filter { it !in existingIds }.map { MeetingPlace(meetingId = meetingId, placeId = it) }
        if (newPlaces.isNotEmpty()) existing + meetingPlaceRepository.saveAll(newPlaces) else existing
    }

    /**
     * 저장된 검색 결과(캐시 등)의 실시간 좋아요 정보를 업데이트한다.
     */
    suspend fun updateLikesForStoredItems(
        storedItems: List<PlacesSearchResponse.PlaceItem>,
        meetingId: Long,
        userId: Long?
    ): List<PlacesSearchResponse.PlaceItem> = withContext(Dispatchers.IO) {
        if (storedItems.isEmpty()) return@withContext emptyList()
        val meetingPlaces = syncMeetingPlaces(meetingId, storedItems.map { it.placeId })
        val meetingPlaceToDbId = meetingPlaces.associate { it.id!! to it.placeId }
        val likes = placeLikeRepository.findByMeetingPlaceIds(meetingPlaceToDbId.keys.toList())
        val likesByDbId = likes.groupBy { meetingPlaceToDbId[it.meetingPlaceId] }
        
        storedItems.map { item ->
            val itemLikes = likesByDbId[item.placeId] ?: emptyList()
            item.copy(
                likeCount = itemLikes.size,
                isLiked = userId != null && itemLikes.any { it.userId == userId }
            )
        }
    }

    data class PlaceLikeInfo(val likeCount: Int, val isLiked: Boolean)
}
