package org.depromeet.team3.place.application.execution

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.slf4j.MDCContext
import org.depromeet.team3.common.GooglePlacesApiProperties
import org.depromeet.team3.common.util.CoroutineDispatchers
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.meeting.MeetingQuery
import org.depromeet.team3.place.PlaceEntity
import org.depromeet.team3.place.PlaceQuery
import org.depromeet.team3.place.application.model.PlaceSearchPlan
import org.depromeet.team3.place.application.plan.CreateSurveyKeywordService
import org.depromeet.team3.place.dto.request.PlacesSearchRequest
import org.depromeet.team3.place.dto.response.PlacesSearchResponse
import org.depromeet.team3.place.exception.PlaceSearchException
import org.depromeet.team3.place.model.PlacesTextSearchResponse
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service

/**
 * 설문 데이터를 기반으로 최적의 장소(식당)를 도출하는 핵심 서비스입니다.
 */
@Service
class ExecutePlaceSearchService(
    private val placeQuery: PlaceQuery,
    private val searchService: MeetingPlaceSearchService,
    private val createSurveyKeywordService: CreateSurveyKeywordService,
    private val googlePlacesApiProperties: GooglePlacesApiProperties,
    private val coroutineDispatchers: CoroutineDispatchers,
    private val redisTemplate: StringRedisTemplate,
) {

    private val logger = LoggerFactory.getLogger(ExecutePlaceSearchService::class.java)
    private val totalFetchSize = 10
    private val photoFallbackBuffer = 5
    private val keywordFetchSize = 20
    private val weightScoreMultiplier = 100.0
    private val likeScoreMultiplier = 50.0
    private val googleApiTimeoutMs = 3000L // 개별 Google API 호출 타임아웃 (3초)

    /**
     * [전역 Semaphore] 서버 전체의 Google Places API 동시 호출 수 상한 제어
     *
     * Google Places API Rate Limit: 20~50 QPS (초당 최대 호출 수)
     * 적정 동시 호출 수 = QPS 하한(20) × 평균 응답시간(0.6s) ≈ 12
     * → 여유분 포함하여 15로 설정
     *
     * 역할: 모임방이 다수 동시에 처리되는 상황에서도 서버 전체 QPS가
     *       Google Rate Limit을 넘지 않도록 서버 레벨에서 보호
     */
    private val globalApiSemaphore = Semaphore(15)

    suspend fun execute(meetingId: Long): PlacesSearchResponse {
        val plan = createSurveyKeywordService.generateKeywordPlan(meetingId)
        val searchRequest = PlacesSearchRequest(meetingId = meetingId, userId = null)
        val searchPlan = PlaceSearchPlan.Automatic(
            keywords = plan.keywords,
            stationCoordinates = plan.stationCoordinates,
            fallbackKeyword = plan.fallbackKeyword
        )
        return search(searchRequest, searchPlan)
    }

    /**
     * 벤치마크용: 특정 디스패처를 지정하여 검색 수행
     */
    suspend fun searchWithDispatcher(
        request: PlacesSearchRequest,
        plan: PlaceSearchPlan,
        dispatcher: CoroutineDispatcher
    ): PlacesSearchResponse {
        return search(request, plan, dispatcher)
    }

    suspend fun search(
        request: PlacesSearchRequest,
        plan: PlaceSearchPlan,
        dispatcher: CoroutineDispatcher? = null
    ): PlacesSearchResponse {
        val actualDispatcher = dispatcher ?: coroutineDispatchers.VT
        return withContext(MDCContext() + actualDispatcher) {
            supervisorScope {
                val storedResult = if (plan is PlaceSearchPlan.Automatic && request.meetingId != null) {
                    searchService.find(request.meetingId, request.userId)
                } else null

                if (storedResult != null && request.meetingId != null) {
                    return@supervisorScope storedResult
                }

                val automaticPlan = plan as? PlaceSearchPlan.Automatic
                    ?: throw IllegalArgumentException("PlaceSearchPlan.Automatic만 지원합니다.")

                val keywordResult = fetchPlacesForKeywords(automaticPlan, photoFallbackBuffer, actualDispatcher)

                val candidatePlaces = (keywordResult.places + keywordResult.fallbackPlaces).distinctBy { it.id }
                val placesToProcess = candidatePlaces
                    .sortedByDescending { keywordResult.placeWeights[it.id] ?: 0.0 }
                    .take(totalFetchSize + photoFallbackBuffer)

                val savedEntities = withContext(actualDispatcher) {
                    placeQuery.savePlacesFromTextSearch(placesToProcess)
                }

                if (savedEntities.isEmpty()) return@supervisorScope PlacesSearchResponse(emptyList())

                val googlePlaceIds = savedEntities.mapNotNull { it.googlePlaceId }
                val placeIdMap = getPlaceStringIdToDbIdMap(googlePlaceIds, actualDispatcher)
                val placeWeightByDbId = keywordResult.placeWeights.mapNotNull { (googleId, weight) ->
                    placeIdMap[googleId]?.let { it to weight }
                }.toMap()

                val likesMap = if (request.meetingId != null) {
                    buildLikesMapFromRedis(request.meetingId, savedEntities, request.userId)
                } else emptyMap()

                val items = savedEntities.mapNotNull { entity ->
                    runCatching {
                        val placeDbId = entity.id ?: return@mapNotNull null
                        val likeInfo = likesMap[placeDbId] ?: PlaceLikeInfo(0, false)

                        PlacesSearchResponse.PlaceItem(
                            placeId = placeDbId,
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
                            topReview = entity.topReviewRating?.let { rating ->
                                entity.topReviewText?.let { text ->
                                    PlacesSearchResponse.PlaceItem.Review(rating.toInt(), text)
                                }
                            },
                            priceRange = null,
                            addressDescriptor = entity.addressDescriptor?.let { desc ->
                                PlacesSearchResponse.PlaceItem.AddressDescriptor(org.depromeet.team3.place.util.PlaceFormatter.extractKoreanName(desc))
                            },
                            likeCount = likeInfo.likeCount,
                            isLiked = likeInfo.isLiked
                        )
                    }.getOrNull()
                }

                val scoreByPlaceId = items.associate { item ->
                    val weight = placeWeightByDbId[item.placeId] ?: 0.0
                    val weightScore = weight * weightScoreMultiplier
                    val likeScore = if (item.likeCount > 0) item.likeCount * likeScoreMultiplier else 0.0
                    item.placeId to (weightScore + likeScore)
                }

                val sortedItems = items.sortedWith(
                    compareByDescending<PlacesSearchResponse.PlaceItem> { scoreByPlaceId[it.placeId] ?: 0.0 }
                        .thenByDescending { it.likeCount }
                )

                val finalItems = sortedItems.take(totalFetchSize)
                val response = PlacesSearchResponse(finalItems)

                if (request.meetingId != null && finalItems.isNotEmpty()) {
                    searchService.save(request.meetingId, response, scoreByPlaceId)
                }

                response
            }
        }
    }

    private suspend fun buildLikesMapFromRedis(meetingId: Long, places: List<PlaceEntity>, userId: Long?): Map<Long, PlaceLikeInfo> {
        val placeIds = places.mapNotNull { it.id }
        if (placeIds.isEmpty()) return emptyMap()

        val pipelineResults = redisTemplate.executePipelined { connection ->
            placeIds.forEach { placeId ->
                val likeKey = searchService.getLikeKey(meetingId, placeId).toByteArray()
                connection.setCommands().sCard(likeKey)
                if (userId != null) {
                    connection.setCommands().sIsMember(likeKey, userId.toString().toByteArray())
                }
            }
            null
        }

        var resIdx = 0
        return placeIds.associateWith {
            val likeCount = (pipelineResults[resIdx++] as? Long) ?: 0L
            val isLiked = if (userId != null) (pipelineResults[resIdx++] as? Boolean) ?: false else false
            PlaceLikeInfo(likeCount.toInt(), isLiked)
        }
    }

    private suspend fun fetchPlacesForKeywords(
        plan: PlaceSearchPlan.Automatic,
        fallbackLimit: Int,
        dispatcher: CoroutineDispatcher
    ): KeywordSearchResult = supervisorScope {
        val requestSemaphore = Semaphore(4) // 하나의 요청(모임방)당 동시 Google API 호출을 4개로 제한
        val parentContext = currentCoroutineContext()
        val deferredResponses = plan.keywords.map { candidate ->
            async(parentContext + dispatcher) {
                ensureActive()
                runCatching {
                    candidate to fetchPlacesFromGoogle(candidate.keyword, plan.stationCoordinates, dispatcher, requestSemaphore)
                }.onFailure { e ->
                    logger.warn("키워드 [${candidate.keyword}] 검색 중 오류 발생: ${e.message}")
                }.getOrNull()
            }
        }

        val results = deferredResponses.awaitAll().filterNotNull()
        
        // 비즈니스 규칙: 10개 키워드 시도 중 최소 1건 이상 결과가 확보되어야 함
        if (results.isEmpty()) {
            logger.error("모든 키워드 검색에 실패하여 유효 결과를 확보하지 못했습니다. (Keywords: ${plan.keywords.map { it.keyword }})")
            throw PlaceSearchException(ErrorCode.PLACE_NOT_FOUND)
        }

        val allocations = calculateKeywordAllocations(results.map { it.first.weight }, totalFetchSize)

        val selectedPlaces = mutableListOf<PlacesTextSearchResponse.Place>()
        val placeWeights = mutableMapOf<String, Double>()
        val usedPlaceIds = mutableSetOf<String>()
        val fallbackCandidates = mutableListOf<PlacesTextSearchResponse.Place>()
        val fallbackIds = mutableSetOf<String>()
        val appliedKeywords = mutableSetOf<String>()
        val fallbackResponses = mutableListOf<Pair<CreateSurveyKeywordService.KeywordCandidate, List<PlacesTextSearchResponse.Place>>>()

        results.forEach { appliedKeywords.add(it.first.keyword) }

        results.forEachIndexed { index, (candidate, response) ->
            val allocation = allocations[index]
            val rawPlaces = response.places ?: emptyList()
            val candidatePlaces = filterPlacesByKeyword(rawPlaces, candidate).sortedByDescending { it.rating ?: 0.0 }
            var addedCount = 0

            candidatePlaces.forEach { place ->
                if (usedPlaceIds.contains(place.id)) return@forEach
                if (addedCount < allocation && selectedPlaces.size < totalFetchSize) {
                    selectedPlaces.add(place)
                    placeWeights[place.id] = candidate.weight
                    usedPlaceIds.add(place.id)
                    addedCount++
                } else if (fallbackCandidates.size < fallbackLimit && fallbackIds.add(place.id)) {
                    placeWeights.putIfAbsent(place.id, candidate.weight)
                    fallbackCandidates.add(place)
                }
            }
            
            // Fallback keyword logic simplified for brevity but kept functional
            if (addedCount < allocation && selectedPlaces.size < totalFetchSize && !candidate.fallbackKeyword.isNullOrBlank()) {
                val fbResponse = fetchPlacesFromGoogle(candidate.fallbackKeyword!!, plan.stationCoordinates, dispatcher, requestSemaphore)
                val fbPlaces = filterPlacesByKeyword(fbResponse.places ?: emptyList(), candidate, candidate.fallbackMatchKeywords).sortedByDescending { it.rating ?: 0.0 }
                fbPlaces.forEach { place ->
                    if (usedPlaceIds.contains(place.id)) return@forEach
                    if (addedCount < allocation && selectedPlaces.size < totalFetchSize) {
                        selectedPlaces.add(place)
                        placeWeights[place.id] = candidate.weight
                        usedPlaceIds.add(place.id)
                        addedCount++
                    }
                }
            }
        }

        val finalPlaces = selectedPlaces.sortedWith(
            compareByDescending<PlacesTextSearchResponse.Place> { placeWeights[it.id] ?: 0.0 }
                .thenByDescending { it.rating ?: 0.0 }
        ).take(totalFetchSize)

        KeywordSearchResult(finalPlaces, fallbackCandidates.take(fallbackLimit), placeWeights, appliedKeywords.toList())
    }

    private fun filterPlacesByKeyword(places: List<PlacesTextSearchResponse.Place>, candidate: CreateSurveyKeywordService.KeywordCandidate, overrideKeywords: Set<String>? = null): List<PlacesTextSearchResponse.Place> {
        val keywords = overrideKeywords ?: candidate.matchKeywords
        if (keywords.isEmpty()) return emptyList()
        return places.filter { place ->
            val normalizedName = place.displayName.text.lowercase().replace(" ", "")
            val types = place.types?.map { it.lowercase() } ?: emptyList()
            keywords.any { keyword -> normalizedName.contains(keyword) || types.any { it.contains(keyword) } }
        }
    }

    private fun calculateKeywordAllocations(weights: List<Double>, totalSlots: Int): List<Int> {
        if (weights.isEmpty()) return emptyList()
        val totalWeight = weights.sum()
        if (totalWeight == 0.0) return List(weights.size) { totalSlots / weights.size }
        val allocations = weights.map { ((it / totalWeight) * totalSlots).toInt() }.toMutableList()
        var allocated = allocations.sum()
        if (allocated < totalSlots) {
            val sortedIndices = weights.indices.sortedByDescending { weights[it] }
            for (i in 0 until (totalSlots - allocated)) allocations[sortedIndices[i % sortedIndices.size]]++
        }
        return allocations
    }

    private suspend fun fetchPlacesFromGoogle(
        query: String,
        stationCoordinates: MeetingQuery.StationCoordinates?,
        dispatcher: CoroutineDispatcher,
        requestSemaphore: Semaphore
    ): PlacesTextSearchResponse {
        val sanitizedQuery = CreateSurveyKeywordService.normalizeKeyword(query)
        if (sanitizedQuery.isBlank()) throw PlaceSearchException(ErrorCode.PLACE_INVALID_QUERY)

        /**
         * 2-tier Semaphore 중첩 적용
         *
         * [외부] globalApiSemaphore(15): 서버 전체 동시 호출 수 제한 (QPS 상한 방어).
         *        모임방이 몇 개 동시에 처리되든, 서버 전체에서 Google API 호출은 항상 15개 이하
         *
         * [내부] requestSemaphore(4): 단일 모임방 내 fan-out 제한.
         *        한 모임방의 키워드가 아무리 많아도, 해당 모임방은 한 번에 4개까지만 호출
         *
         * 중첩 순서: 전역(외부) → 모임별(내부)
         * 이유: 전역 슬롯을 먼저 확보한 뒤 모임별 슬롯을 확보해야
         *       데드락 없이 항상 일관된 방향으로 락이 획득됨
         */
        return globalApiSemaphore.withPermit {
            requestSemaphore.withPermit {
                withContext(dispatcher) {
                    withTimeout(googleApiTimeoutMs) {
                        placeQuery.textSearch(
                            sanitizedQuery,
                            keywordFetchSize,
                            stationCoordinates?.latitude,
                            stationCoordinates?.longitude,
                            3000.0
                        )
                    }
                }
            }
        }
    }

    private suspend fun getPlaceStringIdToDbIdMap(googlePlaceIds: List<String>, dispatcher: CoroutineDispatcher): Map<String, Long> {
        return withContext(dispatcher) {
            placeQuery.findByGooglePlaceIds(googlePlaceIds).mapNotNull { it.id?.let { id -> it.googlePlaceId?.let { gid -> gid to id } } }.toMap()
        }
    }

    private data class KeywordSearchResult(val places: List<PlacesTextSearchResponse.Place>, val fallbackPlaces: List<PlacesTextSearchResponse.Place>, val placeWeights: Map<String, Double>, val usedKeywords: List<String>)
    private data class PlaceLikeInfo(val likeCount: Int, val isLiked: Boolean)
}
