package org.depromeet.team3.place.application.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.slf4j.MDCContext
import org.depromeet.team3.meeting.MeetingQuery
import org.depromeet.team3.place.PlaceQuery
import org.depromeet.team3.place.application.search.model.PlaceSearchPlan
import org.depromeet.team3.place.dto.request.PlacesSearchRequest
import org.depromeet.team3.place.dto.response.PlacesSearchResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.depromeet.team3.place.util.PlaceFormatter
import org.depromeet.team3.common.GooglePlacesApiProperties
import org.depromeet.team3.place.application.llm.ProcessPlaceLlmService
import org.depromeet.team3.place.application.search.components.ManagePlaceSearchService
import org.depromeet.team3.place.application.search.components.RankPlaceSearchService
import org.depromeet.team3.place.application.search.components.SearchGooglePlaceService

/**
 * 장소 검색 실행의 전체 흐름(Google 검색, 정렬, LLM 분석, 캐싱)을 조율하는 오케스트레이션 서비스
 */
@Service
class ExecutePlaceSearchService(
    private val meetingQuery: MeetingQuery,
    private val placeQuery: PlaceQuery,
    private val managePlaceSearchService: ManagePlaceSearchService,
    private val searchGooglePlaceService: SearchGooglePlaceService,
    private val rankPlaceSearchService: RankPlaceSearchService,
    private val processPlaceLlmService: ProcessPlaceLlmService,
    private val googlePlacesApiProperties: GooglePlacesApiProperties
) {
    private val logger = LoggerFactory.getLogger(ExecutePlaceSearchService::class.java)
    private val totalFetchSize = 10
    private val photoFallbackBuffer = 5

    /**
     * 장소 검색 요청을 처리한다. 캐시 확인 -> Google 검색 -> LLM 필터링 -> 정렬 및 상세 분석 적용 순으로 진행한다.
     */
    suspend fun search(request: PlacesSearchRequest, plan: PlaceSearchPlan): PlacesSearchResponse = withContext(MDCContext()) {
        supervisorScope {
            // 1. 캐시 시트(이미 검색된 결과) 확인
            if (plan is PlaceSearchPlan.Automatic && request.meetingId != null) {
                managePlaceSearchService.find(request.meetingId)?.let { cached ->
                    val updatedItems = rankPlaceSearchService.updateLikesForStoredItems(cached.items, request.meetingId, request.userId)
                    return@supervisorScope PlacesSearchResponse(updatedItems)
                }
            }

            val automaticPlan = plan as? PlaceSearchPlan.Automatic ?: throw IllegalArgumentException("Automatic 검색만 지원합니다.")

            // 2. Google Places API 기반 후보 장소 수집
            val searchResult = searchGooglePlaceService.findPlacesByKeywords(automaticPlan, totalFetchSize, photoFallbackBuffer)
            val candidatePlaces = (searchResult.places + searchResult.fallbackPlaces).distinctBy { it.id }

            // 3. 모임 정보 기반 LLM 필터링 및 추천 사유 생성
            val filteredIds = if (request.meetingId != null) {
                val meeting = meetingQuery.findById(request.meetingId)
                processPlaceLlmService.filterByCriteria(candidatePlaces, "모임명: ${meeting?.name ?: "모임"}")
            } else emptyMap()

            // 4. 검색된 장소들을 DB에 동기화 (최신 정보 유지)
            val placesToProcess = candidatePlaces
                .sortedByDescending { it.rating ?: 0.0 }
                .take(totalFetchSize + photoFallbackBuffer)

            val savedEntities = withContext(Dispatchers.IO) { placeQuery.savePlacesFromTextSearch(placesToProcess) }
            if (savedEntities.isEmpty()) return@supervisorScope PlacesSearchResponse(emptyList())

            // 5. 좋아요 상태 및 모임 장소 동기화
            val meetingPlaces = request.meetingId?.let { rankPlaceSearchService.syncMeetingPlaces(it, savedEntities.mapNotNull { e -> e.id }) } ?: emptyList()
            val likesMap = rankPlaceSearchService.buildLikesMap(savedEntities.mapNotNull { it.googlePlaceId }, meetingPlaces, request.userId)

            // 6. 응답 DTO 변환 및 기본 데이터 매핑
            val googleToDbId = savedEntities.associate { it.googlePlaceId!! to it.id!! }
            val dbToGoogleId = savedEntities.associate { it.id!! to it.googlePlaceId!! }
            val weightByDbId = searchResult.placeWeights.mapNotNull { (gId, w) -> googleToDbId[gId]?.let { it to w } }.toMap()

            val items = savedEntities.mapNotNull { entity ->
                runCatching {
                    val googleId = entity.googlePlaceId!!
                    val likeInfo = likesMap[googleId] ?: RankPlaceSearchService.PlaceLikeInfo(0, false)

                    PlacesSearchResponse.PlaceItem(
                        placeId = entity.id!!,
                        name = entity.name ?: "",
                        address = entity.address?.replace("대한민국 ", "") ?: "",
                        rating = entity.rating,
                        userRatingsTotal = entity.userRatingsTotal,
                        openNow = entity.openNow,
                        photos = entity.photos?.split(",")?.map { PhotoName ->
                            PlaceFormatter.generatePhotoUrl(PhotoName, googlePlacesApiProperties.proxyBaseUrl)
                        },
                        link = entity.link ?: "",
                        weekdayText = entity.weekdayText?.split("\n"),
                        topReview = filteredIds[googleId]?.reason?.let { reason ->
                            PlacesSearchResponse.PlaceItem.Review(rating = (entity.rating ?: 0.0).toInt(), text = reason)
                        } ?: entity.llmReason?.let { reason ->
                            PlacesSearchResponse.PlaceItem.Review(rating = (entity.rating ?: 0.0).toInt(), text = reason)
                        } ?: entity.topReviewText?.let { text -> 
                            PlacesSearchResponse.PlaceItem.Review(rating = entity.topReviewRating?.toInt() ?: 0, text = text)
                        },
                        priceRange = null,
                        addressDescriptor = entity.addressDescriptor?.let { desc ->
                            PlacesSearchResponse.PlaceItem.AddressDescriptor(description = desc)
                        },
                        likeCount = likeInfo.likeCount,
                        isLiked = request.userId != null && likeInfo.isLiked
                    )
                }.getOrNull()
            }

            // 7. 최종 랭킹 산정(가중치 적용 정렬) 및 LLM 랜드마크 상세 분석 보충
            val rankedItems = rankPlaceSearchService.rank(items, weightByDbId, filteredIds, dbToGoogleId).take(7)
            val enrichedItems = processPlaceLlmService.applyLlmDetails(rankedItems, savedEntities, filteredIds)

            val response = PlacesSearchResponse(enrichedItems)
            
            // 8. 모임용 검색 결과로 캐싱
            if (request.meetingId != null && enrichedItems.isNotEmpty()) {
                managePlaceSearchService.save(request.meetingId, response)
            }

            response
        }
    }
}