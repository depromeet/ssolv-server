package org.depromeet.team3.place.application.execution

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.depromeet.team3.common.GooglePlacesApiProperties
import org.depromeet.team3.meetingplace.MeetingPlace
import org.depromeet.team3.meetingplace.MeetingPlaceRepository
import org.depromeet.team3.place.PlaceEntity
import org.depromeet.team3.place.PlaceQuery
import org.depromeet.team3.place.application.model.PlaceSearchPlan
import org.depromeet.team3.place.application.plan.CreateSurveyKeywordService
import org.depromeet.team3.place.dto.request.PlacesSearchRequest
import org.depromeet.team3.place.model.PlacesTextSearchResponse
import org.depromeet.team3.placelike.PlaceLikeRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.mockito.Mockito.lenient
import org.mockito.quality.Strictness
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.junit.jupiter.MockitoExtension
import org.junit.jupiter.api.extension.ExtendWith
import org.depromeet.team3.common.util.CoroutineDispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.springframework.transaction.support.TransactionTemplate
import java.util.ArrayDeque

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExecutePlaceSearchServiceTest {

    private lateinit var placeQuery: FakePlaceQuery
    private lateinit var meetingPlaceRepository: MeetingPlaceRepository
    private lateinit var placeLikeRepository: PlaceLikeRepository
    private lateinit var searchService: MeetingPlaceSearchService
    private lateinit var createSurveyKeywordService: CreateSurveyKeywordService
    private lateinit var googlePlacesApiProperties: GooglePlacesApiProperties
    private lateinit var coroutineDispatchers: CoroutineDispatchers
    private lateinit var transactionTemplate: TransactionTemplate

    private lateinit var service: ExecutePlaceSearchService

    @BeforeEach
    fun setup() {
        coroutineDispatchers = mock()
        transactionTemplate = mock()
        whenever(transactionTemplate.execute<Any>(any())).thenAnswer { invocation ->
            val callback = invocation.getArgument<org.springframework.transaction.support.TransactionCallback<Any>>(0)
            callback.doInTransaction(mock())
        }
        
        placeQuery = FakePlaceQuery(transactionTemplate)
        meetingPlaceRepository = mock()
        placeLikeRepository = mock()
        searchService = mock()
        createSurveyKeywordService = mock()
        googlePlacesApiProperties = mock {
            on { proxyBaseUrl } doReturn "https://proxy.url"
        }

        service = ExecutePlaceSearchService(
            placeQuery = placeQuery,
            meetingPlaceRepository = meetingPlaceRepository,
            placeLikeRepository = placeLikeRepository,
            searchService = searchService,
            createSurveyKeywordService = createSurveyKeywordService,
            googlePlacesApiProperties = googlePlacesApiProperties,
            coroutineDispatchers = coroutineDispatchers
        )

        searchService.stub {
            onBlocking { find(any()) }.doReturn(null)
        }

        meetingPlaceRepository.stub {
            onBlocking { findByMeetingId(any()) }.doReturn(emptyList())
            onBlocking { saveAll(any<List<MeetingPlace>>()) }.doAnswer { invocation ->
                invocation.getArgument<List<MeetingPlace>>(0)
            }
        }

        placeLikeRepository.stub {
            onBlocking { findByMeetingPlaceIds(any()) }.doReturn(emptyList())
        }
    }

    @Test
    fun `저장된 결과가 있으면 좋아요 정보만 업데이트해서 반환한다`() {
        runTest {
            lenient().whenever(coroutineDispatchers.VT).thenReturn(UnconfinedTestDispatcher(testScheduler))
            val plan = PlaceSearchPlan.Automatic(
                keywords = emptyList(),
                stationCoordinates = null,
                fallbackKeyword = "fallback"
            )

            val storedItem1 = org.depromeet.team3.place.dto.response.PlacesSearchResponse.PlaceItem(
                placeId = 1L,
                name = "사진 없음",
                address = "주소",
                rating = 4.5,
                userRatingsTotal = 10,
                openNow = true,
                photos = emptyList(),
                link = "link",
                weekdayText = emptyList(),
                topReview = null,
                priceRange = null,
                addressDescriptor = null,
                likeCount = 0,
                isLiked = false
            )
            val storedItem2 = org.depromeet.team3.place.dto.response.PlacesSearchResponse.PlaceItem(
                placeId = 2L,
                name = "사진 있음",
                address = "주소",
                rating = 4.0,
                userRatingsTotal = 8,
                openNow = true,
                photos = listOf("image"),
                link = "link",
                weekdayText = emptyList(),
                topReview = null,
                priceRange = null,
                addressDescriptor = null,
                likeCount = 0,
                isLiked = false
            )

            searchService.stub {
                onBlocking { find(1L) }.doReturn(
                    org.depromeet.team3.place.dto.response.PlacesSearchResponse(
                        items = listOf(storedItem1, storedItem2)
                    )
                )
            }

            val request = PlacesSearchRequest(meetingId = 1L, userId = null)

            val response = service.search(request, plan)

            assertThat(response.items).hasSize(2)
            assertThat(response.items.map { it.name }).containsExactly("사진 없음", "사진 있음")
        }
    }

    @Test
    fun `저장된 결과 없을 시 검색 결과를 DB에 저장하고 반환한다`() {
        runTest {
            lenient().whenever(coroutineDispatchers.VT).thenReturn(UnconfinedTestDispatcher(testScheduler))
            val keywordCandidate = CreateSurveyKeywordService.KeywordCandidate(
                keyword = "키워드 맛집",
                weight = 1.0,
                type = CreateSurveyKeywordService.KeywordType.GENERAL,
                matchKeywords = setOf("키워드", "keyword", "맛집")
            )
            val plan = PlaceSearchPlan.Automatic(
                keywords = listOf(keywordCandidate),
                stationCoordinates = null,
                fallbackKeyword = "fallback 맛집"
            )

            val textSearchPlaces = (0 until 15).map { index ->
                PlacesTextSearchResponse.Place(
                    id = "P$index",
                    displayName = PlacesTextSearchResponse.Place.DisplayName("키워드 맛집 $index"),
                    formattedAddress = "주소 $index",
                    rating = 5.0 - index * 0.1,
                    userRatingCount = 10 - index,
                    location = PlacesTextSearchResponse.Place.Location(37.5 + index, 127.0 + index),
                    photos = listOf(PlacesTextSearchResponse.Place.Photo("photo-$index", 400, 400))
                )
            }

            placeQuery.stubTextSearch("키워드 맛집", PlacesTextSearchResponse(textSearchPlaces))
            placeQuery.stubTextSearch("fallback 맛집", PlacesTextSearchResponse(emptyList()))
            
            // savePlacesFromTextSearch를 모의(mock) 처리하기 위해 FakePlaceQuery의 해당 메서드를 오버라이드
            placeQuery.stubSavePlaces { places ->
                places.mapIndexed { idx, place ->
                    PlaceEntity(
                        id = idx.toLong() + 1,
                        googlePlaceId = place.id,
                        name = place.displayName.text,
                        address = place.formattedAddress,
                        rating = place.rating ?: 0.0,
                        userRatingsTotal = place.userRatingCount ?: 0,
                        photos = place.photos?.joinToString(",") { it.name }
                    )
                }
            }

            val request = PlacesSearchRequest(meetingId = 2L, userId = null)

            val response = service.search(request, plan)

            assertThat(response.items).hasSize(10)
            assertThat(response.items.first().name).contains("키워드 맛집")
            assertThat(response.items.all { it.photos?.isNotEmpty() == true }).isTrue()
        }
    }
}

