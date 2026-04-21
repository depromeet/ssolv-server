package org.depromeet.team3.placelike.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.depromeet.team3.common.ContextConstants
import org.depromeet.team3.common.annotation.MeetingId
import org.depromeet.team3.common.annotation.UserId
import org.depromeet.team3.common.response.DpmApiResponse
import org.depromeet.team3.placelike.application.PlaceLikeService
import org.depromeet.team3.placelike.application.PlaceLikeSseService
import org.depromeet.team3.placelike.dto.PlaceLikeResponse
import org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@Tag(name = "맛집 좋아요", description = "맛집 좋아요 API")
@RestController
@RequestMapping("${ContextConstants.API_VERSION_V1}/meetings/{meetingId}/places")
class PlaceLikeController(private val placeLikeService: PlaceLikeService, private val sseService: PlaceLikeSseService) {

    @Operation(
        summary = "맛집 좋아요 토글",
        description = "맛집에 좋아요를 추가하거나 취소합니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "좋아요 토글 성공"),
        ApiResponse(responseCode = "404", description = "MeetingPlace를 찾을 수 없음"),
        ApiResponse(responseCode = "400", description = "잘못된 요청"),
    )
    @PostMapping("/{placeId}/like")
    suspend fun toggleLike(
        @Parameter(description = "모임 ID 또는 초대 토큰", required = true)
        @MeetingId("meetingId") meetingId: Long,
        @Parameter(description = "Place ID", required = true)
        @PathVariable placeId: Long,
        @UserId userId: Long,
    ): DpmApiResponse<PlaceLikeResponse> {
        val result = placeLikeService.toggle(meetingId, userId, placeId)

        val response = PlaceLikeResponse(
            isLiked = result.isLiked,
            likeCount = result.likeCount,
            message = if (result.isLiked) "좋아요를 추가했습니다." else "좋아요를 취소했습니다.",
        )
        return DpmApiResponse.ok(response)
    }

    @Operation(
        summary = "맛집 업데이트 실시간 구독",
        description = "해당 모임의 맛집 좋아요 변경 및 순위 변동 이벤트를 실시간으로 구독합니다.",
    )
    @GetMapping(value = ["/events"], produces = [TEXT_EVENT_STREAM_VALUE])
    fun subscribe(
        @Parameter(description = "모임 ID 또는 초대 토큰", required = true)
        @MeetingId("meetingId") meetingId: Long,
    ): SseEmitter = sseService.subscribe(meetingId)
}
