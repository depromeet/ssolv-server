package org.depromeet.team3.common.exception

/**
 * 애플리케이션 전역에서 사용하는 에러 코드 정의
 */
enum class ErrorCode(
    val code: String,
    val message: String,
    val httpStatus: Int
) {
    // 4xx Client Errors
    INVALID_REQUEST("C001", "잘못된 요청입니다.", 400),
    INVALID_PARAMETER("C002", "유효하지 않은 파라미터입니다.", 400),
    MISSING_PARAMETER("C003", "필수 파라미터가 누락되었습니다.", 400),
    INVALID_JSON("C004", "잘못된 JSON 형식입니다.", 400),
    USER_ID_REQUIRED("C005", "사용자 ID가 필요합니다.", 400),
    INVALID_END_TIME("C006", "모임 종료 시간이 현재 시간보다 이전일 수 없습니다. 종료 시간을 다시 확인해주세요.", 400),
    MEETING_ALREADY_CLOSED("C4097", "이미 종료된 모임입니다.", 400),

    // 404 Not Found
    RESOURCE_NOT_FOUND("C404", "요청한 리소스를 찾을 수 없습니다.", 404),
    CATEGORY_NOT_FOUND("C4041", "카테고리를 찾을 수 없습니다.", 404),
    PARENT_CATEGORY_NOT_FOUND("C4042", "부모 카테고리를 찾을 수 없습니다.", 404),
    MEETING_NOT_FOUND("C4043", "모임을 찾을 수 없습니다.", 404),
    PARTICIPANT_NOT_FOUND("C4044", "참가자를 찾을 수 없습니다.", 404),
    SURVEY_NOT_FOUND("C4045", "설문을 찾을 수 없습니다.", 404),
    SURVEY_RESULT_NOT_FOUND("C4046", "설문 결과를 찾을 수 없습니다.", 404),
    SURVEY_CATEGORY_NOT_FOUND("C4047", "설문 카테고리가 존재하지 않습니다.", 404),
    SURVEY_BRANCH_CATEGORY_REQUIRED("C4048", "LEAF 카테고리를 선택한 경우 해당 BRANCH 카테고리도 반드시 선택해야 합니다.", 400),
    MEETING_PLACE_NOT_FOUND("C4049", "해당 모임에 추천되지 않은 맛집입니다.", 404),
    PLACE_NOT_FOUND("C4050", "맛집 정보를 찾을 수 없습니다.", 404),
    USER_NOT_FOUND("C4051", "사용자를 찾을 수 없습니다.", 404),
    STATION_NOT_FOUND("C4052", "역 정보를 찾을 수 없습니다.", 404),
    MENU_NOT_FOUND("C4053", "메뉴를 찾을 수 없습니다.", 404),
    SURVEY_LEAF_CATEGORY_LIMIT_EXCEEDED("C4055", "LEAF 카테고리는 최대 5개까지 선택할 수 있습니다.", 400),

    // 409 Conflict
    RESOURCE_CONFLICT("C409", "리소스 충돌이 발생했습니다.", 409),
    DUPLICATE_RESOURCE("C4091", "중복된 리소스입니다.", 409),
    CATEGORY_HAS_CHILDREN("C4092", "하위 카테고리가 존재하여 삭제할 수 없습니다.", 409),
    INVALID_CATEGORY_LEVEL_CHANGE("C4093", "자식 카테고리가 있는 BRANCH 카테고리를 LEAF로 변경할 수 없습니다.", 409),
    DUPLICATE_CATEGORY_NAME("C4094", "같은 부모 하위에 동일한 이름의 카테고리가 이미 존재합니다.", 409),
    DUPLICATE_CATEGORY_ORDER("C4095", "같은 부모 하위에 동일한 순서의 카테고리가 이미 존재합니다.", 409),
    SURVEY_ALREADY_SUBMITTED("C4096", "이미 설문을 제출했습니다.", 409),
    MEETING_ALREADY_JOINED("C4098", "이미 참가한 모임입니다.", 409),
    MEETING_FULL("C4099", "모임 인원이 가득 찼습니다.", 409),
    DUPLICATE_NICKNAME("C4100", "이미 사용 중인 닉네임입니다.", 409),

    // 5xx Server Errors
    INTERNAL_SERVER_ERROR("S001", "서버 내부 오류가 발생했습니다.", 500),
    DATABASE_ERROR("S002", "데이터베이스 오류가 발생했습니다.", 500),
    EXTERNAL_API_ERROR("S003", "외부 API 호출 중 오류가 발생했습니다.", 500),

    // OAuth 관련 에러 (O001~O099)
    KAKAO_INVALID_GRANT("O001", "카카오 인증 코드가 유효하지 않습니다.", 401),
    KAKAO_AUTH_FAILED("O002", "카카오 인증에 실패했습니다.", 401),
    KAKAO_JSON_PARSE_ERROR("O003", "카카오 응답 데이터 파싱에 실패했습니다.", 500),
    KAKAO_API_ERROR("O004", "카카오 API 호출 중 오류가 발생했습니다.", 500),
    KAKAO_RATE_LIMIT_EXCEEDED("O005", "카카오 API 요청 한도를 초과했습니다. 잠시 후 다시 시도해주세요.", 429),
    SOCIAL_LOGIN_INVALID_STATE("O006", "OAuth state 값이 유효하지 않습니다.", 400),
    ALREADY_REGISTERED_WITH_OTHER_LOGIN("O007", "다른 소셜 로그인으로 이미 가입된 이메일입니다.", 409),
    KAKAO_INVALID_REDIRECT_URI("O008", "허용되지 않은 redirect_uri입니다.", 400),
    KAKAO_PROFILE_REQUEST_FAILED("O009", "카카오 프로필 정보 요청에 실패했습니다.", 500),
    KAKAO_REDIRECT_URI_NOT_CONFIGURED("O010", "카카오 OAuth redirect URI가 설정되지 않았습니다.", 500),

    // Apple OAuth 관련 에러 (O011~O020)
    APPLE_INVALID_GRANT("O011", "애플 인증 코드가 유효하지 않습니다.", 401),
    APPLE_AUTH_FAILED("O012", "애플 인증에 실패했습니다.", 401),
    APPLE_JSON_PARSE_ERROR("O013", "애플 응답 데이터 파싱에 실패했습니다.", 500),
    APPLE_API_ERROR("O014", "애플 API 호출 중 오류가 발생했습니다.", 500),
    APPLE_INVALID_REDIRECT_URI("O015", "허용되지 않은 redirect_uri입니다.", 400),
    APPLE_PROFILE_REQUEST_FAILED("O016", "애플 프로필 정보 요청에 실패했습니다.", 500),
    APPLE_REDIRECT_URI_NOT_CONFIGURED("O017", "애플 OAuth redirect URI가 설정되지 않았습니다.", 500),
    APPLE_INVALID_ID_TOKEN("O018", "유효하지 않은 애플 ID 토큰입니다.", 401),
    APPLE_TOKEN_VERIFICATION_FAILED("O019", "애플 토큰 검증에 실패했습니다.", 401),

    // JWT 토큰 관련 에러 (J001~J099)
    JWT_TOKEN_MISSING("J001", "JWT 토큰이 누락되었습니다.", 401),
    JWT_TOKEN_INVALID("J002", "유효하지 않은 JWT 토큰입니다.", 401),
    JWT_TOKEN_EXPIRED("J003", "만료된 JWT 토큰입니다.", 401),
    JWT_TOKEN_MALFORMED("J004", "잘못된 형식의 JWT 토큰입니다.", 401),
    JWT_SIGNATURE_INVALID("J005", "JWT 서명이 유효하지 않습니다.", 401),
    JWT_TOKEN_UNSUPPORTED("J006", "지원하지 않는 JWT 토큰입니다.", 401),
    ACCESS_TOKEN_INVALID("J007", "유효하지 않은 Access Token입니다.", 401),
    REFRESH_TOKEN_INVALID("J008", "유효하지 않은 Refresh Token입니다.", 401),
    REFRESH_TOKEN_EXPIRED("J009", "만료된 Refresh Token입니다.", 401),
    REFRESH_TOKEN_MISMATCH("J010", "Refresh Token이 일치하지 않습니다.", 401),
    USER_NOT_FOUND_FOR_TOKEN("J011", "토큰에 해당하는 사용자를 찾을 수 없습니다.", 404),
    TOKEN_USER_ID_INVALID("J012", "토큰의 사용자 ID가 유효하지 않습니다.", 401),

    // 초대 토큰 관련 에러 (T001~T099)
    INVALID_INVITE_TOKEN("T001", "유효하지 않은 초대 토큰입니다.", 400),
    INVALID_TOKEN_FORMAT("T002", "토큰 형식이 올바르지 않습니다.", 400),
    INVALID_MEETING_ID_IN_TOKEN("T003", "토큰의 모임 ID 형식이 올바르지 않습니다.", 400),
    INVALID_EXPIRY_TIME_IN_TOKEN("T004", "토큰의 만료 시간 형식이 올바르지 않습니다.", 400),
    TOKEN_EXPIRED("T005", "만료된 초대 토큰입니다.", 400),
    MEETING_NOT_FOUND_FOR_TOKEN("T006", "토큰에 해당하는 모임을 찾을 수 없습니다.", 404),

    // Place 관련 에러 (P001~P099)
    PLACE_SEARCH_FAILED("P001", "맛집 검색 중 오류가 발생했습니다.", 500),
    PLACE_API_ERROR("P002", "Google Places API 호출 중 오류가 발생했습니다.", 500),
    PLACE_API_RESPONSE_NULL("P003", "Google Places API 응답이 없습니다.", 500),
    PLACE_DETAILS_NOT_FOUND("P004", "장소를 찾을 수 없습니다.", 404),
    PLACE_DETAILS_FETCH_FAILED("P005", "장소 상세 정보 조회에 실패했습니다.", 500),
    PLACE_NEARBY_SEARCH_FAILED("P006", "주변 장소 검색에 실패했습니다.", 500),
    PLACE_INVALID_QUERY("P007", "유효하지 않은 검색어입니다.", 400),
    PLACE_API_KEY_INVALID("P008", "Google Places API 키가 유효하지 않습니다.", 500),
    PLACE_API_QUOTA_EXCEEDED("P009", "Google Places API 할당량을 초과했습니다.", 429)
}
