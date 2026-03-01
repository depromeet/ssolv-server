package org.depromeet.team3.station

import kotlinx.coroutines.withContext
import org.depromeet.team3.common.util.CoroutineDispatchers
import org.depromeet.team3.mapper.StationMapper
import org.springframework.stereotype.Repository

@Repository
class StationQuery(
    private val stationMapper: StationMapper,
    private val stationJpaRepository: StationJpaRepository,
    private val coroutineDispatchers: CoroutineDispatchers
) : StationRepository {

    override suspend fun findAll(): List<Station> = withContext(coroutineDispatchers.VT) {
        stationJpaRepository.findAll().map { stationMapper.toDomain(it) }
    }

    override suspend fun findAllById(ids: List<Long>): List<Station> = withContext(coroutineDispatchers.VT) {
        stationJpaRepository.findAllById(ids).map { stationMapper.toDomain(it) }
    }

    override suspend fun findById(id: Long): Station? = withContext(coroutineDispatchers.VT) {
        stationJpaRepository.findById(id).orElse(null)?.let { stationMapper.toDomain(it) }
    }
}