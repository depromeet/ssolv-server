package org.depromeet.team3.place.application.search.planner

import org.assertj.core.api.Assertions.assertThat
import org.depromeet.team3.meeting.MeetingQuery
import org.depromeet.team3.place.application.search.model.PlaceSurveySummary
import org.depromeet.team3.place.application.search.planner.SelectSurveyKeywordsService
import org.depromeet.team3.surveycategory.SurveyCategory
import org.depromeet.team3.surveycategory.SurveyCategoryLevel
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDateTime

/**
 * 장소 검색 통합 테스트
 * - SelectSurveyKeywordsService의 키워드 선정 로직을 실제 Spring Context에서 검증
 */
@SpringBootTest
@ActiveProfiles("test")
class PlaceSearchIntegrationTest {

    @Autowired
    private lateinit var selectSurveyKeywordsService: SelectSurveyKeywordsService

    @Test
    fun `키워드 선정이 득표율에 따라 올바르게 동작함`() {
        // given: 5명 중 4명이 "이탈리안"(80%), 3명이 "파스타"(60%) 선택
        val summary = createMockSurveySummary(
            stationName = "강남역",
            totalRespondents = 5,
            leafVotes = mapOf(
                101L to 4,  // 이탈리안 80%
                102L to 3   // 파스타 60%
            ),
            leafCategories = mapOf(
                101L to createCategory(101L, "이탈리안", SurveyCategoryLevel.LEAF),
                102L to createCategory(102L, "파스타", SurveyCategoryLevel.LEAF)
            )
        )

        // when
        val keywords = selectSurveyKeywordsService.selectKeywords(summary)

        // then
        assertThat(keywords).hasSizeGreaterThanOrEqualTo(2)
        assertThat(keywords[0].keyword).isEqualTo("강남역 이탈리안 맛집")
        assertThat(keywords[0].weight).isEqualTo(0.8)
        assertThat(keywords[1].keyword).isEqualTo("강남역 파스타 맛집")
        assertThat(keywords[1].weight).isEqualTo(0.6)
    }

    @Test
    fun `전원 합의 시 가중치 1_0 키워드가 생성됨`() {
        // given: 5명 모두 "이탈리안" 선택
        val summary = createMockSurveySummary(
            stationName = "강남역",
            totalRespondents = 5,
            leafVotes = mapOf(101L to 5),  // 전원 선택
            leafCategories = mapOf(
                101L to createCategory(101L, "이탈리안", SurveyCategoryLevel.LEAF)
            )
        )

        // when
        val keywords = selectSurveyKeywordsService.selectKeywords(summary)

        // then
        assertThat(keywords).isNotEmpty
        assertThat(keywords[0].keyword).isEqualTo("강남역 이탈리안 맛집")
        assertThat(keywords[0].weight).isEqualTo(1.0)
    }

    @Test
    fun `BRANCH 카테고리는 임계값 이상 득표 시 선택됨`() {
        // given
        val summary = createMockSurveySummary(
            stationName = "강남역",
            totalRespondents = 5,
            leafVotes = emptyMap(),
            branchVotes = mapOf(
                201L to 3,  // 양식 60%
                202L to 2   // 아시안 40% - 제외
            ),
            branchCategories = mapOf(
                201L to createCategory(201L, "양식", SurveyCategoryLevel.BRANCH),
                202L to createCategory(202L, "아시안", SurveyCategoryLevel.BRANCH)
            )
        )

        // when
        val keywords = selectSurveyKeywordsService.selectKeywords(summary)

        // then
        assertThat(keywords.map { it.keyword }).contains("강남역 양식 맛집")
        assertThat(keywords.map { it.keyword }).contains("강남역 아시안 맛집")
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

    private fun createMockSurveySummary(
        stationName: String,
        totalRespondents: Int,
        leafVotes: Map<Long, Int> = emptyMap(),
        branchVotes: Map<Long, Int> = emptyMap(),
        leafCategories: Map<Long, SurveyCategory> = emptyMap(),
        branchCategories: Map<Long, SurveyCategory> = emptyMap()
    ): PlaceSurveySummary {
        return PlaceSurveySummary(
            stationName = stationName,
            stationCoordinates = MeetingQuery.StationCoordinates(37.498, 127.028),
            totalRespondents = totalRespondents,
            leafVotes = leafVotes,
            branchVotes = branchVotes,
            leafCategories = leafCategories,
            branchCategories = branchCategories
        )
    }

    private fun createLeafCategory(
        id: Long,
        name: String,
        parentId: Long,
        sortOrder: Int = 0
    ): SurveyCategory {
        return SurveyCategory(
            id = id,
            parentId = parentId,
            level = SurveyCategoryLevel.LEAF,
            name = name,
            sortOrder = sortOrder,
            isDeleted = false,
            createdAt = LocalDateTime.now(),
            updatedAt = LocalDateTime.now()
        )
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
