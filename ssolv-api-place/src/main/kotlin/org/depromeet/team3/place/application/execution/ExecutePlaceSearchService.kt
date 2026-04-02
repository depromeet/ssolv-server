package org.depromeet.team3.place.application.execution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
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
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import org.depromeet.team3.common.GooglePlacesApiProperties
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.meeting.MeetingQuery
import org.depromeet.team3.place.PlaceEntity
import org.depromeet.team3.place.PlaceQuery
import org.depromeet.team3.place.application.model.PlaceSearchPlan
import org.depromeet.team3.place.application.plan.CreateSurveyKeywordService
import org.depromeet.team3.place.dto.request.PlacesSearchRequest
import org.depromeet.team3.place.dto.response.PlacesSearchResponse
import org.depromeet.team3.place.exception.PlaceSearchException
import org.depromeet.team3.common.util.DistributedLockService
import org.depromeet.team3.place.model.PlacesTextSearchResponse

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import kotlinx.coroutines.delay

/**
 * 설문 데이터를 기반으로 최적의 장소(식당)를 도출하는 핵심 서비스입니다.
 */
@Service
class ExecutePlaceSearchService(
    private val placeQuery: PlaceQuery,
    private val searchService: MeetingPlaceSearchService,
    private val createSurveyKeywordService: CreateSurveyKeywordService,
    private val googlePlacesApiProperties: GooglePlacesApiProperties,
    private val redisTemplate: StringRedisTemplate,
    private val globalApiSemaphore: Semaphore,
    private val meterRegistry: MeterRegistry,
    private val lockService: DistributedLockService,
) {

    private val logger = LoggerFactory.getLogger(ExecutePlaceSearchService::class.java)
    private val totalFetchSize = 10
    private val photoFallbackBuffer = 5
    private val keywordFetchSize = 20
    private val weightScoreMultiplier = 100.0
    private val likeScoreMultiplier = 50.0
    private val googleApiTimeoutMs = 3000L // 개별 Google API 호출 타임아웃 (3초)

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
        val actualDispatcher = dispatcher ?: Dispatchers.IO
        return withContext(MDCContext() + actualDispatcher) {
            supervisorScope {
                val meetingId = request.meetingId ?: throw IllegalArgumentException("Meeting ID is required")

                val storedResult = searchService.find(meetingId, request.userId)

                if (storedResult != null) {
                    return@supervisorScope storedResult
                }

                val automaticPlan = plan as? PlaceSearchPlan.Automatic
                    ?: throw IllegalArgumentException("PlaceSearchPlan.Automatic만 지원합니다.")

                // 캐시가 없으면 락을 이용해 구글 API 호출 통제 (Cache Stampede 방어)
                val lockKey = "place_search_lock:$meetingId"
                repeat(5) { attempt ->
                    val result = lockService.withLock(lockKey, Duration.ofSeconds(10)) {
                        // 락 획득 후 캐시 다시 확인 (Double-Check)
                        searchService.find(meetingId, null)?.let { return@withLock it }

                        // 캐시가 여전히 없으면 직접 실행 및 결과 저장
                        val keywordResult = fetchPlacesForKeywords(automaticPlan, photoFallbackBuffer, actualDispatcher)
                        doProcessSearchResults(meetingId, keywordResult, actualDispatcher)
                    }

                    if (result != null) return@supervisorScope result

                    // 락 획득 실패 시: 다른 노드/스레드가 작업 중이므로 잠시 대기 후 캐시 확인
                    delay(1000)
                    searchService.find(meetingId, null)?.let { return@supervisorScope it }
                }

                // 최후의 수단: 5초 대기 후에도 락이나 캐시 결과가 없으면 직접 실행 수행 (Fallback)
                val keywordResult = fetchPlacesForKeywords(automaticPlan, photoFallbackBuffer, actualDispatcher)
                return@supervisorScope doProcessSearchResults(meetingId, keywordResult, actualDispatcher)
            }
        }
    }

    // Helper function to contain the actual processing logic
    private suspend fun doProcessSearchResults(
        meetingId: Long,
        keywordResult: KeywordSearchResult,
        actualDispatcher: CoroutineDispatcher
    ): PlacesSearchResponse {
        val candidatePlaces = (keywordResult.places + keywordResult.fallbackPlaces).distinctBy { it.id }
        
        // 거리 기반 필터링 (역 기준 5km 이상은 제외)
        val stationCoords = (createSurveyKeywordService.getStationCoordinates(meetingId)) ?: return PlacesSearchResponse(emptyList())
        val filteredPlaces = candidatePlaces.filter { place ->
            val placeLoc = place.location ?: return@filter true // 위치 정보 없으면 일단 포함
            calculateDistance(stationCoords.latitude, stationCoords.longitude, placeLoc.latitude, placeLoc.longitude) <= 5.0
        }

        val placesToProcess = filteredPlaces
            .sortedByDescending { keywordResult.placeWeights[it.id] ?: 0.0 }
            .take(totalFetchSize + photoFallbackBuffer)

        val savedEntities = withContext(actualDispatcher) {
            placeQuery.savePlacesFromTextSearch(placesToProcess)
        }

        if (savedEntities.isEmpty()) return PlacesSearchResponse(emptyList())

        val googlePlaceIds = savedEntities.mapNotNull { it.googlePlaceId }
        val placeIdMap = getPlaceStringIdToDbIdMap(googlePlaceIds, actualDispatcher)
        val placeWeightByDbId = keywordResult.placeWeights.mapNotNull { (googleId, weight) ->
            placeIdMap[googleId]?.let { it to weight }
        }.toMap()

        val likesMap = if (meetingId != null) { // Use the passed meetingId
            buildLikesMapFromRedis(meetingId, savedEntities, null) // userId is null for cached result
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
            
            // 거리 가중치 추가 (역 기준 좌표 정보를 다시 조회)
            val distanceScore = savedEntities.find { it.id == item.placeId }?.let { entity ->
                if (entity.latitude != null && entity.longitude != null && stationCoords != null) {
                    val dist = calculateDistance(stationCoords.latitude, stationCoords.longitude, entity.latitude!!, entity.longitude!!)
                    maxOf(0.0, 100.0 - (dist * 20.0)) // 5km 이내일 때 0~100점 (가까울수록 높음)
                } else 0.0
            } ?: 0.0
            
            item.placeId to (weightScore + likeScore + distanceScore)
        }

        val sortedItems = items.sortedWith(
            compareByDescending<PlacesSearchResponse.PlaceItem> { scoreByPlaceId[it.placeId] ?: 0.0 }
                .thenByDescending { it.likeCount }
        )

        val finalItems = sortedItems.take(totalFetchSize)
        val response = PlacesSearchResponse(finalItems)

        if (meetingId != null && finalItems.isNotEmpty()) {
            searchService.save(meetingId, response, scoreByPlaceId)
        }

        return response
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
        val requestSemaphore = Semaphore(4)
        val parentContext = currentCoroutineContext()
        val deferredResponses = plan.keywords.map { candidate ->
            async(parentContext + dispatcher) {
                ensureActive()
                runCatching {
                    candidate to fetchPlacesFromGoogle(candidate.keyword, plan.stationCoordinates, dispatcher, requestSemaphore)
                }.onFailure { e ->
                    // 부모 코루틴 취소(구조화된 동시성)는 삼키지 않고 재전파
                    if (e is CancellationException && e !is TimeoutCancellationException) throw e
                    logger.warn("키워드 [${candidate.keyword}] 검색 중 오류 발생: ${e.message}")
                }.getOrNull()
            }
        }

        val results = deferredResponses.awaitAll().filterNotNull()

        val allocations = calculateKeywordAllocations(results.map { it.first.weight }, totalFetchSize)

        val selectedPlaces = mutableListOf<PlacesTextSearchResponse.Place>()
        val placeWeights = mutableMapOf<String, Double>()
        val usedPlaceIds = mutableSetOf<String>()
        val fallbackCandidates = mutableListOf<PlacesTextSearchResponse.Place>()
        val fallbackIds = mutableSetOf<String>()
        val appliedKeywords = mutableSetOf<String>()

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
            
            // Fallback keyword: primary 키워드와 동일하게 예외를 격리하여 전체 검색 실패 방지
            if (addedCount < allocation && selectedPlaces.size < totalFetchSize && !candidate.fallbackKeyword.isNullOrBlank()) {
                runCatching {
                    val fbResponse = fetchPlacesFromGoogle(candidate.fallbackKeyword, plan.stationCoordinates, dispatcher, requestSemaphore)
                    filterPlacesByKeyword(fbResponse.places ?: emptyList(), candidate, candidate.fallbackMatchKeywords).sortedByDescending { it.rating ?: 0.0 }
                }.onFailure { e ->
                    if (e is CancellationException && e !is TimeoutCancellationException) throw e
                    logger.warn("Fallback 키워드 [${candidate.fallbackKeyword}] 검색 중 오류 발생: ${e.message}")
                }.getOrNull()?.forEach { place ->
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

        // 비즈니스 규칙: API 호출은 성공했지만 필터 결과가 모두 비어있는 경우도 실패 처리
        if (selectedPlaces.isEmpty() && fallbackCandidates.isEmpty()) {
            logger.error("모든 키워드 검색 결과가 비어 있습니다. (Keywords: ${plan.keywords.map { it.keyword }})")
            throw PlaceSearchException(ErrorCode.PLACE_NOT_FOUND)
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
                    try {
                        logger.info("Google API 검색 실행: query={}, coords=({}, {})", query, stationCoordinates?.latitude, stationCoordinates?.longitude)
                        val response = withTimeout(googleApiTimeoutMs) {
                            placeQuery.textSearch(
                                sanitizedQuery,
                                keywordFetchSize,
                                stationCoordinates?.latitude,
                                stationCoordinates?.longitude,
                                3000.0
                            )
                        }
                        recordMetric("success")
                        response
                    } catch (e: TimeoutCancellationException) {
                        recordMetric("timeout")
                        logger.error("Google API 호출 타임아웃 ($googleApiTimeoutMs ms): query=$query")
                        throw e
                    } catch (e: PlaceSearchException) {
                        val reason = if (e.errorCode == ErrorCode.PLACE_API_ERROR) "api_error" else "business_error"
                        recordMetric(reason)
                        logger.warn("Google API 비즈니스 오류 발생: ${e.errorCode}, query=$query")
                        throw e
                    } catch (e: Exception) {
                        recordMetric("unknown_error")
                        logger.error("Google API 알 수 없는 오류 발생: query=$query, message=${e.message}")
                        throw e
                    }
                }
            }
        }
    }

    private fun recordMetric(result: String) {
        meterRegistry.counter("google.api.call.count", listOf(Tag.of("result", result))).increment()
    }

    private suspend fun getPlaceStringIdToDbIdMap(googlePlaceIds: List<String>, dispatcher: CoroutineDispatcher): Map<String, Long> {
        return withContext(dispatcher) {
            placeQuery.findByGooglePlaceIds(googlePlaceIds).mapNotNull { it.id?.let { id -> it.googlePlaceId?.let { gid -> gid to id } } }.toMap()
        }
    }

    /**
     * 하버사인 공식 (Haversine Formula) - 두 좌표 간의 직선 거리 계산 (단위: km)
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0 // 지구 반지름 (km)
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }

    private data class KeywordSearchResult(val places: List<PlacesTextSearchResponse.Place>, val fallbackPlaces: List<PlacesTextSearchResponse.Place>, val placeWeights: Map<String, Double>, val usedKeywords: List<String>)
    private data class PlaceLikeInfo(val likeCount: Int, val isLiked: Boolean)
}
