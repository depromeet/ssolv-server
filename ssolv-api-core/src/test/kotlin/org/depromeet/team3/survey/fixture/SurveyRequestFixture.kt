package org.depromeet.team3.survey.fixture

import org.depromeet.team3.survey.dto.request.SurveyCreateRequest
import org.depromeet.team3.survey.dto.response.SurveyCreateResponse

object SurveyRequestFixture {

    fun createRequest(selectedCategoryList: List<Long> = listOf(1L, 3L)) = SurveyCreateRequest(selectedCategoryList = selectedCategoryList)

    fun createMinimalRequest() = SurveyCreateRequest(selectedCategoryList = listOf(1L))

    fun createEmptyRequest() = SurveyCreateRequest(selectedCategoryList = emptyList())

    fun createResponse(message: String = "설문 제출이 완료되었습니다") = SurveyCreateResponse(message = message)
}
