package org.depromeet.team3.surveyresult

interface SurveyResultRepository {
    suspend fun save(surveyResult: SurveyResult): SurveyResult
    suspend fun saveAll(surveyResults: List<SurveyResult>): List<SurveyResult>
    suspend fun findBySurveyId(surveyId: Long): List<SurveyResult>
    suspend fun findBySurveyIdIn(surveyIds: List<Long>): List<SurveyResult>
}
