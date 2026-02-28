package org.depromeet.team3.place.client

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.depromeet.team3.common.GooglePlacesApiProperties
import org.depromeet.team3.place.exception.PlaceSearchException
import org.depromeet.team3.place.model.PlacesTextSearchRequest
import org.depromeet.team3.place.model.PlacesTextSearchResponse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.depromeet.team3.common.util.CoroutineDispatchers
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
class GooglePlacesClientRetryTest {

    private lateinit var restClient: RestClient
    private lateinit var googlePlacesApiProperties: GooglePlacesApiProperties
    private lateinit var coroutineDispatchers: CoroutineDispatchers
    private lateinit var googlePlacesClient: GooglePlacesClient

    @BeforeEach
    fun setUp() {
        restClient = mock()
        coroutineDispatchers = mock()
        
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
    fun `재시도 성공 - 500 에러 후 재시도하여 성공`() = runTest {
        lenient().whenever(coroutineDispatchers.VT).thenReturn(UnconfinedTestDispatcher(testScheduler))
        val query = "맛집"
        val mockResponse = PlacesTextSearchResponse(emptyList())
        
        val requestBodyUriSpec = mock<RestClient.RequestBodyUriSpec>()
        val requestBodySpec = mock<RestClient.RequestBodySpec>()
        val responseSpec = mock<RestClient.ResponseSpec>()
        
        whenever(restClient.post()).thenReturn(requestBodyUriSpec)
        whenever(requestBodyUriSpec.uri(any<String>())).thenReturn(requestBodySpec)
        whenever(requestBodySpec.header(any<String>(), any<String>())).thenReturn(requestBodySpec)
        // body() returns RequestBodySpec
        doReturn(requestBodySpec).whenever(requestBodySpec).body(any<PlacesTextSearchRequest>())
        whenever(requestBodySpec.retrieve()).thenReturn(responseSpec)
        whenever(responseSpec.body(PlacesTextSearchResponse::class.java))
            .thenThrow(createHttpClientErrorException(500))
            .thenReturn(mockResponse)

        val result = googlePlacesClient.textSearch(query)

        assertThat(result).isNotNull
        verify(responseSpec, times(2)).body(PlacesTextSearchResponse::class.java)
    }

    @Test
    fun `재시도 실패 - 최대 재시도 횟수 초과`() = runTest {
        lenient().whenever(coroutineDispatchers.VT).thenReturn(UnconfinedTestDispatcher(testScheduler))
        val query = "맛집"
        val requestBodyUriSpec = mock<RestClient.RequestBodyUriSpec>()
        val requestBodySpec = mock<RestClient.RequestBodySpec>()
        val responseSpec = mock<RestClient.ResponseSpec>()
        
        whenever(restClient.post()).thenReturn(requestBodyUriSpec)
        whenever(requestBodyUriSpec.uri(any<String>())).thenReturn(requestBodySpec)
        whenever(requestBodySpec.header(any<String>(), any<String>())).thenReturn(requestBodySpec)
        doReturn(requestBodySpec).whenever(requestBodySpec).body(any<PlacesTextSearchRequest>())
        whenever(requestBodySpec.retrieve()).thenReturn(responseSpec)
        whenever(responseSpec.body(PlacesTextSearchResponse::class.java))
            .thenThrow(createHttpClientErrorException(500))

        org.junit.jupiter.api.assertThrows<PlaceSearchException> {
            googlePlacesClient.textSearch(query)
        }
        
        verify(responseSpec, times(3)).body(PlacesTextSearchResponse::class.java)
    }

    private fun createHttpClientErrorException(statusCode: Int): HttpClientErrorException {
        return HttpClientErrorException.create(
            org.springframework.http.HttpStatus.valueOf(statusCode),
            "Error",
            org.springframework.http.HttpHeaders.EMPTY,
            ByteArray(0),
            null
        )
    }
}
