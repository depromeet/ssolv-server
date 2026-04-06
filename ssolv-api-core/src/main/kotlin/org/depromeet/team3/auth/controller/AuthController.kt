package org.depromeet.team3.auth.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.depromeet.team3.auth.application.login.AppleOAuthService
import org.depromeet.team3.auth.application.login.DemoLoginService
import org.depromeet.team3.auth.application.login.KakaoLoginService
import org.depromeet.team3.auth.application.token.UpdateTokenService
import org.depromeet.team3.auth.application.common.LogoutService
import org.depromeet.team3.auth.application.common.WithdrawService
import org.depromeet.team3.auth.command.AppleLoginCommand
import org.depromeet.team3.auth.command.KakaoLoginCommand
import org.depromeet.team3.auth.command.RefreshTokenCommand
import org.depromeet.team3.auth.dto.LoginResponse
import org.depromeet.team3.auth.dto.LogoutResponse
import org.depromeet.team3.auth.dto.RefreshTokenRequest
import org.depromeet.team3.auth.dto.TokenResponse
import org.depromeet.team3.common.ContextConstants
import org.depromeet.team3.common.annotation.UserId
import org.depromeet.team3.common.response.DpmApiResponse
import org.springframework.web.bind.annotation.*

@Tag(name = "로그인/회원가입", description = "사용자 로그인 관련 API")
@RestController
@RequestMapping("${ContextConstants.API_VERSION_V1}/auth")
class AuthController(
    private val kakaoLoginService: KakaoLoginService,
    private val appleOAuthService: AppleOAuthService,
    private val demoLoginService: DemoLoginService,
    private val updateTokenService: UpdateTokenService,
    private val logoutService: LogoutService,
    private val withdrawService: WithdrawService
) {
    @Operation(
        summary = "카카오 소셜 로그인 API",
        description = "카카오 OAuth 인가코드로 로그인을 처리합니다. 성공 시 응답 바디에 accessToken, refreshToken, 사용자 프로필 정보를 반환합니다."
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "로그인 성공"),
        ApiResponse(responseCode = "400", description = "잘못된 요청"),
        ApiResponse(responseCode = "401", description = "카카오 인증 실패 (O001, O002)"),
        ApiResponse(responseCode = "409", description = "다른 소셜 로그인으로 이미 가입된 이메일 (O007)"),
        ApiResponse(responseCode = "500", description = "서버 내부 오류")
    )
    @GetMapping("/kakao-login")
    suspend fun kakaoLogin(
        @Parameter(
            description = "카카오 OAuth 인가코드",
            required = true,
        )
        @RequestParam("code") code: String,
        
        @Parameter(
            description = "리다이렉트 URI",
            required = true,
            schema = Schema(
                type = "string",
                allowableValues = [
                    "http://localhost:3000/auth/callback",
                    "http://localhost:8080/auth/callback",
                    "https://www.ssolv.site/auth/callback",
                    "https://api.ssolv.site/auth/callback",
                    "https://ec01-58-29-179-24.ngrok-free.app/auth/callback"
                ]
            )
        )
        @RequestParam(value = "redirect_uri", required = false) redirectUri: String?
    ): DpmApiResponse<LoginResponse> {
        val command = KakaoLoginCommand(authorizationCode = code, redirectUri = redirectUri)
        val result = kakaoLoginService.login(command)
        return DpmApiResponse.ok(result)
    }

    @Operation(
        summary = "애플 소셜 로그인 API",
        description = "애플 OAuth 인가코드로 로그인을 처리합니다. user 파라미터는 최초 로그인 시 애플이 제공하는 사용자 정보(JSON)를 포함해야 합니다."
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "로그인 성공"),
        ApiResponse(responseCode = "400", description = "잘못된 요청"),
        ApiResponse(responseCode = "401", description = "애플 인증 실패 (O011, O012, O018, O019)"),
        ApiResponse(responseCode = "409", description = "다른 소셜 로그인으로 이미 가입된 이메일 (O007)"),
        ApiResponse(responseCode = "500", description = "서버 내부 오류")
    )
    @PostMapping("/apple-login")
    suspend fun appleLogin(
        @Parameter(description = "애플 OAuth 인가코드", required = true)
        @RequestParam("code") code: String,
        
        @Parameter(
            description = "리다이렉트 URI",
            required = false,
            schema = Schema(
                type = "string",
                allowableValues = [
                    "https://www.ssolv.site/auth/callback",
                    "https://api.ssolv.site/auth/callback",
                    "https://ec01-58-29-179-24.ngrok-free.app/auth/callback"
                ]
            )
        )
        @RequestParam(value = "redirect_uri", required = false) redirectUri: String?,

        @Parameter(
            description = "최초 로그인 시 제공되는 사용자 정보 (JSON 문자열). 이름(firstName, lastName) 추출을 위해 필요합니다.",
            required = false
        )
        @RequestParam(value = "user", required = false) user: String?
    ): DpmApiResponse<LoginResponse> {
        val command = AppleLoginCommand(authorizationCode = code, redirectUri = redirectUri, user = user)
        val result = appleOAuthService.login(command)
        return DpmApiResponse.ok(result)
    }

    @Operation(
        summary = "앱스토어 심사용 데모 로그인 API",
        description = "소셜 로그인 없이 데모 계정으로 즉시 로그인합니다. 앱스토어 심사 전용 엔드포인트입니다."
    )
    @ApiResponse(responseCode = "200", description = "로그인 성공")
    @PostMapping("/demo-login")
    suspend fun demoLogin(): DpmApiResponse<LoginResponse> {
        val result = demoLoginService.login()
        return DpmApiResponse.ok(result)
    }

    @Operation(
        summary = "토큰 갱신 API",
        description = "refreshToken을 사용하여 만료된 accessToken을 갱신합니다. " +
                "요청 바디에 refreshToken을 포함하여 호출하면 새로운 accessToken과 refreshToken을 반환합니다."
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "토큰 갱신 성공"),
        ApiResponse(responseCode = "401", description = "토큰 갱신 실패"),
        ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음"),
        ApiResponse(responseCode = "500", description = "서버 내부 오류")
    )
    @PostMapping("/reissue-token")
    suspend fun refreshToken(
        @RequestBody request: RefreshTokenRequest
    ): DpmApiResponse<TokenResponse> {
        val command = RefreshTokenCommand(request.refreshToken)
        val result = updateTokenService.refresh(command)
        return DpmApiResponse.ok(result)
    }

    @Operation(
        summary = "로그아웃 API",
        description = "리프레시 토큰을 무효화합니다. 카카오 로그인 사용자는 응답의 kakaoLogoutUrl로 이동하면 카카오 계정도 로그아웃됩니다."
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "로그아웃 성공"),
        ApiResponse(responseCode = "401", description = "인증 실패")
    )
    @PostMapping("/logout")
    suspend fun logout(@UserId userId: Long): DpmApiResponse<LogoutResponse> {
        val result = logoutService.logout(userId)
        return DpmApiResponse.ok(result)
    }

    @Operation(
        summary = "회원 탈퇴 API",
        description = "현재 로그인된 사용자의 계정을 삭제하고 소셜 연동을 해제합니다. 이 작업은 되돌릴 수 없습니다."
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "탈퇴 성공"),
        ApiResponse(responseCode = "401", description = "인증 실패"),
        ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음")
    )
    @DeleteMapping("/withdraw")
    suspend fun withdraw(@UserId userId: Long): DpmApiResponse<Unit> {
        withdrawService.withdraw(userId)
        return DpmApiResponse.ok()
    }
}
