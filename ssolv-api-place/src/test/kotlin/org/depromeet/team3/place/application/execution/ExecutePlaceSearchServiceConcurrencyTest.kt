package org.depromeet.team3.place.application.execution

import io.micrometer.core.instrument.MeterRegistry
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.*
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import org.depromeet.team3.common.GooglePlacesApiProperties
import org.depromeet.team3.place.PlaceQuery
import org.depromeet.team3.place.application.model.PlaceSearchPlan
import org.depromeet.team3.place.application.plan.CreateSurveyKeywordService
import org.depromeet.team3.place.dto.request.PlacesSearchRequest
import org.depromeet.team3.place.dto.response.PlacesSearchResponse
import org.depromeet.team3.place.model.PlacesTextSearchResponse
import org.depromeet.team3.common.util.DistributedLockService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MockKExtension::class)
class ExecutePlaceSearchServiceConcurrencyTest {

    @MockK
    lateinit var placeQuery: PlaceQuery
    @MockK
    lateinit var searchService: MeetingPlaceSearchService
    @MockK
    lateinit var createSurveyKeywordService: CreateSurveyKeywordService
    @MockK
    lateinit var googlePlacesApiProperties: GooglePlacesApiProperties
    @MockK
    lateinit var redisTemplate: StringRedisTemplate
    @MockK
    lateinit var meterRegistry: MeterRegistry
    @MockK(relaxed = true)
    lateinit var globalApiSemaphore: Semaphore
    @MockK
    lateinit var lockService: DistributedLockService
    @MockK
    lateinit var objectMapper: ObjectMapper

    @InjectMockKs
    lateinit var executePlaceSearchService: ExecutePlaceSearchService

    @BeforeEach
    fun setUp() {
        // Mocking global objects
        MockKAnnotations.init(this)
        
        // Mock globalApiSemaphore (it's internal or injected)
        // Note: The service uses it in its execute logic.
        
        every { googlePlacesApiProperties.apiKey } returns "test-key"
        every { googlePlacesApiProperties.photoFallbackBuffer } returns 5
        every { googlePlacesApiProperties.totalFetchSize } returns 10
        every { googlePlacesApiProperties.keywordFetchSize } returns 20
        every { googlePlacesApiProperties.semaphoreTimeoutMs } returns 3000L
        every { googlePlacesApiProperties.apiTimeoutMs } returns 3000L
        every { googlePlacesApiProperties.requestSemaphoreSize } returns 5
        every { meterRegistry.counter(any<String>(), any<Iterable<io.micrometer.core.instrument.Tag>>()) } returns mockk(relaxed = true)
        every { meterRegistry.counter(any<String>(), *anyVararg()) } returns mockk(relaxed = true)
        every { meterRegistry.timer(any<String>(), *anyVararg()) } returns mockk(relaxed = true)
        every { redisTemplate.executePipelined(any<org.springframework.data.redis.core.RedisCallback<*>>()) } returns listOf(0L, false)
        
        // Redis ValueOps 및 ObjectMapper 모킹 추가
        val valueOps = mockk<org.springframework.data.redis.core.ValueOperations<String, String>>(relaxed = true)
        every { redisTemplate.opsForValue() } returns valueOps
        every { valueOps.get(any<String>()) } returns null // 초기 상태는 Raw 캐시 없음
        every { objectMapper.writeValueAsString(any()) } returns "{}"
        every { objectMapper.readValue(any<String>(), any<Class<Any>>()) } returns mockk(relaxed = true)
    }

    @Test
    @DisplayName("캐시 스탬피드 방지 테스트: 동일 모임ID로 동시 요청 시 Google API 호출은 한 번만 발생해야 함")
    fun testCacheStampedePrevention() = runBlocking {
        // Given
        val meetingId = 123L
        val request = PlacesSearchRequest(meetingId = meetingId, userId = 1L)
        val plan = PlaceSearchPlan.Automatic(
            keywords = listOf(CreateSurveyKeywordService.KeywordCandidate("맛집", 1.0, CreateSurveyKeywordService.KeywordType.GENERAL, matchKeywords = setOf("restaurant", "식당"))),
            stationCoordinates = null,
            fallbackKeyword = "강남역 맛집"
        )

        // 초기 상태: 캐시 없음
        coEvery { searchService.find(meetingId, any()) } returns null
        coEvery { searchService.find(meetingId, null) } returns null // Internal check
        coEvery { searchService.save(any(), any(), any()) } returns Unit

        // 락 서비스 모킹: 실제 락 시뮬레이션
        // 첫 번째 요청은 성공, 이후 요청은 실패하도록 설정하거나 순차적으로 처리되도록 함
        val lockAcquired = java.util.concurrent.atomic.AtomicBoolean(false)
        coEvery { lockService.withLock<PlacesSearchResponse>(any(), any(), any()) } coAnswers {
            if (lockAcquired.compareAndSet(false, true)) {
                val action = it.invocation.args[2] as suspend () -> PlacesSearchResponse
                val res = action()
                // 시뮬레이션: 완료 후 캐시에 저장됨
                coEvery { searchService.find(meetingId, any()) } returns res
                coEvery { searchService.find(meetingId, null) } returns res
                res
            } else {
                null // 락 획득 실패
            }
        }

        // 구글 API 호출 결과 준비
        val mockPlaces = listOf(
            PlacesTextSearchResponse.Place(
                id = "p1",
                displayName = PlacesTextSearchResponse.Place.DisplayName("식당1"),
                formattedAddress = "주소1",
                location = PlacesTextSearchResponse.Place.Location(37.0, 127.0),
                rating = 4.5,
                userRatingCount = 100,
                types = listOf("restaurant")
            )
        )
        val mockEntities = listOf(
            org.depromeet.team3.place.PlaceEntity(
                id = 1L,
                googlePlaceId = "p1",
                name = "식당1",
                address = "주소1",
                latitude = 37.0,
                longitude = 127.0,
                rating = 4.5,
                userRatingsTotal = 100
            )
        )
        coEvery { placeQuery.textSearch(any(), any(), any(), any(), any()) } returns PlacesTextSearchResponse(mockPlaces)
        coEvery { placeQuery.savePlacesFromTextSearch(any()) } returns mockEntities
        coEvery { placeQuery.findByGooglePlaceIds(any()) } returns mockEntities
        coEvery { createSurveyKeywordService.getStationCoordinates(meetingId) } returns org.depromeet.team3.meeting.MeetingQuery.StationCoordinates(37.0, 127.0)


        // 10명의 사용자가 동시에 요청
        val concurrency = 10
        val results = (1..concurrency).map {
            async(Dispatchers.IO) {
                executePlaceSearchService.search(request, plan)
            }
        }.awaitAll()

        // Then
        // 1. 모든 사용자가 같은 결과를 받아야 함
        assertEquals(concurrency, results.size)
        results.forEach {
            assertEquals(1, it.items.size)
        }

        // 2. Google API (textSearch) 호출은 단 한 번만 발생해야 함 (첫 번째 락 획득자만 수행)
        coVerify(exactly = 1) { placeQuery.textSearch(any(), any(), any(), any(), any()) }
        
        // 3. 락 시도는 최소 1번 이상 발생함
        coVerify(atLeast = 1) { lockService.withLock<PlacesSearchResponse>(any(), any(), any()) }
    }
}