private open class FakePlaceQuery(
    transactionTemplate: TransactionTemplate
) : PlaceQuery(
    googlePlacesClient = mock(),
    placeJpaRepository = mock(),
    transactionTemplate = transactionTemplate
) {
    private val textSearchResponses = mutableMapOf<String, ArrayDeque<PlacesTextSearchResponse>>()
    private var findByIdsProvider: (List<String>) -> List<PlaceEntity> = { emptyList() }
    private var savePlacesProvider: (List<PlacesTextSearchResponse.Place>) -> List<PlaceEntity> = { emptyList() }

    fun stubTextSearch(query: String, vararg responses: PlacesTextSearchResponse) {
        textSearchResponses[query] = ArrayDeque(responses.asList())
    }

    fun stubFindByGooglePlaceIds(provider: (List<String>) -> List<PlaceEntity>) {
        findByIdsProvider = provider
    }

    fun stubSavePlaces(provider: (List<PlacesTextSearchResponse.Place>) -> List<PlaceEntity>) {
        savePlacesProvider = provider
    }

    override suspend fun textSearch(
        query: String,
        maxResults: Int,
        latitude: Double?,
        longitude: Double?,
        radius: Double
    ): PlacesTextSearchResponse {
        val deque = textSearchResponses[query]
            ?: error("No stubbed response for query=$query")
        if (deque.isEmpty()) {
            error("No remaining stubbed responses for query=$query")
        }
        return deque.removeFirst()
    }

    override suspend fun findByGooglePlaceIds(googlePlaceIds: List<String>): List<PlaceEntity> =
        findByIdsProvider(googlePlaceIds)

    override suspend fun savePlacesFromTextSearch(places: List<PlacesTextSearchResponse.Place>): List<PlaceEntity> =
        savePlacesProvider(places)
}
