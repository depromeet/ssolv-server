package org.depromeet.team3.station.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.depromeet.team3.common.ContextConstants
import org.depromeet.team3.common.annotation.UserId
import org.depromeet.team3.common.response.DpmApiResponse
import org.depromeet.team3.station.application.GetStationService
import org.depromeet.team3.station.dto.response.StationResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "지하철역", description = "역 관련 API")
@RestController
@RequestMapping("${ContextConstants.API_VERSION_V1}/stations")
class StationController(private val getStationService: GetStationService) {

    @Operation(
        summary = "모든 지하철역 조회",
        description = "모든 지하철역 목록을 반환합니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "모든 역 조회 성공"),
    )
    @GetMapping
    suspend fun getAllStations(@UserId userId: Long): DpmApiResponse<List<StationResponse>> {
        val response = getStationService.getAllStations()

        return DpmApiResponse.ok(response)
    }
}
