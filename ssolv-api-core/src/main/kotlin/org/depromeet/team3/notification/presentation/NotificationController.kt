package org.depromeet.team3.notification.presentation

import jakarta.validation.Valid
import org.depromeet.team3.common.ContextConstants
import org.depromeet.team3.common.annotation.UserId
import org.depromeet.team3.common.response.DpmApiResponse
import org.depromeet.team3.notification.application.DeleteDeviceTokenService
import org.depromeet.team3.notification.application.GetNotificationSettingService
import org.depromeet.team3.notification.application.RegisterDeviceTokenService
import org.depromeet.team3.notification.application.UpdateNotificationSettingService
import org.depromeet.team3.notification.dto.DeleteDeviceTokenRequest
import org.depromeet.team3.notification.dto.NotificationSettingResponse
import org.depromeet.team3.notification.dto.RegisterDeviceTokenRequest
import org.depromeet.team3.notification.dto.UpdateNotificationSettingRequest
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.*

/**
 * 푸시 알림 및 사용자의 알림 설정 관리를 담당하는 API 컨트롤러
 */
@Tag(name = "알림 (Notification)", description = "푸시 알림 및 단말기 토큰 관리 API")
@RestController
@RequestMapping("${ContextConstants.API_VERSION_V1}/notifications")
class NotificationController(
    private val getNotificationSettingService: GetNotificationSettingService,
    private val updateNotificationSettingService: UpdateNotificationSettingService,
    private val registerDeviceTokenService: RegisterDeviceTokenService,
    private val deleteDeviceTokenService: DeleteDeviceTokenService
) {

    @Operation(
        summary = "알림 설정 조회",
        description = "마이페이지의 알림 설정 활성화 여부를 조회합니다.",
        responses = [ApiResponse(responseCode = "200", description = "조회 성공")]
    )
    @GetMapping("/settings")
    suspend fun getNotificationSetting(
        @Parameter(hidden = true) @UserId userId: Long
    ): DpmApiResponse<NotificationSettingResponse> {
        val response = getNotificationSettingService.execute(userId)
        return DpmApiResponse.ok(response)
    }

    @Operation(
        summary = "알림 설정 변경",
        description = "마이페이지의 알림 설정 활성화 여부를 변경합니다. false 설정 시 알림 발송 대상에서 제외됩니다.",
        responses = [ApiResponse(responseCode = "200", description = "변경 성공")]
    )
    @PatchMapping("/settings")
    suspend fun updateNotificationSetting(
        @Parameter(hidden = true) @UserId userId: Long,
        @Valid @RequestBody request: UpdateNotificationSettingRequest
    ): DpmApiResponse<Unit> {
        updateNotificationSettingService.execute(userId, request)
        return DpmApiResponse.ok()
    }

    @Operation(
        summary = "FCM 기기 토큰 등록/갱신",
        description = "로그인 직후 호출하여 FCM 기기 토큰을 등록하거나 업데이트합니다.",
        responses = [ApiResponse(responseCode = "200", description = "등록 성공")]
    )
    @PostMapping("/fcm-tokens")
    suspend fun registerFcmToken(
        @Parameter(hidden = true) @UserId userId: Long,
        @Valid @RequestBody request: RegisterDeviceTokenRequest
    ): DpmApiResponse<Unit> {
        registerDeviceTokenService.execute(userId, request)
        return DpmApiResponse.ok()
    }

    @Operation(
        summary = "FCM 기기 토큰 삭제 (로그아웃)",
        description = "로그아웃 시 등록된 기기 토큰을 삭제하여 더 이상 알림을 받지 않도록 합니다.",
        responses = [ApiResponse(responseCode = "200", description = "삭제 성공")]
    )
    @DeleteMapping("/fcm-tokens")
    fun deleteFcmToken(
        @Parameter(hidden = true) @UserId userId: Long,
        @Valid @RequestBody request: DeleteDeviceTokenRequest
    ): DpmApiResponse<Unit> {
        deleteDeviceTokenService.execute(request)
        return DpmApiResponse.ok()
    }
}