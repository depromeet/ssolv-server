package org.depromeet.team3.station

import org.depromeet.team3.mapper.StationMapper
import org.springframework.stereotype.Repository

@Repository
class StationQuery(
    private val stationMapper: StationMapper,
    private val stationJpaRepository: StationJpaRepository,
) : StationRepository {

    override suspend fun findAll(): List<Station> {
        return stationJpaRepository.findAll().map { stationMapper.toDomain(it) }
    }

    override suspend fun findAllById(ids: List<Long>): List<Station> {
        return stationJpaRepository.findAllById(ids).map { stationMapper.toDomain(it) }
    }

    override suspend fun findById(id: Long): Station? {
        return stationJpaRepository.findById(id).orElse(null)?.let { stationMapper.toDomain(it) }
    }
}