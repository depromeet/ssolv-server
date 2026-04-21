package org.depromeet.team3.mapper

import org.depromeet.team3.auth.UserRepository
import org.depromeet.team3.auth.exception.UserException
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.meeting.Meeting
import org.depromeet.team3.meeting.MeetingEntity
import org.depromeet.team3.station.StationJpaRepository
import org.depromeet.team3.station.exception.StationException
import org.springframework.stereotype.Component

@Component
class MeetingMapper(
    private val userRepository: UserRepository,
    private val stationJpaRepository: StationJpaRepository,
    private val stationMapper: StationMapper,
) : DomainMapper<Meeting, MeetingEntity> {

    override fun toDomain(entity: MeetingEntity): Meeting = Meeting(
        id = entity.id!!,
        name = entity.name,
        hostUserId = entity.hostUser.id!!,
        attendeeCount = entity.attendeeCount,
        isClosed = entity.isClosed,
        stationId = stationMapper.toDomain(entity.station).id!!,
        endAt = entity.endAt,
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
    )

    override fun toEntity(domain: Meeting): MeetingEntity {
        val userEntity = userRepository.findById(domain.hostUserId)
            .orElseThrow {
                UserException(
                    errorCode = ErrorCode.USER_NOT_FOUND,
                    detail = mapOf("userId" to domain.hostUserId),
                )
            }

        val stationEntity = stationJpaRepository.findById(domain.stationId)
            .orElseThrow {
                StationException(
                    errorCode = ErrorCode.STATION_NOT_FOUND,
                    detail = mapOf("stationId" to domain.stationId),
                )
            }

        return MeetingEntity(
            id = domain.id,
            name = domain.name,
            attendeeCount = domain.attendeeCount,
            isClosed = domain.isClosed,
            endAt = domain.endAt,
            hostUser = userEntity,
            station = stationEntity,
        )
    }
}
