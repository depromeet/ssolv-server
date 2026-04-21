package org.depromeet.team3.meetingplace

import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.mapper.MeetingPlaceMapper
import org.depromeet.team3.meeting.MeetingJpaRepository
import org.depromeet.team3.meeting.exception.MeetingException
import org.depromeet.team3.place.PlaceJpaRepository
import org.depromeet.team3.place.exception.PlaceException
import org.springframework.stereotype.Component

@Component
class MeetingPlaceQuery(
    private val meetingPlaceJpaRepository: MeetingPlaceJpaRepository,
    private val meetingPlaceMapper: MeetingPlaceMapper,
    private val meetingJpaRepository: MeetingJpaRepository,
    private val placeJpaRepository: PlaceJpaRepository,
) : MeetingPlaceRepository {

    override suspend fun save(meetingPlace: MeetingPlace): MeetingPlace {
        val meeting = meetingJpaRepository.findById(meetingPlace.meetingId)
            .orElseThrow {
                MeetingException(
                    errorCode = ErrorCode.MEETING_NOT_FOUND,
                    detail = mapOf("meetingId" to meetingPlace.meetingId),
                )
            }
        val place = placeJpaRepository.findById(meetingPlace.placeId)
            .orElseThrow {
                PlaceException(
                    errorCode = ErrorCode.PLACE_NOT_FOUND,
                    detail = mapOf("placeId" to meetingPlace.placeId),
                )
            }

        val entity = meetingPlaceMapper.toEntity(meetingPlace, meeting, place)
        val saved = meetingPlaceJpaRepository.save(entity)
        return meetingPlaceMapper.toDomain(saved)
    }

    override suspend fun saveAll(meetingPlaces: List<MeetingPlace>): List<MeetingPlace> {
        // Meeting과 Place를 미리 조회 (N+1 방지)
        val meetingIds = meetingPlaces.map { it.meetingId }.distinct()
        val placeIds = meetingPlaces.map { it.placeId }.distinct()

        val meetings = meetingJpaRepository.findAllById(meetingIds).associateBy { it.id }
        val places = placeJpaRepository.findAllById(placeIds).associateBy { it.id }

        // Entity 변환
        val entities = meetingPlaces.map { meetingPlace ->
            val meeting = meetings[meetingPlace.meetingId]
                ?: throw MeetingException(
                    errorCode = ErrorCode.MEETING_NOT_FOUND,
                    detail = mapOf("meetingId" to meetingPlace.meetingId),
                )
            val place = places[meetingPlace.placeId]
                ?: throw PlaceException(
                    errorCode = ErrorCode.PLACE_NOT_FOUND,
                    detail = mapOf("placeId" to meetingPlace.placeId),
                )

            meetingPlaceMapper.toEntity(meetingPlace, meeting, place)
        }

        // 저장
        val saved = meetingPlaceJpaRepository.saveAll(entities)
        return saved.map { meetingPlaceMapper.toDomain(it) }
    }

    override suspend fun findByMeetingId(meetingId: Long): List<MeetingPlace> = meetingPlaceJpaRepository.findByMeetingId(meetingId)
        .map { meetingPlaceMapper.toDomain(it) }

    override suspend fun findByMeetingIdAndPlaceId(meetingId: Long, placeId: Long): MeetingPlace? =
        meetingPlaceJpaRepository.findByMeetingIdAndPlaceId(meetingId, placeId)
            ?.let { meetingPlaceMapper.toDomain(it) }

    override suspend fun findIdByMeetingIdAndPlaceId(meetingId: Long, placeId: Long): Long? =
        meetingPlaceJpaRepository.findIdByMeetingIdAndPlaceId(meetingId, placeId)

    override suspend fun deleteByMeetingId(meetingId: Long) {
        meetingPlaceJpaRepository.deleteByMeetingId(meetingId)
    }

    override suspend fun existsByMeetingIdAndPlaceId(meetingId: Long, placeId: Long): Boolean =
        meetingPlaceJpaRepository.existsByMeetingIdAndPlaceId(meetingId, placeId)
}
