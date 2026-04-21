package org.depromeet.team3.place.application.execution
import com.fasterxml.jackson.databind.ObjectMapper
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
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.*
import org.mockito.quality.Strictness
import org.springframework.data.redis.core.SetOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.data.redis.core.ZSetOperations

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

    private lateinit var service: MeetingPlaceSearchService

    @BeforeEach
    fun setup() {
        redisTemplate = mock()
        objectMapper = mock()
        placeQuery = mock()
        googlePlacesApiProperties = mock {
            on { apiKey } doReturn "test-api-key"
        }

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
        )
    }

    @Test
    @DisplayName("Cache All Hit - Redis ZSET 및 MGET으로 응답, 좋아요 정보 결합 및 로그 기반 재정렬 확인")
    fun `ZSET에서 가져온 ID들로 MGET 수행 후 좋아요 정보를 결합하여 로그 기반으로 재정렬하여 반환한다`() = runTest {
        // given
        val meetingId = 1L
        val userId = 99L
        // 101: 베이스 점수 100, 205: 베이스 점수 80
        val tuple1 = mock<ZSetOperations.TypedTuple<String>> {
            on { value } doReturn "101"
            on { score } doReturn 100.0
        }
        val tuple2 = mock<ZSetOperations.TypedTuple<String>> {
            on { value } doReturn "205"
            on { score } doReturn 80.0
        }
        val tuples = setOf(tuple1, tuple2)

        whenever(zSetOps.reverseRangeWithScores("meeting:places:$meetingId", 0, 9)).thenReturn(tuples)

        val json1 = """{"placeId":101,"name":"쉑쉑버거"}"""
        val json2 = """{"placeId":205,"name":"마라탕"}"""

        val item1 = PlacesSearchResponse.PlaceItem(
            placeId = 101L, name = "쉑쉑버거", address = "", rating = 4.5, userRatingsTotal = 10,
            openNow = true, photos = emptyList(), link = "", weekdayText = emptyList(),
            topReview = null, priceRange = null, addressDescriptor = null,
        )
        val item2 = PlacesSearchResponse.PlaceItem(
            placeId = 205L, name = "마라탕", address = "", rating = 4.8, userRatingsTotal = 20,
            openNow = true, photos = emptyList(), link = "", weekdayText = emptyList(),
            topReview = null, priceRange = null, addressDescriptor = null,
        )

        whenever(valueOps.multiGet(listOf("place:details:101", "place:details:205"))).thenReturn(listOf(json1, json2))
        whenever(objectMapper.readValue(json1, PlacesSearchResponse.PlaceItem::class.java)).thenReturn(item1)
        whenever(objectMapper.readValue(json2, PlacesSearchResponse.PlaceItem::class.java)).thenReturn(item2)

        // 좋아요 정보 모킹
        // 101: 0개, 205: 10개 (로그 점수가 101의 베이스 점수 20차이를 극복할 수 있는지 확인)
        // ln(11) * 50 ≈ 119.8점 추가됨 -> 205가 역전해야 함
        whenever(redisTemplate.executePipelined(any<org.springframework.data.redis.core.RedisCallback<Any>>()))
            .thenReturn(listOf(0L, false, 10L, true))

        // when
        val result = service.find(meetingId, userId)

        // then
        assertThat(result).isNotNull
        assertThat(result?.items).hasSize(2)

        // 좋아요가 많은 205번이 상단으로 재정렬되었는지 확인
        assertThat(result?.items?.get(0)?.placeId).isEqualTo(205L)
        assertThat(result?.items?.get(1)?.placeId).isEqualTo(101L)

        verify(placeQuery, never()).findByIds(any())
    }

    @Test
    @DisplayName("Partial Cache Miss - ZSET 목록 중 누락된 건만 복구")
    fun `ZSET 목록 중 일부 캐시가 만료되었을 때 누락된 상세정보만 DB에서 복구한다`() = runTest {
        // given
        val meetingId = 1L
        val tuple1 = mock<ZSetOperations.TypedTuple<String>> {
            on { value } doReturn "101"
            on { score } doReturn 100.0
        }
        val tuple2 = mock<ZSetOperations.TypedTuple<String>> {
            on { value } doReturn "205"
            on { score } doReturn 80.0
        }
        val tuples = setOf(tuple1, tuple2)

        whenever(zSetOps.reverseRangeWithScores("meeting:places:$meetingId", 0, 9)).thenReturn(tuples)

        val json1 = """{"placeId":101,"name":"쉑쉑버거"}"""
        // 205번 누락 (null)
        whenever(valueOps.multiGet(listOf("place:details:101", "place:details:205"))).thenReturn(listOf(json1, null))

        val item1 = PlacesSearchResponse.PlaceItem(
            placeId = 101L, name = "쉑쉑버거", address = "", rating = null, userRatingsTotal = null,
            openNow = null, photos = null, link = "", weekdayText = null, topReview = null,
            priceRange = null, addressDescriptor = null,
        )
        whenever(objectMapper.readValue(json1, PlacesSearchResponse.PlaceItem::class.java)).thenReturn(item1)

        val missingEntity =
            PlaceEntity(id = 205L, googlePlaceId = "g205", name = "마라탕", address = "강남", rating = 4.8, userRatingsTotal = 20)
        whenever(placeQuery.findByIds(listOf(205L))).thenReturn(listOf(missingEntity))
        whenever(objectMapper.writeValueAsString(any())).thenReturn("""{"placeId":205,"name":"마라탕"}""")

        // 좋아요 정보 모킹
        whenever(redisTemplate.executePipelined(any<org.springframework.data.redis.core.RedisCallback<Any>>()))
            .thenReturn(listOf(0L, 0L))

        // when
        val result = service.find(meetingId)

        // then
        assertThat(result?.items?.map { it.placeId }).containsExactlyInAnyOrder(101L, 205L)
        verify(placeQuery, times(1)).findByIds(listOf(205L))
        verify(valueOps).set(eq("place:details:205"), any(), any(), any())
    }
}
