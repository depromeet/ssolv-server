package org.depromeet.team3.meeting.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.depromeet.team3.common.ContextConstants
import org.depromeet.team3.common.annotation.MeetingId
import org.depromeet.team3.common.annotation.UserId
import org.depromeet.team3.common.response.DpmApiResponse
import org.depromeet.team3.meeting.application.CreateMeetingService
import org.depromeet.team3.meeting.application.GetMeetingDetailService
import org.depromeet.team3.meeting.application.GetMeetingService
import org.depromeet.team3.meeting.application.InviteTokenService
import org.depromeet.team3.meeting.application.JoinMeetingService
import org.depromeet.team3.meeting.dto.request.CreateMeetingRequest
import org.depromeet.team3.meeting.dto.request.JoinMeetingRequest
import org.depromeet.team3.meeting.dto.response.*
import org.springframework.web.bind.annotation.*

@Tag(name = "모임", description = "모임 관련 API")
@RestController
@RequestMapping("${ContextConstants.API_VERSION_V1}/meetings")
class MeetingController(
    private val creteMeetingService: CreateMeetingService,
    private val getMeetingService: GetMeetingService,
    private val getMeetingDetailService: GetMeetingDetailService,
    private val inviteTokenService: InviteTokenService,
    private val joinMeetingService: JoinMeetingService
) {

    @Operation(
        summary = "사용자 모임 목록 조회",
        description = "특정 사용자가 참여한 모임 목록을 조회합니다."
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "모임 목록 조회 성공")
    )
    @GetMapping
    fun getMeeting(
        @UserId userId: Long
    ) : DpmApiResponse<List<MeetingsResponse>> {
        val response = getMeetingService(userId)

        return DpmApiResponse.ok(response)
    }

    @Operation(
        summary = "모임 상세 정보 조회",
        description = "특정 모임의 상세 정보를 조회합니다."
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "모임 상세 정보 조회 성공")
    )
    @GetMapping("/{identifier}")
    fun getMeetingDetail(
        @Parameter(description = "모임 ID 또는 초대 토큰", example = "1")
        @MeetingId("identifier") meetingId: Long,
        @UserId userId: Long
    ) : DpmApiResponse<MeetingDetailResponse> {
        val response = getMeetingDetailService.invoke(meetingId, userId)

        return DpmApiResponse.ok(response)
    }

    @Operation(
        summary = "종료된 모임 상세 정보 조회",
        description = "endAt이 지난 모임도 동일한 응답 스키마로 조회합니다."
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "종료된 모임 상세 정보 조회 성공")
    )
    @GetMapping("/{identifier}/history")
    fun getMeetingDetailHistory(
        @Parameter(description = "모임 ID 또는 초대 토큰", example = "1")
        @MeetingId("identifier") meetingId: Long,
        @UserId userId: Long
    ): DpmApiResponse<MeetingDetailResponse> {
        val response = getMeetingDetailService.invoke(meetingId, userId, allowClosed = true)

        return DpmApiResponse.ok(response)
    }

    @Operation(
        summary = "모임 초대 토큰 조회",
        description = "특정 모임의 초대 토큰을 조회합니다."
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "초대 토큰 조회 성공"),
        ApiResponse(responseCode = "400", description = "잘못된 요청 (모임이 존재하지 않거나 종료된 경우)")
    )
    @GetMapping("/{identifier}/invite-token")
    fun getInviteToken(
        @Parameter(description = "모임 ID 또는 초대 토큰", example = "5")
        @MeetingId("identifier") meetingId: Long
    ): DpmApiResponse<GetInviteTokenResponse> {
        val inviteUrl = inviteTokenService.generateInviteToken(meetingId)
        val token = inviteUrl.substringAfter("token=")
        val response = GetInviteTokenResponse(token = token)

        return DpmApiResponse.ok(response)
    }

    @Operation(
        summary = "모임 초대 토큰 검증",
        description = "모임 초대 토큰의 유효성을 검증합니다."
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "토큰 검증 성공")
    )
    @GetMapping("/validate-invite")
    fun validateInviteToken(
        @Parameter(description = "모임 초대 토큰", example = "abc123def456")
        @RequestParam token: String,
        @UserId userId: Long
    ): DpmApiResponse<ValidateInviteTokenResponse> {
        val response = inviteTokenService.validateInviteToken(userId,token)

        return DpmApiResponse.ok(response)
    }

    @Operation(
        summary = "모임 생성",
        description = "새로운 모임을 생성합니다."
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "모임 생성 성공")
    )
    @PostMapping
    fun create(
        @UserId userId: Long,
        @RequestBody @Valid request: CreateMeetingRequest,
    ) : DpmApiResponse<CreateMeetingResponse> {
        val response = creteMeetingService(request, userId)

        return DpmApiResponse.ok(response)
    }

    @Operation(
        summary = "모임 참여",
        description = "특정 모임에 참여합니다."
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "모임 참여 성공")
    )
    @PostMapping("/join")
    fun join(
        @UserId userId: Long,
        @RequestBody @Valid request: JoinMeetingRequest
    ) : DpmApiResponse<Unit> {
        joinMeetingService.invoke(userId, request.token)

        return DpmApiResponse.ok()
    }
}