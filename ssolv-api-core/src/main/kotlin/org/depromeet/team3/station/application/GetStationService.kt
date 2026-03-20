package org.depromeet.team3.station.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.depromeet.team3.station.dto.response.StationResponse
import org.depromeet.team3.station.StationRepository
import org.springframework.stereotype.Service

@Service
class GetStationService(
    private val stationRepository: StationRepository,
) {

    suspend fun getAllStations(): List<StationResponse> = withContext(Dispatchers.IO) {
        stationRepository.findAll().map { StationResponse(it.id!!, it.name) }
    }
}