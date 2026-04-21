package org.depromeet.team3.place.application

import io.mockk.*
import io.mockk.junit5.MockKExtension
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.depromeet.team3.place.application.execution.ExecutePlaceSearchService
import org.depromeet.team3.place.application.facade.GetPlacesService
import org.depromeet.team3.place.application.model.PlaceSearchPlan
import org.depromeet.team3.place.application.plan.CreatePlaceSearchPlanService
import org.depromeet.team3.place.dto.request.PlacesSearchRequest
import org.depromeet.team3.place.dto.response.PlacesSearchResponse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MockKExtension::class)
class GetPlacesServiceTest {

    private lateinit var createPlaceSearchPlanService: CreatePlaceSearchPlanService
    private lateinit var executePlaceSearchService: ExecutePlaceSearchService
    private lateinit var getPlacesService: GetPlacesService

    @BeforeEach
    fun setUp() {
        createPlaceSearchPlanService = mockk(relaxed = true)
        executePlaceSearchService = mockk(relaxed = true)
        getPlacesService = GetPlacesService(
            createPlaceSearchPlanService = createPlaceSearchPlanService,
            executePlaceSearchService = executePlaceSearchService,
        )
    }

    @Test
    fun `should call execute service after plan is created in automatic search`() = runTest {
        // given
        val request = PlacesSearchRequest(meetingId = 1L)
        val plan = PlaceSearchPlan.Automatic(
            keywords = emptyList(),
            stationCoordinates = null,
            fallbackKeyword = "Jamsil",
        )
        val expectedResponse = PlacesSearchResponse(emptyList())

        coEvery { createPlaceSearchPlanService.resolve(any()) } returns plan
        coEvery { executePlaceSearchService.search(any(), any(), any()) } returns expectedResponse

        // when
        val response = getPlacesService.textSearch(request)

        // then
        assertThat(response).isEqualTo(expectedResponse)

        coVerify {
            createPlaceSearchPlanService.resolve(request)
            executePlaceSearchService.search(eq(request), eq(plan), any())
        }
    }
}
