package org.depromeet.team3.place.application.execution
import kotlinx.coroutines.Dispatchers
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.depromeet.team3.common.GooglePlacesApiProperties
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
import org.springframework.data.redis.core.SetOperations
import org.springframework.data.redis.core.ZSetOperations
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

    private lateinit var zSetOps: ZSetOperations<String, String>
    private lateinit var valueOps: ValueOperations<String, String>
    private lateinit var setOps: SetOperations<String, String>
    private lateinit var meetingQuery: org.depromeet.team3.meeting.MeetingQuery

    private lateinit var service: MeetingPlaceSearchService

    @BeforeEach
    fun setup() {
        redisTemplate = mock()
        objectMapper = mock()
        placeQuery = mock()
        googlePlacesApiProperties = mock {
            on { apiKey } doReturn "test-api-key"
        }
        lenient().whenever(Dispatchers.IO).thenReturn(UnconfinedTestDispatcher())
        meetingQuery = mock()

        zSetOps = mock()
        valueOps = mock()
        setOps = mock()

        whenever(redisTemplate.opsForZSet()).thenReturn(zSetOps)
        whenever(redisTemplate.opsForValue()).thenReturn(valueOps)
        whenever(redisTemplate.opsForSet()).thenReturn(setOps)

        // Redis Pipeline 모킹: executePipelined 결과를 테스트의 given 절에서 설정한 Mock SetOperations 결과로 시뮬레이션
        whenever(redisTemplate.executePipelined(any<org.springframework.data.redis.core.RedisCallback<Any>>())).thenAnswer { invocation ->
            val callback = invocation.arguments[0] as org.springframework.data.redis.core.RedisCallback<*>
            // 테스트 데이터를 직접 반환할 수 없으므로, 테스트 코드에서 given 절을 수정하여 Pipeline 결과를 시뮬레이션하도록 유도하거나
            // 여기서는 실제 로직이 예상하는 리스트 구조를 반환하도록 설정해야 함
            // 기본적으로 빈 리스트를 반환하고, 개별 테스트에서 덮어쓰기
            emptyList<Any>()
        }

        service = MeetingPlaceSearchService(
            redisTemplate = redisTemplate,
            objectMapper = objectMapper,
            placeQuery = placeQuery,
            googlePlacesApiProperties = googlePlacesApiProperties,
            meetingQuery = meetingQuery
        )
    }

    @Test
    @DisplayName("Cache All Hit - Redis ZSET 및 MGET으로 응답, 좋아요 정보 결합 확인")
    fun `ZSET에서 가져온 ID들로 MGET 수행 후 좋아요 정보를 결합하여 반환한다`() = runTest {
        // given
        val meetingId = 1L
        val userId = 99L
        val placeIds = setOf("101", "205")
        
        whenever(zSetOps.reverseRange("meeting:places:$meetingId", 0, 9)).thenReturn(placeIds)
        
        val json1 = """{"placeId":101,"name":"쉑쉑버거"}"""
        val json2 = """{"placeId":205,"name":"마라탕"}"""
        
        val item1 = PlacesSearchResponse.PlaceItem(placeId = 101L, name = "쉑쉑버거", address = "", rating = 4.5, userRatingsTotal = 10, openNow = true, photos = emptyList(), link = "", weekdayText = emptyList(), topReview = null, priceRange = null, addressDescriptor = null)
        val item2 = PlacesSearchResponse.PlaceItem(placeId = 205L, name = "마라탕", address = "", rating = 4.8, userRatingsTotal = 20, openNow = true, photos = emptyList(), link = "", weekdayText = emptyList(), topReview = null, priceRange = null, addressDescriptor = null)

        whenever(valueOps.multiGet(listOf("place:details:101", "place:details:205"))).thenReturn(listOf(json1, json2))
        whenever(objectMapper.readValue(json1, PlacesSearchResponse.PlaceItem::class.java)).thenReturn(item1)
        whenever(objectMapper.readValue(json2, PlacesSearchResponse.PlaceItem::class.java)).thenReturn(item2)

        // 좋아요 정보 모킹 (101: 5개 대기, 205: 유저가 좋아요 누름)
        // Pipeline 결과 시뮬레이션: (SCARD 101, SISMEMBER 101, SCARD 205, SISMEMBER 205)
        whenever(redisTemplate.executePipelined(any<org.springframework.data.redis.core.RedisCallback<Any>>()))
            .thenReturn(listOf(5L, false, 1L, true))

        // when
        val result = service.find(meetingId, userId)

        // then
        assertThat(result).isNotNull
        assertThat(result?.items).hasSize(2)
        
        // 좋아요 정보 반영 확인
        val res101 = result?.items?.find { it.placeId == 101L }
        assertThat(res101?.likeCount).isEqualTo(5)
        assertThat(res101?.isLiked).isFalse()

        val res205 = result?.items?.find { it.placeId == 205L }
        assertThat(res205?.likeCount).isEqualTo(1)
        assertThat(res205?.isLiked).isTrue()
        
        verify(placeQuery, never()).findByIds(any())
    }

    @Test
    @DisplayName("Partial Cache Miss - ZSET 목록 중 누락된 건만 복구")
    fun `ZSET 목록 중 일부 캐시가 만료되었을 때 누락된 상세정보만 DB에서 복구한다`() = runTest {
        // given
        val meetingId = 1L
        val placeIds = setOf("101", "205")
        
        whenever(zSetOps.reverseRange("meeting:places:$meetingId", 0, 9)).thenReturn(placeIds)
        
        val json1 = """{"placeId":101,"name":"쉑쉑버거"}"""
        // 205번 누락 (null)
        whenever(valueOps.multiGet(listOf("place:details:101", "place:details:205"))).thenReturn(listOf(json1, null))
        
        val item1 = PlacesSearchResponse.PlaceItem(placeId = 101L, name = "쉑쉑버거", address = "", rating = null, userRatingsTotal = null, openNow = null, photos = null, link = "", weekdayText = null, topReview = null, priceRange = null, addressDescriptor = null)
        whenever(objectMapper.readValue(json1, PlacesSearchResponse.PlaceItem::class.java)).thenReturn(item1)

        val missingEntity = PlaceEntity(id = 205L, googlePlaceId = "g205", name = "마라탕", address = "강남", rating = 4.8, userRatingsTotal = 20)
        whenever(placeQuery.findByIds(listOf(205L))).thenReturn(listOf(missingEntity))
        whenever(objectMapper.writeValueAsString(any())).thenReturn("""{"placeId":205,"name":"마라탕"}""")

        // 좋아요 정보 모킹 (Pipeline 결과 시뮬레이션: SCARD 101, SCARD 205)
        whenever(redisTemplate.executePipelined(any<org.springframework.data.redis.core.RedisCallback<Any>>()))
            .thenReturn(listOf(0L, 0L))

        // when
        val result = service.find(meetingId)

        // then
        assertThat(result?.items?.map { it.placeId }).containsExactly(101L, 205L)
        verify(placeQuery, times(1)).findByIds(listOf(205L))
        verify(valueOps).set(eq("place:details:205"), any(), any(), any())
    }
}

