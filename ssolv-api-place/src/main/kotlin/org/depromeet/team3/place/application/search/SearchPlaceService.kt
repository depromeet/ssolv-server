package org.depromeet.team3.place.application.search

import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.depromeet.team3.place.application.search.planner.CreatePlaceSearchPlanService
import org.depromeet.team3.place.dto.request.PlacesSearchRequest
import org.depromeet.team3.place.dto.response.PlacesSearchResponse
import org.springframework.stereotype.Service

/**
 * 검색 요청을 받아 검색 계획 수립과 실행을 오케스트레이션하고
 * PlaceSearchPlanService와 PlaceSearchExecutionService를 순차 호출한다.
 */
@Service
class SearchPlaceService(
    private val createPlaceSearchPlanService: CreatePlaceSearchPlanService,
    private val executePlaceSearchService: ExecutePlaceSearchService
) {
    suspend fun textSearch(request: PlacesSearchRequest): PlacesSearchResponse = withContext(MDCContext()) {
        val plan = createPlaceSearchPlanService.resolve(request)
        executePlaceSearchService.search(request, plan)
    }
}