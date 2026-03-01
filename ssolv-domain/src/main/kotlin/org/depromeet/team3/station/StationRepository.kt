package org.depromeet.team3.station

interface StationRepository {
    suspend fun findAll(): List<Station>
    suspend fun findAllById(ids: List<Long>): List<Station>
    suspend fun findById(id: Long): Station?
}