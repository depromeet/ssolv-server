package org.depromeet.team3.place.application.search.components

import kotlinx.coroutines.*
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.meeting.MeetingQuery
import org.depromeet.team3.place.PlaceQuery
import org.depromeet.team3.place.application.search.model.PlaceSearchPlan
import org.depromeet.team3.place.application.search.support.CreateSurveyKeywordService
import org.depromeet.team3.place.exception.PlaceSearchException
import org.depromeet.team3.place.model.PlacesTextSearchResponse
import org.springframework.stereotype.Service

/**
 * Google Places API(Text Search)를 호출하여 검색 키워드에 맞는 장소들을 수집하는 서비스
 */
@Service
class SearchGooglePlaceService(
    private val placeQuery: PlaceQuery
) {
    private val keywordFetchSize = 20

    /**
     * 설문 기반 키워드 목록을 병렬로 검색하고 결과를 취합한다.
     */
    suspend fun findPlacesByKeywords(
        plan: PlaceSearchPlan.Automatic,
        totalFetchSize: Int,
        fallbackLimit: Int
    ): KeywordSearchResult = coroutineScope {
        val parentContext = currentCoroutineContext()
        
        // 1. 키워드별 병렬 검색 수행
        val deferredResponses = plan.keywords.map { candidate ->
            async(parentContext) {
                ensureActive()
                runCatching {
                    candidate to fetchFromGoogle(candidate.keyword, plan.stationCoordinates)
                }.getOrNull()
            }
        }

        val results = deferredResponses.awaitAll().filterNotNull()

        // 2. 가중치 비례 할당량 계산
        val allocations = calculateAllocations(results.map { it.first.weight }, totalFetchSize)

        // 3. 필터링 및 결과 통합
        val selectedPlaces = mutableListOf<PlacesTextSearchResponse.Place>()
        val placeWeights = mutableMapOf<String, Double>()
        val usedPlaceIds = mutableSetOf<String>()
        val fallbackCandidates = mutableListOf<PlacesTextSearchResponse.Place>()
        val fallbackIds = mutableSetOf<String>()
        val appliedKeywords = mutableSetOf<String>()

        results.forEachIndexed { index, (candidate, response) ->
            appliedKeywords.add(candidate.keyword)
            val allocation = allocations[index]
            val filteredPlaces = filterByKeyword(response.places ?: emptyList(), candidate)
                .sortedByDescending { it.rating ?: 0.0 }
            
            var addedCount = 0
            filteredPlaces.forEach { place ->
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
        }

        // 4. 부족분 해결 및 최종 결과 보증
        if (selectedPlaces.isEmpty()) {
            val finalFallback = fetchFromGoogle(plan.fallbackKeyword, plan.stationCoordinates)
            val finalPlaces = (finalFallback.places ?: emptyList()).take(totalFetchSize)
            return@coroutineScope KeywordSearchResult(
                places = finalPlaces,
                fallbackPlaces = emptyList(),
                placeWeights = finalPlaces.associate { it.id to 0.1 },
                usedKeywords = listOf(plan.fallbackKeyword)
            )
        }

        KeywordSearchResult(
            places = selectedPlaces,
            fallbackPlaces = fallbackCandidates.filter { it.id !in usedPlaceIds }.take(fallbackLimit),
            placeWeights = placeWeights,
            usedKeywords = appliedKeywords.toList()
        )
    }

    private suspend fun fetchFromGoogle(query: String, coords: MeetingQuery.StationCoordinates?): PlacesTextSearchResponse = withContext(Dispatchers.IO) {
        val sanitized = CreateSurveyKeywordService.normalizeKeyword(query)
        if (sanitized.isBlank()) throw PlaceSearchException(ErrorCode.PLACE_INVALID_QUERY)
        
        placeQuery.textSearch(
            query = sanitized,
            maxResults = keywordFetchSize,
            latitude = coords?.latitude,
            longitude = coords?.longitude,
            radius = 3000.0
        )
    }

    private fun filterByKeyword(
        places: List<PlacesTextSearchResponse.Place>,
        candidate: CreateSurveyKeywordService.KeywordCandidate
    ): List<PlacesTextSearchResponse.Place> {
        val keywords = candidate.matchKeywords
        if (keywords.isEmpty()) return emptyList()
        return places.filter { place ->
            val name = place.displayName.text.lowercase().replace(" ", "")
            val types = place.types?.map { it.lowercase() } ?: emptyList()
            keywords.any { k -> name.contains(k) || types.any { t -> t.contains(k) } }
        }
    }

    private fun calculateAllocations(weights: List<Double>, total: Int): List<Int> {
        val sum = weights.sum()
        if (sum == 0.0) return List(weights.size) { if (weights.isNotEmpty()) total / weights.size else 0 }
        val res = weights.map { (it / sum * total).toInt() }.toMutableList()
        var diff = total - res.sum()
        val sortedIdx = weights.indices.sortedByDescending { weights[it] }
        repeat(diff) { res[sortedIdx[it % res.size]]++ }
        return res
    }

    data class KeywordSearchResult(
        val places: List<PlacesTextSearchResponse.Place>,
        val fallbackPlaces: List<PlacesTextSearchResponse.Place>,
        val placeWeights: Map<String, Double>,
        val usedKeywords: List<String>
    )
}
