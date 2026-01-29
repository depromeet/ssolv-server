package org.depromeet.team3.place.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.depromeet.team3.common.ContextConstants
import org.depromeet.team3.common.annotation.MeetingId
import org.depromeet.team3.common.annotation.UserId
import org.depromeet.team3.common.response.DpmApiResponse
import org.depromeet.team3.place.application.execution.PlacePhotoService
import org.depromeet.team3.place.application.facade.GetPlacesService
import org.depromeet.team3.place.dto.request.PlacesSearchRequest
import org.depromeet.team3.place.dto.response.PlacesSearchResponse
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.concurrent.TimeUnit

@Tag(name = "맛집 데이터", description = "구글 플레이스 맛집 검색 API")
@RestController
@RequestMapping("${ContextConstants.API_VERSION_V1}/places")
class PlacesSearchController(
    private val getPlacesService: GetPlacesService,
    private val placePhotoService: PlacePhotoService
) {
    @Operation(
        summary = "맛집 데이터 검색",
        description = "모임 ID를 기반으로 설문 결과에 맞춘 맛집 데이터를 반환합니다. 좋아요 정보가 함께 포함되며 가중치·좋아요 순으로 정렬됩니다."
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "검색 성공"),
        ApiResponse(responseCode = "400", description = "잘못된 요청")
    )
    @GetMapping
    suspend fun textSearch(
        @Parameter(description = "모임 ID 또는 초대 토큰", example = "1")
        @MeetingId("meetingId") meetingId: Long,
        @UserId userId: Long?
    ): DpmApiResponse<PlacesSearchResponse> {
        val request = PlacesSearchRequest(
            meetingId = meetingId,
            userId = userId
        )
        val response = getPlacesService.textSearch(request)
        return DpmApiResponse.ok(response)
    }

    @Operation(
        summary = "장소 사진 조회 (프록시)",
        description = "구글 플레이스 사진을 프록시하여 반환합니다. "
    )
    @GetMapping("/photos/{photoName:.+}", produces = [MediaType.IMAGE_JPEG_VALUE])
    suspend fun getPhoto(
        @PathVariable photoName: String
    ): ResponseEntity<ByteArray> {
        val photoData = placePhotoService.getPhoto(photoName)
            ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok()
            .contentType(MediaType.IMAGE_JPEG)
            .cacheControl(CacheControl.maxAge(30, TimeUnit.DAYS).cachePublic())
            .body(photoData)
    }
}