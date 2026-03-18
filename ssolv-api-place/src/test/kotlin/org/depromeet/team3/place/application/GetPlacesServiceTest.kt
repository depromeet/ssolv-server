package org.depromeet.team3.place.application

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.depromeet.team3.place.application.facade.GetPlacesService
import org.depromeet.team3.place.application.plan.CreatePlaceSearchPlanService
import org.depromeet.team3.place.application.execution.ExecutePlaceSearchService
import org.depromeet.team3.place.application.model.PlaceSearchPlan
import org.depromeet.team3.place.dto.request.PlacesSearchRequest
import org.depromeet.team3.place.dto.response.PlacesSearchResponse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GetPlacesServiceTest {

    private val createPlaceSearchPlanService: CreatePlaceSearchPlanService = mockk()
    private val executePlaceSearchService: ExecutePlaceSearchService = mockk()
    private lateinit var getPlacesService: GetPlacesService

    @BeforeEach
    fun setUp() {
        getPlacesService = GetPlacesService(
            createPlaceSearchPlanService = createPlaceSearchPlanService,
            executePlaceSearchService = executePlaceSearchService
        )
    }

    @Test
    fun `should call execute service after plan is created in automatic search`() = runBlocking {
        // given
        val request = PlacesSearchRequest(meetingId = 1L)
        val plan = PlaceSearchPlan.Automatic(
            keywords = emptyList(),
            stationCoordinates = null,
            fallbackKeyword = "잠실 맛집"
        )
        val expectedResponse = PlacesSearchResponse(emptyList())

        coEvery { createPlaceSearchPlanService.resolve(request) } returns plan
        coEvery { executePlaceSearchService.search(request, plan) } returns expectedResponse

        // when
        val response = getPlacesService.textSearch(request)

        // then
        assertThat(response).isEqualTo(expectedResponse)
        coVerify { createPlaceSearchPlanService.resolve(request) }
        coVerify { executePlaceSearchService.search(request, plan) }
    }
}
