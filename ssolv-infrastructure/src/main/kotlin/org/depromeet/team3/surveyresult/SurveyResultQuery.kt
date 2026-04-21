package org.depromeet.team3.surveyresult

import org.depromeet.team3.mapper.SurveyResultMapper
import org.springframework.stereotype.Repository

@Repository
class SurveyResultQuery(
    private val surveyResultMapper: SurveyResultMapper,
    private val surveyResultJpaRepository: SurveyResultJpaRepository,
) : SurveyResultRepository {

    override suspend fun save(surveyResult: SurveyResult): SurveyResult {
        val entity = surveyResultMapper.toEntity(surveyResult)
        return surveyResultMapper.toDomain(surveyResultJpaRepository.save(entity))
    }

    override suspend fun saveAll(surveyResults: List<SurveyResult>): List<SurveyResult> {
        val entities = surveyResults.map { surveyResultMapper.toEntity(it) }
        val savedEntities = surveyResultJpaRepository.saveAll(entities)
        return savedEntities.map { surveyResultMapper.toDomain(it) }
    }

    override suspend fun findBySurveyId(surveyId: Long): List<SurveyResult> = surveyResultJpaRepository.findBySurveyId(surveyId)
        .map { surveyResultMapper.toDomain(it) }

    override suspend fun findBySurveyIdIn(surveyIds: List<Long>): List<SurveyResult> = surveyResultJpaRepository.findBySurveyIdIn(surveyIds)
        .map { surveyResultMapper.toDomain(it) }
}
