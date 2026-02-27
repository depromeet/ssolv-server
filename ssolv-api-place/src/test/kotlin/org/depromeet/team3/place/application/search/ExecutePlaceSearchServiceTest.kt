package org.depromeet.team3.place.application.search

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.depromeet.team3.common.GooglePlacesApiProperties
import org.depromeet.team3.meeting.MeetingQuery
import org.depromeet.team3.place.PlaceQuery
import org.depromeet.team3.place.application.llm.ProcessPlaceLlmService
import org.depromeet.team3.place.application.search.components.ManagePlaceSearchService
import org.depromeet.team3.place.application.search.components.RankPlaceSearchService
import org.depromeet.team3.place.application.search.components.SearchGooglePlaceService
import org.depromeet.team3.place.application.search.model.PlaceSearchPlan
import org.depromeet.team3.place.dto.request.PlacesSearchRequest
import org.depromeet.team3.place.dto.response.PlacesSearchResponse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*

class ExecutePlaceSearchServiceTest {

    private lateinit var meetingQuery: MeetingQuery
    private lateinit var placeQuery: PlaceQuery
    private lateinit var managePlaceSearchService: ManagePlaceSearchService
    private lateinit var searchGooglePlaceService: SearchGooglePlaceService
    private lateinit var rankPlaceSearchService: RankPlaceSearchService
    private lateinit var processPlaceLlmService: ProcessPlaceLlmService
    private lateinit var googlePlacesApiProperties: GooglePlacesApiProperties

    private lateinit var service: ExecutePlaceSearchService

    @BeforeEach
    fun setup() {
        meetingQuery = mock()
        placeQuery = mock()
        managePlaceSearchService = mock()
        searchGooglePlaceService = mock()
        rankPlaceSearchService = mock()
        processPlaceLlmService = mock()
        googlePlacesApiProperties = mock {
            on { proxyBaseUrl } doReturn "https://proxy.url"
        }

        service = ExecutePlaceSearchService(
            meetingQuery = meetingQuery,
            placeQuery = placeQuery,
            managePlaceSearchService = managePlaceSearchService,
            searchGooglePlaceService = searchGooglePlaceService,
            rankPlaceSearchService = rankPlaceSearchService,
            processPlaceLlmService = processPlaceLlmService,
            googlePlacesApiProperties = googlePlacesApiProperties
        )
    }

    @Test
    fun `저장된 결과가 있으면 좋아요 정보만 업데이트해서 반환한다`() = runTest {
        // given
        val plan = PlaceSearchPlan.Automatic(
            keywords = emptyList(),
            stationCoordinates = null,
            fallbackKeyword = "fallback"
        )
        val request = PlacesSearchRequest(meetingId = 1L, userId = 100L)
        val cachedResponse = PlacesSearchResponse(items = emptyList())
        val updatedItems = emptyList<PlacesSearchResponse.PlaceItem>()

        whenever(managePlaceSearchService.find(1L)).thenReturn(cachedResponse)
        whenever(rankPlaceSearchService.updateLikesForStoredItems(any(), eq(1L), eq(100L)))
            .thenReturn(updatedItems)

        // when
        val response = service.search(request, plan)

        // then
        assertThat(response.items).isEmpty()
        verify(managePlaceSearchService).find(1L)
        verify(rankPlaceSearchService).updateLikesForStoredItems(any(), any(), any())
    }

    @Test
    fun `저장된 결과 없을 시 검색 프로세스 전체를 수행한다`() = runTest {
        // given
        val plan = PlaceSearchPlan.Automatic(
            keywords = emptyList(),
            stationCoordinates = null,
            fallbackKeyword = "fallback"
        )
        val request = PlacesSearchRequest(meetingId = 1L, userId = 100L)
        
        whenever(managePlaceSearchService.find(any())).thenReturn(null)
        whenever(searchGooglePlaceService.findPlacesByKeywords(any(), any(), any()))
            .thenReturn(SearchGooglePlaceService.KeywordSearchResult(emptyList(), emptyList(), emptyMap(), emptyList()))
        whenever(placeQuery.savePlacesFromTextSearch(any())).thenReturn(emptyList())

        // when
        val response = service.search(request, plan)

        // then
        assertThat(response.items).isEmpty()
        verify(searchGooglePlaceService).findPlacesByKeywords(any(), any(), any())
    }
}
