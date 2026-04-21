package org.depromeet.team3.mapper

import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.survey.SurveyJpaRepository
import org.depromeet.team3.surveycategory.SurveyCategoryJpaRepository
import org.depromeet.team3.surveyresult.SurveyResult
import org.depromeet.team3.surveyresult.SurveyResultEntity
import org.depromeet.team3.surveyresult.exception.SurveyResultException
import org.springframework.stereotype.Component

@Component
class SurveyResultMapper(
    private val surveyJpaRepository: SurveyJpaRepository,
    private val surveyCategoryJpaRepository: SurveyCategoryJpaRepository,
) : DomainMapper<SurveyResult, SurveyResultEntity> {

    override fun toDomain(entity: SurveyResultEntity): SurveyResult = SurveyResult(
        id = entity.id,
        surveyId =
        entity.survey.id ?: throw SurveyResultException(ErrorCode.SURVEY_NOT_FOUND, mapOf("message" to "Survey ID cannot be null")),
        surveyCategoryId =
        entity.surveyCategory.id
            ?: throw SurveyResultException(ErrorCode.CATEGORY_NOT_FOUND, mapOf("message" to "SurveyCategory ID cannot be null")),
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
    )

    override fun toEntity(domain: SurveyResult): SurveyResultEntity {
        val surveyEntity = surveyJpaRepository.findById(domain.surveyId)
            .orElseThrow { SurveyResultException(ErrorCode.SURVEY_NOT_FOUND, mapOf("surveyId" to domain.surveyId)) }

        val surveyCategoryEntity = surveyCategoryJpaRepository.findById(domain.surveyCategoryId)
            .orElseThrow { SurveyResultException(ErrorCode.CATEGORY_NOT_FOUND, mapOf("surveyCategoryId" to domain.surveyCategoryId)) }

        return SurveyResultEntity(
            id = domain.id,
            survey = surveyEntity,
            surveyCategory = surveyCategoryEntity,
        )
    }
}
