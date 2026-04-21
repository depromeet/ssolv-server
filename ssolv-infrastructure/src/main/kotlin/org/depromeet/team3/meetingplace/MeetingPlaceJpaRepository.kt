package org.depromeet.team3.meetingplace

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface MeetingPlaceJpaRepository : JpaRepository<MeetingPlaceEntity, Long> {
    fun findByMeetingId(meetingId: Long): List<MeetingPlaceEntity>
    fun findByMeetingIdAndPlaceId(meetingId: Long, placeId: Long): MeetingPlaceEntity?

    @Query("SELECT mp.id FROM MeetingPlaceEntity mp WHERE mp.meeting.id = :meetingId AND mp.place.id = :placeId")
    fun findIdByMeetingIdAndPlaceId(meetingId: Long, placeId: Long): Long?

    fun existsByMeetingIdAndPlaceId(meetingId: Long, placeId: Long): Boolean

    @Modifying
    fun deleteByMeetingId(meetingId: Long)
}
