package org.depromeet.team3.survey

import com.querydsl.jpa.impl.JPAQueryFactory
import org.depromeet.team3.mapper.SurveyMapper
import org.depromeet.team3.survey.QSurveyEntity
import org.springframework.stereotype.Repository

@Repository
class SurveyQuery(
    private val surveyMapper: SurveyMapper,
    private val surveyJpaRepository: SurveyJpaRepository,
    private val queryFactory: JPAQueryFactory,
) : SurveyRepository {
    
    override suspend fun save(survey: Survey): Survey {
        val entity = surveyMapper.toEntity(survey)
        return surveyMapper.toDomain(surveyJpaRepository.save(entity))
    }
    
    override suspend fun findByMeetingIdAndParticipantId(meetingId: Long, participantId: Long): Survey? {
        return surveyJpaRepository.findByMeetingIdAndParticipantId(meetingId, participantId)
            ?.let { surveyMapper.toDomain(it) }
    }
    
    override suspend fun findByMeetingId(meetingId: Long): List<Survey> {
        val qSurvey = QSurveyEntity.surveyEntity
        
        val entities = queryFactory
            .selectFrom(qSurvey)
            .leftJoin(qSurvey.meeting).fetchJoin()
            .leftJoin(qSurvey.participant).fetchJoin()
            .leftJoin(qSurvey.participant.user).fetchJoin()
            .where(qSurvey.meeting.id.eq(meetingId))
            .fetch()
        
        return entities.map { surveyMapper.toDomain(it) }
    }

    override suspend fun findByMeetingIdIn(meetingIds: List<Long>): List<Survey> {
        if (meetingIds.isEmpty()) return emptyList()
        val qSurvey = QSurveyEntity.surveyEntity
        
        val entities = queryFactory
            .selectFrom(qSurvey)
            .leftJoin(qSurvey.meeting).fetchJoin()
            .leftJoin(qSurvey.participant).fetchJoin()
            .leftJoin(qSurvey.participant.user).fetchJoin()
            .where(qSurvey.meeting.id.`in`(meetingIds))
            .fetch()
        
        return entities.map { surveyMapper.toDomain(it) }
    }
    
    override suspend fun existsByMeetingIdAndParticipantId(meetingId: Long, participantId: Long): Boolean {
        return surveyJpaRepository.existsByMeetingIdAndParticipantId(meetingId, participantId)
    }
}
