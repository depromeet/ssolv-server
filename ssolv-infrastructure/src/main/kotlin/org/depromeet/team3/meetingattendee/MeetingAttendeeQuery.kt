package org.depromeet.team3.meetingattendee

import kotlinx.coroutines.withContext
import org.depromeet.team3.common.util.CoroutineDispatchers
import org.depromeet.team3.mapper.MeetingAttendeeMapper
import org.springframework.stereotype.Repository

@Repository
class MeetingAttendeeQuery(
    private val meetingAttendeeMapper: MeetingAttendeeMapper,
    private val meetingAttendeeJpaRepository: MeetingAttendeeJpaRepository,
    private val coroutineDispatchers: CoroutineDispatchers
) : MeetingAttendeeRepository {

    override suspend fun save(meetingAttendee: MeetingAttendee): MeetingAttendee = withContext(coroutineDispatchers.VT) {
        val entity = meetingAttendeeMapper.toEntity(meetingAttendee)
        meetingAttendeeMapper.toDomain(meetingAttendeeJpaRepository.save(entity))
    }

    override suspend fun findByMeetingId(meetingId: Long): List<MeetingAttendee> = withContext(coroutineDispatchers.VT) {
        meetingAttendeeJpaRepository.findByMeetingId(meetingId)
            .map { meetingAttendeeMapper.toDomain(it) }
    }

    override suspend fun findByUserId(userId: Long): List<MeetingAttendee> = withContext(coroutineDispatchers.VT) {
        meetingAttendeeJpaRepository.findByUserId(userId)
            .map { meetingAttendeeMapper.toDomain(it) }
    }

    override suspend fun findByMeetingIdAndUserId(
        meetingId: Long,
        userId: Long
    ): MeetingAttendee? = withContext(coroutineDispatchers.VT) {
        meetingAttendeeJpaRepository.findByMeetingIdAndUserId(meetingId, userId)
            ?.let { meetingAttendeeMapper.toDomain(it) }
    }

    override suspend fun existsByMeetingIdAndUserId(
        meetingId: Long,
        userId: Long
    ): Boolean = withContext(coroutineDispatchers.VT) {
        meetingAttendeeJpaRepository.existsByMeetingIdAndUserId(meetingId, userId)
    }

    override suspend fun existsByMeetingIdAndNormalizedNickname(
        meetingId: Long,
        nickname: String,
        excludeUserId: Long
    ): Boolean = withContext(coroutineDispatchers.VT) {
        meetingAttendeeJpaRepository.existsByMeetingIdAndNickname(meetingId, nickname, excludeUserId)
    }

    override suspend fun countByMeetingId(meetingId: Long): Int = withContext(coroutineDispatchers.VT) {
        meetingAttendeeJpaRepository.countByMeetingId(meetingId)
    }

    override suspend fun deleteById(id: Long): Unit = withContext(coroutineDispatchers.VT) {
        meetingAttendeeJpaRepository.deleteById(id)
    }
}
