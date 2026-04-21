package org.depromeet.team3.meetingplace

interface MeetingPlaceRepository {
    suspend fun save(meetingPlace: MeetingPlace): MeetingPlace
    suspend fun saveAll(meetingPlaces: List<MeetingPlace>): List<MeetingPlace>
    suspend fun findByMeetingId(meetingId: Long): List<MeetingPlace>
    suspend fun findByMeetingIdAndPlaceId(meetingId: Long, placeId: Long): MeetingPlace?
    suspend fun findIdByMeetingIdAndPlaceId(meetingId: Long, placeId: Long): Long?
    suspend fun deleteByMeetingId(meetingId: Long)
    suspend fun existsByMeetingIdAndPlaceId(meetingId: Long, placeId: Long): Boolean
}
