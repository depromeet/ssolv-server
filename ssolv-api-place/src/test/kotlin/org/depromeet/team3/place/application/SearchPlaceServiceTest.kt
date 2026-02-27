package org.depromeet.team3.place.application

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.depromeet.team3.place.application.search.SearchPlaceService
import org.depromeet.team3.place.application.search.planner.CreatePlaceSearchPlanService
import org.depromeet.team3.place.application.search.ExecutePlaceSearchService
import org.depromeet.team3.place.application.search.model.PlaceSearchPlan
import org.depromeet.team3.place.dto.request.PlacesSearchRequest
import org.depromeet.team3.place.dto.response.PlacesSearchResponse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*

@ExtendWith(MockitoExtension::class)
class SearchPlaceServiceTest {

    @Mock
    private lateinit var createPlaceSearchPlanService: CreatePlaceSearchPlanService

    @Mock
    private lateinit var executePlaceSearchService: ExecutePlaceSearchService
    
    private lateinit var searchPlaceService: SearchPlaceService

    @BeforeEach
    fun setUp() {
        searchPlaceService = SearchPlaceService(
            createPlaceSearchPlanService = createPlaceSearchPlanService,
            executePlaceSearchService = executePlaceSearchService
        )
    }

    @Test
    fun `자동 검색 시 계획 수립 후 실행 서비스 호출`(): Unit = runBlocking {
        // given
        val request = PlacesSearchRequest(meetingId = 1L)
        val plan = PlaceSearchPlan.Automatic(
            keywords = emptyList(),
            stationCoordinates = null,
            fallbackKeyword = "잠실 맛집"
        )
        val expectedResponse = PlacesSearchResponse(emptyList())

        whenever(createPlaceSearchPlanService.resolve(request))
            .thenReturn(plan)
        whenever(executePlaceSearchService.search(request, plan))
            .thenReturn(expectedResponse)

        // when
        val response = searchPlaceService.textSearch(request)

        // then
        assertThat(response).isEqualTo(expectedResponse)
        verify(createPlaceSearchPlanService).resolve(request)
        verify(executePlaceSearchService).search(request, plan)
    }
}
