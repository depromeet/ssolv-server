package org.depromeet.team3.station.application

import org.depromeet.team3.station.dto.response.StationResponse
import org.depromeet.team3.station.StationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class GetStationService(
    private val stationRepository: StationRepository
) {

    @Transactional(readOnly = true)
    suspend fun getAllStations(): List<StationResponse> {
        return stationRepository.findAll().map { StationResponse(it.id!!, it.name) }
    }
}