package org.depromeet.team3.survey

import kotlinx.coroutines.withContext
import org.depromeet.team3.common.util.CoroutineDispatchers
import com.querydsl.jpa.impl.JPAQueryFactory
import org.depromeet.team3.mapper.SurveyMapper
import org.depromeet.team3.survey.QSurveyEntity
import org.springframework.stereotype.Repository

@Repository
class SurveyQuery(
    private val surveyMapper: SurveyMapper,
    private val surveyJpaRepository: SurveyJpaRepository,
    private val queryFactory: JPAQueryFactory,
    private val coroutineDispatchers: CoroutineDispatchers
) : SurveyRepository {
    
    override suspend fun save(survey: Survey): Survey = withContext(coroutineDispatchers.VT) {
        val entity = surveyMapper.toEntity(survey)
        surveyMapper.toDomain(surveyJpaRepository.save(entity))
    }
    
    override suspend fun findByMeetingIdAndParticipantId(meetingId: Long, participantId: Long): Survey? = withContext(coroutineDispatchers.VT) {
        surveyJpaRepository.findByMeetingIdAndParticipantId(meetingId, participantId)
            ?.let { surveyMapper.toDomain(it) }
    }
    
    override suspend fun findByMeetingId(meetingId: Long): List<Survey> = withContext(coroutineDispatchers.VT) {
        val qSurvey = QSurveyEntity.surveyEntity
        
        val entities = queryFactory
            .selectFrom(qSurvey)
            .leftJoin(qSurvey.meeting).fetchJoin()
            .leftJoin(qSurvey.participant).fetchJoin()
            .leftJoin(qSurvey.participant.user).fetchJoin()
            .where(qSurvey.meeting.id.eq(meetingId))
            .fetch()
        
        entities.map { surveyMapper.toDomain(it) }
    }
    
    override suspend fun existsByMeetingIdAndParticipantId(meetingId: Long, participantId: Long): Boolean = withContext(coroutineDispatchers.VT) {
        surveyJpaRepository.existsByMeetingIdAndParticipantId(meetingId, participantId)
    }
}
