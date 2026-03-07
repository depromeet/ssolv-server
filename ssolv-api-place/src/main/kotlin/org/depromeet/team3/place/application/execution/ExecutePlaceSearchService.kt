package org.depromeet.team3.place.application.execution

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.slf4j.MDCContext
import org.depromeet.team3.common.GooglePlacesApiProperties
import org.depromeet.team3.common.util.CoroutineDispatchers
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.meeting.MeetingQuery
import org.depromeet.team3.meetingplace.MeetingPlace
import org.depromeet.team3.meetingplace.MeetingPlaceRepository
import org.depromeet.team3.place.PlaceQuery
import org.depromeet.team3.place.application.model.PlaceSearchPlan
import org.depromeet.team3.place.application.plan.CreateSurveyKeywordService
import org.depromeet.team3.place.application.plan.CreateSurveyKeywordService.KeywordCandidate
import org.depromeet.team3.place.application.plan.CreateSurveyKeywordService.KeywordPlan
import org.depromeet.team3.place.dto.request.PlacesSearchRequest
import org.depromeet.team3.place.dto.response.PlacesSearchResponse
import org.depromeet.team3.place.exception.PlaceSearchException
import org.depromeet.team3.place.model.PlacesTextSearchResponse
import org.depromeet.team3.placelike.PlaceLikeRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import kotlin.math.ln

/**
 * 설문 데이터를 기반으로 최적의 장소(식당)를 도출하는 핵심 서비스입니다.
 * 
 * 주요 기능:
 * 1. 설문 기반 자동 장소 검색 (Automatic Search): 다수 인원의 선호도(가중치)를 반영한 키워드 병합 검색
 * 2. 수동 검색 (Manual Search): 특정 키워드에 대한 직접 검색 (추후 확장 가능)
 * 3. 정렬 및 필터링: 구글 평점, 사용자 좋아요, 설문 가중치를 종합하여 최종 10건 도출
 * 4. 결과 영속화: 검색된 결과를 DB에 저장하여 후속 요청 시 일관된 결과 제공
 */
