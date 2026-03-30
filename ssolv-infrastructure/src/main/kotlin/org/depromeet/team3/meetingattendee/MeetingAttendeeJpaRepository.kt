package org.depromeet.team3.meetingattendee

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface MeetingAttendeeJpaRepository : JpaRepository<MeetingAttendeeEntity, Long> {
    @Query("SELECT ma FROM MeetingAttendeeEntity ma JOIN FETCH ma.user JOIN ma.meeting m WHERE m.id = :meetingId")
    fun findByMeetingId(@Param("meetingId") meetingId: Long): List<MeetingAttendeeEntity>

    @Query("SELECT ma FROM MeetingAttendeeEntity ma JOIN FETCH ma.user JOIN ma.meeting m WHERE m.id IN (:meetingIds)")
    fun findByMeetingIdIn(@Param("meetingIds") meetingIds: List<Long>): List<MeetingAttendeeEntity>
    
    @Query("SELECT ma FROM MeetingAttendeeEntity ma JOIN FETCH ma.user JOIN ma.user u WHERE u.id = :userId")
    fun findByUserId(@Param("userId") userId: Long): List<MeetingAttendeeEntity>

    @Query("SELECT ma FROM MeetingAttendeeEntity ma JOIN FETCH ma.user JOIN ma.meeting m JOIN ma.user u WHERE m.id = :meetingId AND u.id = :userId")
    fun findByMeetingIdAndUserId(
        @Param("meetingId") meetingId: Long,
        @Param("userId") userId: Long
    ): MeetingAttendeeEntity?

    @Query("SELECT COUNT(ma) FROM MeetingAttendeeEntity ma JOIN ma.meeting m WHERE m.id = :meetingId")
    fun countByMeetingId(@Param("meetingId") meetingId: Long): Int

    @Query("SELECT CASE WHEN COUNT(ma) > 0 THEN true ELSE false END FROM MeetingAttendeeEntity ma JOIN ma.meeting m JOIN ma.user u WHERE m.id = :meetingId AND u.id = :userId")
    fun existsByMeetingIdAndUserId(
        @Param("meetingId") meetingId: Long,
        @Param("userId") userId: Long
    ): Boolean

    @Query("SELECT CASE WHEN COUNT(ma) > 0 THEN true ELSE false END FROM MeetingAttendeeEntity ma JOIN ma.meeting m JOIN ma.user u WHERE m.id = :meetingId AND ma.attendeeNickname IS NOT NULL AND ma.attendeeNickname = :nickname AND u.id != :excludeUserId")
    fun existsByMeetingIdAndNickname(
        @Param("meetingId") meetingId: Long,
        @Param("nickname") nickname: String,
        @Param("excludeUserId") excludeUserId: Long
    ): Boolean
}
