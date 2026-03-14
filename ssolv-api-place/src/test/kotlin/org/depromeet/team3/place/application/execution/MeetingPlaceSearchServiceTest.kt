package org.depromeet.team3.place.application.execution

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.depromeet.team3.common.GooglePlacesApiProperties
import org.depromeet.team3.common.util.CoroutineDispatchers
import org.depromeet.team3.place.PlaceEntity
import org.depromeet.team3.place.PlaceQuery
import org.depromeet.team3.place.dto.response.PlacesSearchResponse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito.lenient
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.*
import org.mockito.quality.Strictness
import org.springframework.data.redis.core.ListOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.util.concurrent.TimeUnit

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MeetingPlaceSearchServiceTest {

    private lateinit var redisTemplate: StringRedisTemplate
    private lateinit var objectMapper: ObjectMapper
    private lateinit var placeQuery: PlaceQuery
    private lateinit var googlePlacesApiProperties: GooglePlacesApiProperties
    private lateinit var coroutineDispatchers: CoroutineDispatchers

    private lateinit var listOps: ListOperations<String, String>
    private lateinit var valueOps: ValueOperations<String, String>

    private lateinit var service: MeetingPlaceSearchService

    @BeforeEach
    fun setup() {
        redisTemplate = mock()
        objectMapper = mock()
        placeQuery = mock()
        googlePlacesApiProperties = mock {
            on { apiKey } doReturn "test-api-key"
        }
        coroutineDispatchers = mock()
        lenient().whenever(coroutineDispatchers.VT).thenReturn(UnconfinedTestDispatcher())

        listOps = mock()
        valueOps = mock()

        whenever(redisTemplate.opsForList()).thenReturn(listOps)
        whenever(redisTemplate.opsForValue()).thenReturn(valueOps)

        service = MeetingPlaceSearchService(
            redisTemplate = redisTemplate,
            objectMapper = objectMapper,
            placeQuery = placeQuery,
            googlePlacesApiProperties = googlePlacesApiProperties,
            coroutineDispatchers = coroutineDispatchers
        )
    }

    @Test
    @DisplayName("Cache All Hit - Redis만으로 응답, DB 조회 없음")
    fun `모든 장소 캐시가 살아있을 때 DB 조회가 발생하지 않는다`() = runTest {
        // given
        val meetingId = 1L
        val placeIds = listOf("101", "205")
        
        whenever(listOps.range("meeting:places:$meetingId", 0, -1)).thenReturn(placeIds)
        
        val json1 = """{"placeId":101,"name":"쉑쉑버거","address":"강남대로","likeCount":0,"isLiked":false}"""
        val json2 = """{"placeId":205,"name":"마라탕","address":"강남대로","likeCount":0,"isLiked":false}"""
        
        val item1 = PlacesSearchResponse.PlaceItem(
            placeId = 101, name = "쉑쉑버거", address = "강남대로", rating = 4.5,
            userRatingsTotal = 10, openNow = true, photos = emptyList(), link = "",
            weekdayText = emptyList(), topReview = null, priceRange = null, addressDescriptor = null
        )
        val item2 = PlacesSearchResponse.PlaceItem(
            placeId = 205, name = "마라탕", address = "강남대로", rating = 4.8,
            userRatingsTotal = 20, openNow = true, photos = emptyList(), link = "",
            weekdayText = emptyList(), topReview = null, priceRange = null, addressDescriptor = null
        )

        whenever(valueOps.multiGet(listOf("place:details:101", "place:details:205")))
            .thenReturn(listOf(json1, json2))

        whenever(objectMapper.readValue(json1, PlacesSearchResponse.PlaceItem::class.java)).thenReturn(item1)
        whenever(objectMapper.readValue(json2, PlacesSearchResponse.PlaceItem::class.java)).thenReturn(item2)

        // when
        val result = service.find(meetingId)

        // then
        assertThat(result).isNotNull
        assertThat(result?.items).hasSize(2)
        assertThat(result?.items?.map { it.placeId }).containsExactly(101L, 205L)
        
        // DB 조회가 발생하지 않아야 함
        verify(placeQuery, never()).findByIds(any())
    }

    @Test
    @DisplayName("Partial Cache Miss - 누락된 placeId로만 DB 조회 후 Redis 재적재")
    fun `일부 캐시가 만료되었을 때 누락된 건만 DB에서 복구하고 Redis를 갱신한다`() = runTest {
        // given
        val meetingId = 1L
        val placeIds = listOf("101", "205", "388")
        
        whenever(listOps.range("meeting:places:$meetingId", 0, -1)).thenReturn(placeIds)
        
        val json1 = """{"placeId":101,"name":"쉑쉑버거"}"""
        val json3 = """{"placeId":388,"name":"초밥"}"""
        
        val item1 = PlacesSearchResponse.PlaceItem(placeId = 101L, name = "쉑쉑버거", address = "", rating = null, userRatingsTotal = null, openNow = null, photos = null, link = "", weekdayText = null, topReview = null, priceRange = null, addressDescriptor = null)
        val item3 = PlacesSearchResponse.PlaceItem(placeId = 388L, name = "초밥", address = "", rating = null, userRatingsTotal = null, openNow = null, photos = null, link = "", weekdayText = null, topReview = null, priceRange = null, addressDescriptor = null)

        // 205 (마라탕) 데이터는 만료되어 null 반환
        whenever(valueOps.multiGet(listOf("place:details:101", "place:details:205", "place:details:388")))
            .thenReturn(listOf(json1, null, json3))

        whenever(objectMapper.readValue(json1, PlacesSearchResponse.PlaceItem::class.java)).thenReturn(item1)
        whenever(objectMapper.readValue(json3, PlacesSearchResponse.PlaceItem::class.java)).thenReturn(item3)

        // DB에서 데이터 복구를 위한 모킹
        val missingEntity = PlaceEntity(
            id = 205L,
            googlePlaceId = "g205",
            name = "마라탕",
            address = "강남대로",
            rating = 4.8,
            userRatingsTotal = 20
        )
        whenever(placeQuery.findByIds(listOf(205L))).thenReturn(listOf(missingEntity))
        
        val recoveredJson = """{"placeId":205,"name":"마라탕"}"""
        whenever(objectMapper.writeValueAsString(any())).thenReturn(recoveredJson)

        // when
        val result = service.find(meetingId)

        // then
        assertThat(result).isNotNull
        assertThat(result?.items).hasSize(3)
        // placeholder가 제대로 치환되었는지 확인
        assertThat(result?.items?.map { it.placeId }).containsExactly(101L, 205L, 388L)
        assertThat(result?.items?.find { it.placeId == 205L }?.name).isEqualTo("마라탕")

        // 누락된 1건에 대해서만 DB 조회 확인
        verify(placeQuery, times(1)).findByIds(listOf(205L))
        
        // Redis에 다시 TTL 30일과 함께 적재되었는지 확인
        verify(valueOps, times(1)).set("place:details:205", recoveredJson, 30L, TimeUnit.DAYS)
    }
}