@Service
class ExecutePlaceSearchService(
    private val placeQuery: PlaceQuery,
    private val meetingPlaceRepository: MeetingPlaceRepository,
    private val placeLikeRepository: PlaceLikeRepository,
    private val searchService: MeetingPlaceSearchService,
    private val createSurveyKeywordService: CreateSurveyKeywordService,
    private val googlePlacesApiProperties: GooglePlacesApiProperties,
    private val coroutineDispatchers: CoroutineDispatchers,
) {

    private val logger = LoggerFactory.getLogger(ExecutePlaceSearchService::class.java)
    private val totalFetchSize = 10  // 최종 반환 개수
    private val photoFallbackBuffer = 5  // 사진 없는 결과를 대체할 여분 슬롯
    private val keywordFetchSize = 20  // 키워드당 API 요청 개수 (최대 20개까지 요금 동일하므로 최대로 설정)
    private val weightScoreMultiplier = 100.0
    private val likeScoreMultiplier = 50.0  // 좋아요 비중 증가 (15.0 → 50.0)
 
    /**
     * 모임의 설문 결과를 분석하여 자동으로 최적의 식당을 도출하고 저장한다.
     * (Redis Stream Consumer 등 백그라운드 작업에서 주로 호출)
     */
    suspend fun execute(meetingId: Long): PlacesSearchResponse {
        val plan = createSurveyKeywordService.generateKeywordPlan(meetingId)
        
        val searchRequest = PlacesSearchRequest(
            meetingId = meetingId,
            userId = null
        )
        
        val searchPlan = PlaceSearchPlan.Automatic(
            keywords = plan.keywords,
            stationCoordinates = plan.stationCoordinates,
            fallbackKeyword = plan.fallbackKeyword
        )
        
        return search(searchRequest, searchPlan)
    }

    suspend fun search(request: PlacesSearchRequest, plan: PlaceSearchPlan): PlacesSearchResponse = withContext(MDCContext()) {
        supervisorScope {
        // DB 저장된 결과 확인 (Automatic 검색 + meetingId 있을 때만)
        val storedResult = if (plan is PlaceSearchPlan.Automatic && request.meetingId != null) {
            searchService.find(request.meetingId)
        } else null
        
        // 저장된 결과가 있으면 좋아요 정보만 업데이트해서 반환
        if (storedResult != null && request.meetingId != null) {
            logger.debug("저장된 검색 결과 사용 - meetingId=${request.meetingId}, places=${storedResult.items.size}개")
            val updatedItems = updateLikesForStoredItems(storedResult.items, request.meetingId, request.userId)
            return@supervisorScope PlacesSearchResponse(updatedItems)
        }
        
        // 저장된 결과 없음 -> 새로 검색
        val automaticPlan = plan as? PlaceSearchPlan.Automatic
            ?: throw IllegalArgumentException("PlaceSearchPlan.Automatic만 지원합니다.")

        val keywordResult = fetchPlacesForKeywords(automaticPlan, photoFallbackBuffer)

        if (keywordResult.places.isEmpty()) {
            logger.info("장소 검색 결과 없음 - keywords={}, meetingId={}", keywordResult.usedKeywords, request.meetingId)
            return@supervisorScope PlacesSearchResponse(emptyList())
        }

        // 가중치 기반으로 정렬 후 상위 (결과 + 여분) 개수만큼 선택
        val candidatePlaces = (keywordResult.places + keywordResult.fallbackPlaces)
            .distinctBy { it.id }
        val placesToProcess = candidatePlaces
            .sortedByDescending { keywordResult.placeWeights[it.id] ?: 0.0 }
            .take(totalFetchSize + photoFallbackBuffer)
        
        val savedEntities = withContext(coroutineDispatchers.VT) {
            placeQuery.savePlacesFromTextSearch(placesToProcess)
        }
        
        if (savedEntities.isEmpty()) {
            logger.info("검색된 장소의 DB 저장 결과 없음 - keywords={}, meetingId={}", keywordResult.usedKeywords, request.meetingId)
            return@supervisorScope PlacesSearchResponse(emptyList())
        }

        val meetingPlaces = if (request.meetingId != null) {
            val placeDbIds = savedEntities.mapNotNull { it.id }
            createOrGetMeetingPlaces(request.meetingId, placeDbIds)
        } else {
            emptyList()
        }

        val googlePlaceIds = savedEntities.mapNotNull { it.googlePlaceId }
        val placeIdMap = getPlaceStringIdToDbIdMap(googlePlaceIds)
        val placeWeightByDbId = keywordResult.placeWeights.mapNotNull { (googleId, weight) ->
            placeIdMap[googleId]?.let { it to weight }
        }.toMap()

        val likesMap = if (meetingPlaces.isNotEmpty()) {
            buildLikesMap(googlePlaceIds, meetingPlaces, request.userId)
        } else {
            emptyMap()
        }

        val items = savedEntities.mapNotNull { entity ->
            runCatching {
                val googleId = entity.googlePlaceId ?: return@mapNotNull null
                val placeDbId = entity.id ?: return@mapNotNull null
                val likeInfo = likesMap[googleId] ?: PlaceLikeInfo(0, false)

                PlacesSearchResponse.PlaceItem(
                    placeId = placeDbId,
                    name = entity.name,
                    address = entity.address.replace("대한민국 ", ""),
                    rating = entity.rating,
                    userRatingsTotal = entity.userRatingsTotal,
                    openNow = entity.openNow,
                    photos = entity.photos?.split(",")?.map { photoName ->
                        // PlaceFormatter를 사용하여 프록시 URL 생성
                        org.depromeet.team3.place.util.PlaceFormatter.generatePhotoUrl(
                            photoName, 
                            googlePlacesApiProperties.proxyBaseUrl
                        )
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
                    priceRange = null, // Text Search에는 가격대 없음
                    addressDescriptor = entity.addressDescriptor?.let { desc ->
                        PlacesSearchResponse.PlaceItem.AddressDescriptor(description = desc)
                    },
                    likeCount = likeInfo.likeCount,
                    isLiked = likeInfo.isLiked
                )
            }.onFailure { e ->
                logger.warn("장소 응답 변환 실패: googleId=${entity.googlePlaceId}, error=${e.message}")
            }.getOrNull()
        }

        // 정렬 우선순위:
        // 자동 검색(가중치 있음): (설문가중치 * 100) + (ln(좋아요+1) * 50) 점수 → 좋아요 순
        // 사용자가 좋아요 누른 항목은 추가 부스트 적용
        val sortedItems = when {
            placeWeightByDbId.isNotEmpty() -> {
                val scoreByPlaceId = items.associate { item ->
                    val weight = placeWeightByDbId[item.placeId] ?: 0.0
                    val likeScore = if (item.likeCount > 0) ln(item.likeCount.toDouble() + 1) * likeScoreMultiplier else 0.0
                    val userLikedBoost = if (item.isLiked) 100.0 else 0.0  // 사용자가 좋아요 누른 항목 추가 부스트
                    val combinedScore = weight * weightScoreMultiplier + likeScore + userLikedBoost
                    item.placeId to combinedScore
                }

                items.sortedWith(
                    compareByDescending<PlacesSearchResponse.PlaceItem> { hasPhoto(it) }
                        .thenByDescending { scoreByPlaceId[it.placeId] ?: 0.0 }
                        .thenByDescending { it.likeCount }
                )
            }

            else -> items.sortedWith(
                compareByDescending<PlacesSearchResponse.PlaceItem> { hasPhoto(it) }
            )
        }

        val finalItems = sortedItems.take(totalFetchSize)
        val response = PlacesSearchResponse(finalItems)
        
        // DB에 검색 결과 저장 (meetingId가 있을 때만)
        if (request.meetingId != null && finalItems.isNotEmpty()) {
            searchService.save(request.meetingId, response)
        }

        response
        }
    }

    private fun hasPhoto(item: PlacesSearchResponse.PlaceItem): Boolean =
        item.photos?.isNullOrEmpty() == false
    
    /**
     * 설문 기반 키워드 목록으로 병렬 검색을 수행하고 결과를 가중치 비례로 병합한다.
     *
     * 처리 흐름:
     * 1. 각 키워드마다 독립적인 코루틴으로 Google Places API 병렬 호출
     * 2. 총 10개를 가중치 비례로 키워드별 할당량 계산
     * 3. 각 키워드에서 할당량만큼 평점 높은 장소 선택
     * 4. 최종 결과를 가중치 순으로 정렬 (같은 가중치면 평점 순)
     *
     * 예시:
     * - 한식 40%, 양식 30%, 일식 20%, 중식 10%
     * - → 한식 4개, 양식 3개, 일식 2개, 중식 1개
     *
     * 정렬 우선순위:
     * 1순위: 가중치 (설문 득표율) - 높을수록 먼저
     * 2순위: 좋아요 개수 (좋아요 정보는 나중에 DB에서 조회하여 적용)
     *
     * @param plan 설문 기반으로 생성된 키워드 목록과 역 좌표
     * @return 병합된 장소 목록, 장소별 가중치, 사용된 키워드 목록
     */
    private suspend fun fetchPlacesForKeywords(
        plan: PlaceSearchPlan.Automatic,
        fallbackLimit: Int
    ): KeywordSearchResult = coroutineScope {
        // 현재 코루틴 컨텍스트 캡처 (MDC 등 전파를 위해)
        val parentContext = currentCoroutineContext()
        
        // 1단계: 각 키워드로 병렬 API 호출
        val deferredResponses = plan.keywords.map { candidate ->
            async(parentContext) {
                ensureActive()
                runCatching {
                    candidate to fetchPlacesFromGoogle(candidate.keyword, plan.stationCoordinates)
                }.onFailure { e ->
                    logger.warn("Google Places API 호출 실패(자동 키워드): keyword={}, message={}", candidate.keyword, e.message)
                }.getOrNull()
            }
        }

        val results = deferredResponses.awaitAll().filterNotNull()

        // 2단계: 가중치 비례로 각 키워드별 할당량 계산
        val allocations = calculateKeywordAllocations(
            results.map { it.first.weight },
            totalFetchSize
        )

        // 3단계: 각 키워드에서 할당량만큼 선택
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
            if (allocation == 0) return@forEachIndexed
            
            val rawPlaces = response.places ?: emptyList()
            val candidatePlaces = filterPlacesByKeyword(rawPlaces, candidate)
                .sortedByDescending { it.rating ?: 0.0 }
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

            if (addedCount < allocation && selectedPlaces.size < totalFetchSize) {
                val fallbackKeyword = candidate.fallbackKeyword
                if (!fallbackKeyword.isNullOrBlank()) {
                    val fallbackResponse = fetchPlacesFromGoogle(fallbackKeyword, plan.stationCoordinates)
                    val fallbackRawPlaces = fallbackResponse.places ?: emptyList()
                    val fallbackPlaces = filterPlacesByKeyword(fallbackRawPlaces, candidate, candidate.fallbackMatchKeywords)
                        .sortedByDescending { it.rating ?: 0.0 }

                    fallbackResponses.add(candidate to fallbackPlaces)

                    var fallbackUsed = false

                    fallbackPlaces.forEach { place ->
                        if (usedPlaceIds.contains(place.id)) return@forEach

                        if (addedCount < allocation && selectedPlaces.size < totalFetchSize) {
                            selectedPlaces.add(place)
                            placeWeights[place.id] = candidate.weight
                            usedPlaceIds.add(place.id)
                            addedCount++
                            fallbackUsed = true
                        } else if (fallbackCandidates.size < fallbackLimit && fallbackIds.add(place.id)) {
                            placeWeights.putIfAbsent(place.id, candidate.weight)
                            fallbackCandidates.add(place)
                            fallbackUsed = true
                        }
                    }

                    if (fallbackUsed) {
                        appliedKeywords.add(fallbackKeyword)
                    }
                }
            }
        }

        // 4단계: 할당량을 못 채운 경우, 가중치 높은 키워드부터 추가 선택
        if (selectedPlaces.size < totalFetchSize) {
            val sortedResults = results.sortedByDescending { it.first.weight }
            
            sortedResults.forEach { (candidate, response) ->
                if (selectedPlaces.size >= totalFetchSize) return@forEach
                
                val places = filterPlacesByKeyword(response.places ?: emptyList(), candidate)
                places
                    .filter { !usedPlaceIds.contains(it.id) }
                    .sortedByDescending { it.rating ?: 0.0 }
                    .take(totalFetchSize - selectedPlaces.size)
                    .forEach { place ->
                        selectedPlaces.add(place)
                        placeWeights[place.id] = candidate.weight
                        usedPlaceIds.add(place.id)
                    }
            }
        }

        // fallback 후보군 보충 (가중치 순 → 평점 순, 기존 선택 제외)
        if (fallbackCandidates.size < fallbackLimit) {
            val candidateSources = buildList {
                addAll(results.map { (candidate, resp) ->
                    candidate to filterPlacesByKeyword(resp.places ?: emptyList(), candidate)
                        .sortedByDescending { it.rating ?: 0.0 }
                })
                addAll(fallbackResponses)
            }

            candidateSources.forEach { (candidate, places) ->
                places
                    .filter { place -> !usedPlaceIds.contains(place.id) && fallbackIds.add(place.id) }
                    .sortedByDescending { it.rating ?: 0.0 }
                    .forEach { place ->
                        if (fallbackCandidates.size < fallbackLimit) {
                            fallbackCandidates.add(place)
                            placeWeights.putIfAbsent(place.id, candidate.weight)
                        }
                    }

                if (fallbackCandidates.size >= fallbackLimit) return@forEach
            }
        }

        // 이미 선택된 ID는 fallback 후보에서 제거
        val uniqueFallbackCandidates = fallbackCandidates
            .filterNot { usedPlaceIds.contains(it.id) }

        // 5단계: 최종 정렬 (가중치 순 → 평점 순)
        // 좋아요 정렬은 나중에 search() 메서드에서 DB 데이터와 함께 처리됨
        val sortedPlaces = selectedPlaces
            .sortedWith(
                compareByDescending<PlacesTextSearchResponse.Place> { placeWeights[it.id] ?: 0.0 }
                    .thenByDescending { it.rating ?: 0.0 }
            )

        if (sortedPlaces.isNotEmpty()) {
            val primaryPlaces = sortedPlaces.take(totalFetchSize)
            val primaryIds = primaryPlaces.map { it.id }.toSet()

            return@coroutineScope KeywordSearchResult(
                places = primaryPlaces,
                fallbackPlaces = uniqueFallbackCandidates
                    .filter { it.id !in primaryIds }
                    .sortedWith(
                        compareByDescending<PlacesTextSearchResponse.Place> { placeWeights[it.id] ?: 0.0 }
                            .thenByDescending { it.rating ?: 0.0 }
                    )
                    .take(fallbackLimit),
                placeWeights = placeWeights,
                usedKeywords = appliedKeywords.toList()
            )
        }

        logger.warn("설문 기반 키워드로 결과 없음 - fallback keyword 사용: {}", plan.fallbackKeyword)

        val fallbackResponse = fetchPlacesFromGoogle(plan.fallbackKeyword, plan.stationCoordinates)
        val fallbackPlaces = (fallbackResponse.places ?: emptyList()).take(totalFetchSize)
        val fallbackWeights = fallbackPlaces.associate { it.id to 0.1 }

        KeywordSearchResult(
            places = fallbackPlaces,
            fallbackPlaces = emptyList(),
            placeWeights = fallbackWeights,
            usedKeywords = listOf(plan.fallbackKeyword)
        )
    }

    private fun filterPlacesByKeyword(
        places: List<PlacesTextSearchResponse.Place>,
        candidate: CreateSurveyKeywordService.KeywordCandidate,
        overrideKeywords: Set<String>? = null
    ): List<PlacesTextSearchResponse.Place> {
        val keywords = overrideKeywords ?: candidate.matchKeywords
        if (keywords.isEmpty()) {
            return emptyList()
        }

        val filtered = places.filter { place ->
            val normalizedName = place.displayName.text.lowercase().replace(" ", "")
            val types = place.types?.map { it.lowercase() } ?: emptyList()

            keywords.any { keyword ->
                normalizedName.contains(keyword) ||
                    place.displayName.text.lowercase().contains(keyword) ||
                    types.any { it.contains(keyword) }
            }
        }

        return if (filtered.isNotEmpty()) filtered else emptyList()
    }

    /**
     * 가중치 비례로 각 키워드별 할당량 계산
     * 
     * @param weights 각 키워드의 가중치 리스트
     * @param totalSlots 총 할당할 개수
     * @return 각 키워드별 할당량 리스트
     */
    private fun calculateKeywordAllocations(weights: List<Double>, totalSlots: Int): List<Int> {
        if (weights.isEmpty()) return emptyList()
        
        val totalWeight = weights.sum()
        if (totalWeight == 0.0) {
            // 모든 가중치가 0이면 균등 분배
            val equalSlots = totalSlots / weights.size
            return List(weights.size) { equalSlots }
        }
        
        // 가중치 비례로 계산
        val allocations = weights.map { weight ->
            ((weight / totalWeight) * totalSlots).toInt()
        }.toMutableList()
        
        // 반올림 오차로 인한 부족분 처리
        var allocated = allocations.sum()
        if (allocated < totalSlots) {
            // 가중치 높은 순서대로 1개씩 추가
            val sortedIndices = weights.indices.sortedByDescending { weights[it] }
            for (i in 0 until (totalSlots - allocated)) {
                allocations[sortedIndices[i % sortedIndices.size]]++
            }
        }
        
        logger.debug(
            "키워드 할당량 계산 - 가중치: {}, 할당: {}, 총: {}개",
            weights.map { "%.2f".format(it) },
            allocations,
            allocations.sum()
        )
        
        return allocations
    }

    /**
     * Google Places Text Search API를 호출하여 장소 목록을 조회한다.
     *
     * @param query 검색 키워드 (호출 시점에서 정규화되어 있지만, 방어적으로 한 번 더 정규화)
     * @param stationCoordinates 역 좌표 (있으면 반경 3km 내 우선 검색)
     * @return Google Places API 응답 (최대 15개 장소)
     */
    private suspend fun fetchPlacesFromGoogle(
        query: String,
        stationCoordinates: MeetingQuery.StationCoordinates?
    ): PlacesTextSearchResponse {
        val sanitizedQuery = CreateSurveyKeywordService.normalizeKeyword(query)
        if (sanitizedQuery.isBlank()) {
            throw PlaceSearchException(
                ErrorCode.PLACE_INVALID_QUERY,
                detail = mapOf("query" to query)
            )
        }

        return try {
            withContext(coroutineDispatchers.VT) {
                placeQuery.textSearch(
                    query = sanitizedQuery,
                    maxResults = keywordFetchSize,
                    latitude = stationCoordinates?.latitude,
                    longitude = stationCoordinates?.longitude,
                    radius = 3000.0  // 역 중심 반경 3km 내 우선 검색
                )
            }
        } catch (e: PlaceSearchException) {
            throw e
        } catch (e: Exception) {
            logger.error("Google Places API 호출 실패: query=$sanitizedQuery", e)
            throw PlaceSearchException(
                ErrorCode.PLACE_SEARCH_FAILED,
                    detail = mapOf("query" to sanitizedQuery, "error" to e.message)
            )
        }
    }

    private suspend fun getPlaceStringIdToDbIdMap(googlePlaceIds: List<String>): Map<String, Long> {
        return withContext(coroutineDispatchers.VT) {
            placeQuery.findByGooglePlaceIds(googlePlaceIds)
                .mapNotNull { place ->
                    val dbId = place.id ?: return@mapNotNull null
                    val gId = place.googlePlaceId ?: return@mapNotNull null
                    gId to dbId
                }
                .toMap()
        }
    }

    private suspend fun createOrGetMeetingPlaces(meetingId: Long, placeDbIds: List<Long>): List<MeetingPlace> = withContext(coroutineDispatchers.VT) {
        val existingMeetingPlaces = meetingPlaceRepository.findByMeetingId(meetingId)
        val existingPlaceIds = existingMeetingPlaces.map { it.placeId }.toSet()

        val newMeetingPlaces = placeDbIds
            .filter { it !in existingPlaceIds }
            .map { placeDbId ->
                MeetingPlace(
                    meetingId = meetingId,
                    placeId = placeDbId
                )
            }

        if (newMeetingPlaces.isNotEmpty()) {
            val saved = meetingPlaceRepository.saveAll(newMeetingPlaces)
            existingMeetingPlaces + saved
        } else {
            existingMeetingPlaces
        }
    }

    private suspend fun buildLikesMap(
        googlePlaceIds: List<String>,
        meetingPlaces: List<MeetingPlace>,
        userId: Long?
    ): Map<String, PlaceLikeInfo> {
        val placeStringIdToDbId = getPlaceStringIdToDbIdMap(googlePlaceIds)
        val meetingPlaceIds = meetingPlaces.mapNotNull { it.id }

        if (meetingPlaceIds.isEmpty()) {
            return emptyMap()
        }

        val placeLikes = placeLikeRepository.findByMeetingPlaceIds(meetingPlaceIds)

        val meetingPlaceIdToPlaceDbId = meetingPlaces
            .filter { it.id != null }
            .associate { it.id!! to it.placeId }

        val likesByPlaceDbId = placeLikes
            .groupBy { meetingPlaceIdToPlaceDbId[it.meetingPlaceId] }

        return googlePlaceIds.associateWith { googlePlaceId ->
            val placeDbId = placeStringIdToDbId[googlePlaceId]
            val likes = if (placeDbId != null) likesByPlaceDbId[placeDbId] ?: emptyList() else emptyList()

            PlaceLikeInfo(
                likeCount = likes.size,
                isLiked = userId != null && likes.any { it.userId == userId }
            )
        }
    }

    /**
     * 저장된 검색 결과의 좋아요 정보만 업데이트
     */
    private suspend fun updateLikesForStoredItems(
        storedItems: List<PlacesSearchResponse.PlaceItem>,
        meetingId: Long,
        userId: Long?
    ): List<PlacesSearchResponse.PlaceItem> = withContext(coroutineDispatchers.VT) {
        if (storedItems.isEmpty()) return@withContext emptyList()
        
        // PlaceItem의 placeId는 DB ID이므로 직접 사용
        val placeDbIds = storedItems.map { it.placeId }
        val meetingPlaces = createOrGetMeetingPlaces(meetingId, placeDbIds)
        
        if (meetingPlaces.isEmpty()) {
            return@withContext storedItems
        }
        
        // MeetingPlace ID -> Place DB ID 매핑
        val meetingPlaceIdToPlaceDbId = meetingPlaces
            .filter { it.id != null }
            .associate { it.id!! to it.placeId }
        
        // 좋아요 정보 조회
        val placeLikes = placeLikeRepository.findByMeetingPlaceIds(meetingPlaceIdToPlaceDbId.keys.toList())
        val likesByPlaceDbId = placeLikes
            .groupBy { meetingPlaceIdToPlaceDbId[it.meetingPlaceId] }
        
        // 각 아이템의 좋아요 정보 업데이트
        storedItems.map { item ->
            val likes = likesByPlaceDbId[item.placeId] ?: emptyList()
            item.copy(
                likeCount = likes.size,
                isLiked = userId != null && likes.any { it.userId == userId }
            )
        }
    }

    private data class KeywordSearchResult(
        val places: List<PlacesTextSearchResponse.Place>,
        val fallbackPlaces: List<PlacesTextSearchResponse.Place>,
        val placeWeights: Map<String, Double>,
        val usedKeywords: List<String>
    )

    private data class PlaceLikeInfo(
        val likeCount: Int,
        val isLiked: Boolean
    )
}