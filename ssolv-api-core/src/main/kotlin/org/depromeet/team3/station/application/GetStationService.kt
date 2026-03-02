package org.depromeet.team3.station.application

import kotlinx.coroutines.withContext
import org.depromeet.team3.common.util.CoroutineDispatchers
import org.depromeet.team3.station.dto.response.StationResponse
import org.depromeet.team3.station.StationRepository
import org.springframework.stereotype.Service

@Service
class GetStationService(
    private val stationRepository: StationRepository,
    private val coroutineDispatchers: CoroutineDispatchers
) {

    suspend fun getAllStations(): List<StationResponse> = withContext(coroutineDispatchers.VT) {
        stationRepository.findAll().map { StationResponse(it.id!!, it.name) }
    }
}