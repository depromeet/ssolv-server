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
import org.depromeet.team3.placelike.dto.PlaceLikeResponse
import org.springframework.web.bind.annotation.*

@Tag(name = "맛집 좋아요", description = "맛집 좋아요 API")
@RestController
@RequestMapping("${ContextConstants.API_VERSION_V1}/meetings/{meetingId}/places/{placeId}/like")
class PlaceLikeController(
    private val placeLikeService: PlaceLikeService
) {

    @Operation(
        summary = "맛집 좋아요 토글",
        description = "맛집에 좋아요를 추가하거나 취소합니다."
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "좋아요 토글 성공"),
        ApiResponse(responseCode = "404", description = "MeetingPlace를 찾을 수 없음"),
        ApiResponse(responseCode = "400", description = "잘못된 요청")
    )
    @PostMapping
    suspend fun toggleLike(
        @Parameter(description = "모임 ID 또는 초대 토큰", required = true)
        @MeetingId("meetingId") meetingId: Long,
        @Parameter(description = "Place ID", required = true)
        @PathVariable placeId: Long,
        @UserId userId: Long
    ): DpmApiResponse<PlaceLikeResponse> {
        val result = placeLikeService.toggle(meetingId, userId, placeId)
        
        val response = PlaceLikeResponse(
            isLiked = result.isLiked,
            likeCount = result.likeCount,
            message = if (result.isLiked) "좋아요를 추가했습니다." else "좋아요를 취소했습니다."
        )
        return DpmApiResponse.ok(response)
    }
}