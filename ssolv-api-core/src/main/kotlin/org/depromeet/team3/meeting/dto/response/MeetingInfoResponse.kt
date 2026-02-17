package org.depromeet.team3.meeting.dto.response

import com.fasterxml.jackson.annotation.JsonFormat
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

@Schema(description = "모임 정보 응답")
data class MeetingInfoResponse(
    @Schema(description = "모임 ID", example = "1")
    val id: Long,

    @Schema(description = "모임 이름", example = "저녁 모무찌")
    val title: String? = null,
    
    @Schema(description = "호스트 사용자 ID", example = "123")
    val hostUserId: Long,
    
    @Schema(description = "전체 참여자 수", example = "5")
    val totalParticipantCnt: Int,
    
    @Schema(description = "모임 종료 여부", example = "false")
    val isClosed: Boolean,
    
    @Schema(description = "역 이름", example = "강남")
    val stationName: String,
    
    @Schema(description = "모임 종료 시간", example = "2024-12-31T18:00:00")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    val endAt: LocalDateTime,
    
    @Schema(description = "모임 생성 시간", example = "2024-12-25T10:00:00")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    val createdAt: LocalDateTime,
    
    @Schema(description = "모임 수정 시간", example = "2024-12-25T15:30:00")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    val updatedAt: LocalDateTime?,

    @Schema(description = "모임 토큰", example = "abc123def456")
    val token: String? = null
)