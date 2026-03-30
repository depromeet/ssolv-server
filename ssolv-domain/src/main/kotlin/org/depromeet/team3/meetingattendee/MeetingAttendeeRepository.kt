package org.depromeet.team3.meetingattendee

interface MeetingAttendeeRepository {
    suspend fun save(meetingAttendee: MeetingAttendee): MeetingAttendee
    suspend fun findByMeetingId(meetingId: Long): List<MeetingAttendee>
    suspend fun findByMeetingIdIn(meetingIds: List<Long>): List<MeetingAttendee>
    suspend fun findByUserId(userId: Long): List<MeetingAttendee>
    suspend fun findByMeetingIdAndUserId(meetingId: Long, userId: Long): MeetingAttendee?
    suspend fun existsByMeetingIdAndUserId(meetingId: Long, userId: Long): Boolean
    suspend fun existsByMeetingIdAndNormalizedNickname(meetingId: Long, nickname: String, excludeUserId: Long): Boolean
    suspend fun countByMeetingId(meetingId: Long): Int
    suspend fun deleteById(id: Long)
}