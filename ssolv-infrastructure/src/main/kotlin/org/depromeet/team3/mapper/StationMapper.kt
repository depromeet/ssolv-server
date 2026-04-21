package org.depromeet.team3.mapper

import org.depromeet.team3.station.Station
import org.depromeet.team3.station.StationEntity
import org.springframework.stereotype.Component

@Component
class StationMapper : DomainMapper<Station, StationEntity> {

    override fun toDomain(entity: StationEntity): Station = Station(
        id = entity.id,
        name = entity.name,
        locX = entity.locX,
        locY = entity.locY,
        isDeleted = entity.isDeleted,
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
    )

    override fun toEntity(domain: Station): StationEntity = StationEntity(
        id = domain.id,
        name = domain.name,
        locX = domain.locX,
        locY = domain.locY,
        isDeleted = domain.isDeleted,
    )
}
