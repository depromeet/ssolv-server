package org.depromeet.team3.place.client
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.depromeet.team3.common.GooglePlacesApiProperties
import org.depromeet.team3.place.model.PlacesTextSearchRequest
import org.depromeet.team3.place.model.PlacesTextSearchResponse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.springframework.web.client.RestClient
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.mockito.Mockito.lenient
import org.mockito.quality.Strictness
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.junit.jupiter.MockitoExtension
import org.junit.jupiter.api.extension.ExtendWith

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GooglePlacesClientTest {

    private lateinit var restClient: RestClient
    private lateinit var googlePlacesApiProperties: GooglePlacesApiProperties
    private lateinit var googlePlacesClient: GooglePlacesClient

    @BeforeEach
    fun setUp() {
        restClient = mock()
        
        googlePlacesApiProperties = GooglePlacesApiProperties(
            apiKey = "test-api-key",
            baseUrl = "https://places.googleapis.com"
        )
        
        googlePlacesClient = GooglePlacesClient(
            googlePlacesRestClient = restClient,
            googlePlacesApiProperties = googlePlacesApiProperties,
            coroutineDispatchers = coroutineDispatchers
        )
    }

    @Test
    fun `텍스트 검색 성공`() {
        runTest {
            lenient().whenever(Dispatchers.IO).thenReturn(UnconfinedTestDispatcher(testScheduler))
            val query = "강남역 맛집"
            val mockResponse = PlacesTextSearchResponse(
                places = listOf(
                    PlacesTextSearchResponse.Place(
                        id = "place_1",
                        displayName = PlacesTextSearchResponse.Place.DisplayName("맛집 1"),
                        formattedAddress = "서울시 강남구",
                        rating = 4.5,
                        userRatingCount = 100,
                        location = PlacesTextSearchResponse.Place.Location(37.5, 127.0)
                    )
                )
            )

            val requestBodyUriSpec = mock<RestClient.RequestBodyUriSpec>()
            val requestBodySpec = mock<RestClient.RequestBodySpec>()
            val responseSpec = mock<RestClient.ResponseSpec>()

            whenever(restClient.post()).thenReturn(requestBodyUriSpec)
            whenever(requestBodyUriSpec.uri(any<String>())).thenReturn(requestBodySpec)
            whenever(requestBodySpec.header(any<String>(), any<String>())).thenReturn(requestBodySpec)
            doReturn(requestBodySpec).whenever(requestBodySpec).body(any<PlacesTextSearchRequest>())
            whenever(requestBodySpec.retrieve()).thenReturn(responseSpec)
            whenever(responseSpec.body(PlacesTextSearchResponse::class.java)).thenReturn(mockResponse)

            val result = googlePlacesClient.textSearch(query)

            assertThat(result).isNotNull
            assertThat(result.places).hasSize(1)
            assertThat(result.places!![0].displayName.text).isEqualTo("맛집 1")
        }
    }
}
