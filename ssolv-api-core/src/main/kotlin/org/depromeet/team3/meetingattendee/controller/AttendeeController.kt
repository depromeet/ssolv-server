package org.depromeet.team3.meetingattendee.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.depromeet.team3.common.ContextConstants
import org.depromeet.team3.common.annotation.MeetingId
import org.depromeet.team3.common.annotation.UserId
import org.depromeet.team3.common.response.DpmApiResponse
import org.depromeet.team3.meetingattendee.application.UpdateAttendeeService
import org.depromeet.team3.meetingattendee.dto.request.UpdateAttendeeRequest
import org.springframework.web.bind.annotation.*

@Tag(name = "모임 참여자", description = "모임 참여자 API")
@RestController
@RequestMapping("${ContextConstants.API_VERSION_V1}/meetings/{meetingId}/attendees")
class AttendeeController(
    private val updateAttendeeService: UpdateAttendeeService
) {

    @Operation(
        summary = "참여자 정보 수정",
        description = "참여자의 닉네임과 무찌 색상을 수정합니다."
    )
    @PutMapping()
    fun updateAttendee(
        @UserId userId: Long,

        @Parameter(description = "모임 ID 또는 초대 토큰", required = true)
        @MeetingId("meetingId") meetingId: Long,

        @Parameter(description = "참여자 정보 수정 요청", required = true)
        @RequestBody @Valid request: UpdateAttendeeRequest
    ): DpmApiResponse<Unit> {
        updateAttendeeService(
            userId = userId,
            meetingId = meetingId,
            attendeeNickname = request.attendeeNickname,
            color = request.color
        )

        return DpmApiResponse.ok()
    }
}