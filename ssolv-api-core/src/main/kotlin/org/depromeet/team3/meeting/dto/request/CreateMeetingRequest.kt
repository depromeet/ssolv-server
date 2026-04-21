package org.depromeet.team3.meeting.dto.request

import com.fasterxml.jackson.annotation.JsonFormat
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

data class CreateMeetingRequest(

    @Schema(description = "모임 이름", example = "점심 모무찌?")
    val name: String,

    @Schema(description = "모임 참여 인원", example = "1")
    val attendeeCount: Int,

    @Schema(description = "역 ID", example = "1")
    val stationId: Long,

    @Schema(description = "모임 종료 시간", example = "2024-12-25T15:30:00")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    val endAt: LocalDateTime? = null,
)
