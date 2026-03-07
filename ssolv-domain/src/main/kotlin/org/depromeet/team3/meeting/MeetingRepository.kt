package org.depromeet.team3.meeting

interface MeetingRepository {
    suspend fun save(meeting: Meeting): Meeting
    suspend fun findMeetingsByUserId(userId: Long): List<Meeting>
    suspend fun findById(id: Long): Meeting?
    suspend fun findAllById(ids: List<Long>): List<Meeting>
}