package org.depromeet.team3.meetingattendee

import org.depromeet.team3.mapper.MeetingAttendeeMapper
import org.springframework.stereotype.Repository

@Repository
class MeetingAttendeeQuery(
    private val meetingAttendeeMapper: MeetingAttendeeMapper,
    private val meetingAttendeeJpaRepository: MeetingAttendeeJpaRepository,
) : MeetingAttendeeRepository {

    override suspend fun save(meetingAttendee: MeetingAttendee): MeetingAttendee {
        val entity = meetingAttendeeMapper.toEntity(meetingAttendee)
        return meetingAttendeeMapper.toDomain(meetingAttendeeJpaRepository.save(entity))
    }

    override suspend fun findByMeetingId(meetingId: Long): List<MeetingAttendee> {
        return meetingAttendeeJpaRepository.findByMeetingId(meetingId)
            .map { meetingAttendeeMapper.toDomain(it) }
    }

    override suspend fun findByUserId(userId: Long): List<MeetingAttendee> {
        return meetingAttendeeJpaRepository.findByUserId(userId)
            .map { meetingAttendeeMapper.toDomain(it) }
    }

    override suspend fun findByMeetingIdAndUserId(
        meetingId: Long,
        userId: Long
    ): MeetingAttendee? {
        return meetingAttendeeJpaRepository.findByMeetingIdAndUserId(meetingId, userId)
            ?.let { meetingAttendeeMapper.toDomain(it) }
    }

    override suspend fun existsByMeetingIdAndUserId(
        meetingId: Long,
        userId: Long
    ): Boolean {
        return meetingAttendeeJpaRepository.existsByMeetingIdAndUserId(meetingId, userId)
    }

    override suspend fun existsByMeetingIdAndNormalizedNickname(
        meetingId: Long,
        nickname: String,
        excludeUserId: Long
    ): Boolean {
        return meetingAttendeeJpaRepository.existsByMeetingIdAndNickname(meetingId, nickname, excludeUserId)
    }

    override suspend fun countByMeetingId(meetingId: Long): Int {
        return meetingAttendeeJpaRepository.countByMeetingId(meetingId)
    }

    override suspend fun deleteById(id: Long): Unit {
        meetingAttendeeJpaRepository.deleteById(id)
    }
}
