package org.depromeet.team3.place.application.execution

import kotlinx.coroutines.CoroutineDispatcher
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
        return places.mapNotNull { it.id }.associateWith { placeId ->
            val likeKey = searchService.getLikeKey(meetingId, placeId)
            val likeCount = redisTemplate.opsForSet().size(likeKey) ?: 0L
            val isLiked = if (userId != null) redisTemplate.opsForSet().isMember(likeKey, userId.toString()) == true else false
            PlaceLikeInfo(likeCount.toInt(), isLiked)
        }
    }

    private suspend fun fetchPlacesForKeywords(
        plan: PlaceSearchPlan.Automatic,
        fallbackLimit: Int,
        dispatcher: CoroutineDispatcher
    ): KeywordSearchResult = coroutineScope {
        val parentContext = currentCoroutineContext()
        val deferredResponses = plan.keywords.map { candidate ->
            async(parentContext + dispatcher) {
                ensureActive()
                runCatching {
                    candidate to fetchPlacesFromGoogle(candidate.keyword, plan.stationCoordinates, dispatcher)
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
                val fbResponse = fetchPlacesFromGoogle(candidate.fallbackKeyword!!, plan.stationCoordinates, dispatcher)
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

    private suspend fun fetchPlacesFromGoogle(query: String, stationCoordinates: MeetingQuery.StationCoordinates?, dispatcher: CoroutineDispatcher): PlacesTextSearchResponse {
        val sanitizedQuery = CreateSurveyKeywordService.normalizeKeyword(query)
        if (sanitizedQuery.isBlank()) throw PlaceSearchException(ErrorCode.PLACE_INVALID_QUERY)
        return withContext(dispatcher) {
            placeQuery.textSearch(sanitizedQuery, keywordFetchSize, stationCoordinates?.latitude, stationCoordinates?.longitude, 3000.0)
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
