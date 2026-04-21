package org.depromeet.team3.surveycategory.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.depromeet.team3.common.ContextConstants
import org.depromeet.team3.common.response.DpmApiResponse
import org.depromeet.team3.surveycategory.application.CreateSurveyCategoryService
import org.depromeet.team3.surveycategory.application.DeleteSurveyCategoryService
import org.depromeet.team3.surveycategory.application.GetSurveyCategoryService
import org.depromeet.team3.surveycategory.application.UpdateSurveyCategoryService
import org.depromeet.team3.surveycategory.dto.request.CreateSurveyCategoryRequest
import org.depromeet.team3.surveycategory.dto.request.UpdateSurveyCategoryRequest
import org.depromeet.team3.surveycategory.dto.response.CreateSurveyCategoryResponse
import org.depromeet.team3.surveycategory.dto.response.SurveyCategoryItem
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "설문 카테고리", description = "설문 카테고리 관리 API")
@RestController
@RequestMapping("${ContextConstants.API_VERSION_V1}/survey-categories")
class SurveyCategoryController(
    private val createSurveyCategoryService: CreateSurveyCategoryService,
    private val getSurveyCategoryService: GetSurveyCategoryService,
    private val updateSurveyCategoryService: UpdateSurveyCategoryService,
    private val deleteSurveyCategoryService: DeleteSurveyCategoryService,
) {

    @Operation(
        summary = "설문 카테고리 목록 조회",
        description = "모든 활성 설문 카테고리를 계층 구조로 조회합니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "카테고리 목록 조회 성공"),
    )
    @GetMapping
    suspend fun getSurveyCategoryList(): DpmApiResponse<List<SurveyCategoryItem>> {
        val response = getSurveyCategoryService()

        return DpmApiResponse.ok(response)
    }

    @Operation(
        summary = "설문 카테고리 생성",
        description = "새로운 설문 카테고리를 생성합니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "카테고리 생성 성공"),
    )
    @PostMapping
    suspend fun create(@RequestBody @Valid request: CreateSurveyCategoryRequest): DpmApiResponse<CreateSurveyCategoryResponse> {
        val response = createSurveyCategoryService(request)

        return DpmApiResponse.ok(response)
    }

    @Operation(
        summary = "설문 카테고리 수정",
        description = "기존 설문 카테고리 정보를 수정합니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "카테고리 수정 성공"),
        ApiResponse(responseCode = "404", description = "카테고리를 찾을 수 없음"),
    )
    @PutMapping("/{id}")
    suspend fun update(
        @Parameter(description = "카테고리 ID", example = "1")
        @PathVariable id: Long,
        @RequestBody @Valid request: UpdateSurveyCategoryRequest,
    ): DpmApiResponse<Unit> {
        updateSurveyCategoryService(id, request)

        return DpmApiResponse.ok()
    }

    @Operation(
        summary = "설문 카테고리 삭제",
        description = "설문 카테고리를 삭제합니다. 하위 카테고리가 있으면 삭제할 수 없습니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "카테고리 삭제 성공"),
        ApiResponse(responseCode = "404", description = "카테고리를 찾을 수 없음"),
        ApiResponse(responseCode = "400", description = "하위 카테고리가 존재하여 삭제 불가"),
    )
    @DeleteMapping("/{id}")
    suspend fun delete(
        @Parameter(description = "카테고리 ID", example = "1")
        @PathVariable id: Long,
    ): DpmApiResponse<Unit> {
        deleteSurveyCategoryService(id)

        return DpmApiResponse.ok()
    }
}
