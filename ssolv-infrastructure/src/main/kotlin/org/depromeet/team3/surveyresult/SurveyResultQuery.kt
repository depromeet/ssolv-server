package org.depromeet.team3.surveyresult

import kotlinx.coroutines.withContext
import org.depromeet.team3.common.util.CoroutineDispatchers
import org.depromeet.team3.mapper.SurveyResultMapper
import org.springframework.stereotype.Repository

@Repository
class SurveyResultQuery(
    private val surveyResultMapper: SurveyResultMapper,
    private val surveyResultJpaRepository: SurveyResultJpaRepository,
    private val coroutineDispatchers: CoroutineDispatchers
) : SurveyResultRepository {
    
    override suspend fun save(surveyResult: SurveyResult): SurveyResult = withContext(coroutineDispatchers.VT) {
        val entity = surveyResultMapper.toEntity(surveyResult)
        surveyResultMapper.toDomain(surveyResultJpaRepository.save(entity))
    }
    
    override suspend fun saveAll(surveyResults: List<SurveyResult>): List<SurveyResult> = withContext(coroutineDispatchers.VT) {
        val entities = surveyResults.map { surveyResultMapper.toEntity(it) }
        val savedEntities = surveyResultJpaRepository.saveAll(entities)
        savedEntities.map { surveyResultMapper.toDomain(it) }
    }
    
    override suspend fun findBySurveyId(surveyId: Long): List<SurveyResult> = withContext(coroutineDispatchers.VT) {
        surveyResultJpaRepository.findBySurveyId(surveyId)
            .map { surveyResultMapper.toDomain(it) }
    }
    
    override suspend fun findBySurveyIdIn(surveyIds: List<Long>): List<SurveyResult> = withContext(coroutineDispatchers.VT) {
        surveyResultJpaRepository.findBySurveyIdIn(surveyIds)
            .map { surveyResultMapper.toDomain(it) }
    }
}