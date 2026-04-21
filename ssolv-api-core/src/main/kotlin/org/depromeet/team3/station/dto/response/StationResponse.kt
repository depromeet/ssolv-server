package org.depromeet.team3.station.dto.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "역 정보 응답")
data class StationResponse(

    @Schema(description = "역 ID", example = "1")
    val id: Long,

    @Schema(description = "역 이름", example = "강남역")
    val name: String,
)
