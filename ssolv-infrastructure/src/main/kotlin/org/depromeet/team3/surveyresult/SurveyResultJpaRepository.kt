package org.depromeet.team3.surveyresult

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface SurveyResultJpaRepository : JpaRepository<SurveyResultEntity, Long> {
    @Query("SELECT sr FROM SurveyResultEntity sr JOIN FETCH sr.survey JOIN FETCH sr.surveyCategory WHERE sr.survey.id = :surveyId")
    fun findBySurveyId(@Param("surveyId") surveyId: Long): List<SurveyResultEntity>

    @Query("SELECT sr FROM SurveyResultEntity sr JOIN FETCH sr.survey JOIN FETCH sr.surveyCategory WHERE sr.survey.id IN (:surveyIds)")
    fun findBySurveyIdIn(@Param("surveyIds") surveyIds: List<Long>): List<SurveyResultEntity>
}
