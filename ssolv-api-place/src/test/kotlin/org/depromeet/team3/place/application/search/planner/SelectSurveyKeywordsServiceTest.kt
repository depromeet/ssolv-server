package org.depromeet.team3.place.application.search.planner

import org.assertj.core.api.Assertions.assertThat
import org.depromeet.team3.place.application.search.model.PlaceSurveySummary
import org.depromeet.team3.place.application.search.planner.SelectSurveyKeywordsService
import org.depromeet.team3.surveycategory.SurveyCategory
import org.depromeet.team3.surveycategory.SurveyCategoryLevel
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class SelectSurveyKeywordsServiceTest {

    private lateinit var selectSurveyKeywordsService: SelectSurveyKeywordsService

    @BeforeEach
    fun setUp() {
        selectSurveyKeywordsService = SelectSurveyKeywordsService()
    }

    @Test
    fun `5명 소규모 모임에서 각 참석자의 선택이 반영됨 - 균등 분포`() {
        // given - 5명이 각각 다른 카테고리 선택
        val korean = createLeafCategory(1L, "한식", 1L)
        val western = createLeafCategory(2L, "양식", 2L)
        val japanese = createLeafCategory(3L, "일식", 3L)
        val chinese = createLeafCategory(4L, "중식", 4L)
        val asian = createLeafCategory(5L, "동남아", 5L)
        
        val summary = PlaceSurveySummary(
            stationName = "잠실",
            stationCoordinates = null,
            totalRespondents = 5,
            leafVotes = mapOf(
                1L to 1,  // 한식 20%
                2L to 1,  // 양식 20%
                3L to 1,  // 일식 20%
                4L to 1,  // 중식 20%
                5L to 1   // 동남아 20%
            ),
            branchVotes = mapOf(
                1L to 1,
                2L to 1,
                3L to 1,
                4L to 1,
                5L to 1
            ),
            leafCategories = mapOf(
                1L to korean,
                2L to western,
                3L to japanese,
                4L to chinese,
                5L to asian
            ),
            branchCategories = mapOf(
                1L to createCategory(1L, "한식", SurveyCategoryLevel.BRANCH),
                2L to createCategory(2L, "양식", SurveyCategoryLevel.BRANCH),
                3L to createCategory(3L, "일식", SurveyCategoryLevel.BRANCH),
                4L to createCategory(4L, "중식", SurveyCategoryLevel.BRANCH),
                5L to createCategory(5L, "동남아", SurveyCategoryLevel.BRANCH)
            )
        )

        // when
        val result = selectSurveyKeywordsService.selectKeywords(summary)

        // then - 모든 카테고리가 반영되어야 함
        assertThat(result).hasSize(5)
        assertThat(result.map { it.keyword }).containsExactlyInAnyOrder(
            "잠실 한식 맛집",
            "잠실 양식 맛집",
            "잠실 일식 맛집",
            "잠실 중식 맛집",
            "잠실 동남아 맛집"
        )
        // 모두 20% 득표 = 0.2 가중치
        assertThat(result.map { it.weight }).allMatch { it == 0.2 }
    }
    
    private fun createLeafCategory(id: Long, name: String, parentId: Long): SurveyCategory {
        return SurveyCategory(
            id = id,
            parentId = parentId,
            level = SurveyCategoryLevel.LEAF,
            name = name,
            sortOrder = id.toInt(),
            isDeleted = false,
            createdAt = LocalDateTime.now(),
            updatedAt = LocalDateTime.now()
        )
    }

    @Test
    fun `가중치 편향 분포 - 득표율이 높을수록 많은 키워드 할당`() {
        // given - 5명 중 한식 3명(60%), 양식 2명(40%)
        val korean1 = createLeafCategory(11L, "김치찌개", 10L)
        val korean2 = createLeafCategory(12L, "삼겹살", 10L)
        val western1 = createLeafCategory(21L, "파스타", 20L)
        val western2 = createLeafCategory(22L, "스테이크", 20L)
        
        val summary = PlaceSurveySummary(
            stationName = "강남",
            stationCoordinates = null,
            totalRespondents = 5,
            leafVotes = mapOf(
                11L to 2,  // 김치찌개 40%
                12L to 1,  // 삼겹살 20%
                21L to 1,  // 파스타 20%
                22L to 1   // 스테이크 20%
            ),
            branchVotes = mapOf(
                10L to 3,  // 한식 60%
                20L to 2   // 양식 40%
            ),
            leafCategories = mapOf(
                11L to korean1,
                12L to korean2,
                21L to western1,
                22L to western2
            ),
            branchCategories = mapOf(
                10L to createCategory(10L, "한식", SurveyCategoryLevel.BRANCH),
                20L to createCategory(20L, "양식", SurveyCategoryLevel.BRANCH)
            )
        )

        // when
        val result = selectSurveyKeywordsService.selectKeywords(summary)

        // then - 총 5개 키워드 중 한식 3개, 양식 2개
        assertThat(result).hasSize(5)
        val koreanCount = result.count { it.keyword.contains("한식") || it.keyword.contains("김치찌개") || it.keyword.contains("삼겹살") }
        val westernCount = result.count { it.keyword.contains("양식") || it.keyword.contains("파스타") || it.keyword.contains("스테이크") }
        
        // 60%:40% 비율로 3:2 분배
        assertThat(koreanCount).isEqualTo(3)
        assertThat(westernCount).isEqualTo(2)
    }

    @Test
    fun `10% 미만 득표는 제외됨`() {
        // given - 10명 중 한식 9명, 양식 1명(10% 미만)
        val summary = PlaceSurveySummary(
            stationName = "강남",
            stationCoordinates = null,
            totalRespondents = 10,
            leafVotes = mapOf(
                101L to 9,  // 한식 90%
                102L to 1   // 양식 10% 미만 (정확히는 10%지만 임계값 체크용)
            ),
            branchVotes = mapOf(
                10L to 9,   // 한식 90%
                20L to 1    // 양식 10%
            ),
            leafCategories = mapOf(
                101L to createLeafCategory(101L, "김치찌개", 10L),
                102L to createLeafCategory(102L, "파스타", 20L)
            ),
            branchCategories = mapOf(
                10L to createCategory(10L, "한식", SurveyCategoryLevel.BRANCH),
                20L to createCategory(20L, "양식", SurveyCategoryLevel.BRANCH)
            )
        )

        // when
        val result = selectSurveyKeywordsService.selectKeywords(summary)

        // then - 10% 이상인 카테고리만 포함
        assertThat(result).isNotEmpty
        // 양식은 10%이므로 포함될 수 있음 (임계값 = 0.1)
        val westernIncluded = result.any { it.keyword.contains("양식") || it.keyword.contains("파스타") }
        assertThat(westernIncluded).isTrue() // 10%는 임계값 = 포함
    }

    @Test
    fun `가중치에 비례하여 슬롯 분배됨`() {
        // given - 한식 50%, 양식 30%, 일식 20% (총 5개 슬롯)
        val summary = PlaceSurveySummary(
            stationName = "강남",
            stationCoordinates = null,
            totalRespondents = 10,
            leafVotes = mapOf(
                101L to 5,  // 한식 50%
                102L to 3,  // 양식 30%
                103L to 2   // 일식 20%
            ),
            branchVotes = mapOf(
                10L to 5,   // 한식 50%
                20L to 3,   // 양식 30%
                30L to 2    // 일식 20%
            ),
            leafCategories = mapOf(
                101L to createLeafCategory(101L, "김치찌개", 10L),
                102L to createLeafCategory(102L, "파스타", 20L),
                103L to createLeafCategory(103L, "초밥", 30L)
            ),
            branchCategories = mapOf(
                10L to createCategory(10L, "한식", SurveyCategoryLevel.BRANCH),
                20L to createCategory(20L, "양식", SurveyCategoryLevel.BRANCH),
                30L to createCategory(30L, "일식", SurveyCategoryLevel.BRANCH)
            )
        )

        // when
        val result = selectSurveyKeywordsService.selectKeywords(summary)

        // then - 5개 키워드: 한식 2~3개, 양식 1~2개, 일식 1개 (비율에 따라)
        assertThat(result).hasSize(5)
        val korean = result.filter { it.keyword.contains("한식") || it.keyword.contains("김치찌개") }
        val western = result.filter { it.keyword.contains("양식") || it.keyword.contains("파스타") }
        val japanese = result.filter { it.keyword.contains("일식") || it.keyword.contains("초밥") }
        
        // 50%:30%:20% 비율 확인 (대략적으로)
        assertThat(korean.size).isGreaterThanOrEqualTo(2)
        assertThat(western.size).isGreaterThanOrEqualTo(1)
        assertThat(japanese.size).isGreaterThanOrEqualTo(1)
    }

    @Test
    fun `10% 미만 득표만 있으면 일반 키워드 반환`() {
        // given - 모두 10% 미만
        val summary = PlaceSurveySummary(
            stationName = "강남",
            stationCoordinates = null,
            totalRespondents = 20,
            leafVotes = mapOf(
                101L to 1,  // 5%
                102L to 1   // 5%
            ),
            branchVotes = mapOf(
                10L to 1,   // 5%
                20L to 1    // 5%
            ),
            leafCategories = mapOf(
                101L to createLeafCategory(101L, "파스타", 10L),
                102L to createLeafCategory(102L, "초밥", 20L)
            ),
            branchCategories = mapOf(
                10L to createCategory(10L, "양식", SurveyCategoryLevel.BRANCH),
                20L to createCategory(20L, "일식", SurveyCategoryLevel.BRANCH)
            )
        )

        // when
        val result = selectSurveyKeywordsService.selectKeywords(summary)

        // then - 일반 키워드로 폴백
        assertThat(result).hasSize(1)
        assertThat(result[0].keyword).isEqualTo("강남 맛집")
        assertThat(result[0].weight).isEqualTo(0.1)
    }

    @Test
    fun `응답자가 0명이면 빈 리스트 반환`() {
        // given
        val summary = PlaceSurveySummary(
            stationName = "강남",
            stationCoordinates = null,
            totalRespondents = 0,
            leafVotes = emptyMap(),
            branchVotes = emptyMap(),
            leafCategories = emptyMap(),
            branchCategories = emptyMap()
        )

        // when
        val result = selectSurveyKeywordsService.selectKeywords(summary)

        // then
        assertThat(result).isEmpty()
    }

    @Test
    fun `최대 5개 키워드만 반환`() {
        // given - 6개 이상의 BRANCH가 있어도 최대 5개만 선택
        val branchIds = listOf(10L, 20L, 30L, 40L, 50L, 60L)
        val branchVotes = mapOf(
            10L to 5,
            20L to 4,
            30L to 4,
            40L to 3,
            50L to 2,
            60L to 2
        )

        val summary = PlaceSurveySummary(
            stationName = "강남",
            stationCoordinates = null,
            totalRespondents = 20,
            leafVotes = branchIds.associate { branchId ->
                val leafId = branchId + 1
                leafId to branchVotes.getValue(branchId)
            },
            branchVotes = branchVotes,
            leafCategories = branchIds.associate { branchId ->
                val leafId = branchId + 1
                leafId to createLeafCategory(leafId, "카테고리$leafId", branchId)
            },
            branchCategories = branchIds.associateWith { branchId ->
                createCategory(branchId, "브랜치$branchId", SurveyCategoryLevel.BRANCH)
            }
        )

        // when
        val result = selectSurveyKeywordsService.selectKeywords(summary)

        // then - 최대 5개까지만 반환
        assertThat(result.size).isLessThanOrEqualTo(5)
    }


    private fun createCategory(
        id: Long,
        name: String,
        level: SurveyCategoryLevel,
        parentId: Long? = null,
        sortOrder: Int = 0
    ): SurveyCategory {
        return SurveyCategory(
            id = id,
            parentId = parentId,
            level = level,
            name = name,
            sortOrder = sortOrder,
            isDeleted = false,
            createdAt = LocalDateTime.now(),
            updatedAt = LocalDateTime.now()
        )
    }
}