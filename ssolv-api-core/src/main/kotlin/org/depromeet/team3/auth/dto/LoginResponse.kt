package org.depromeet.team3.auth.dto

data class LoginResponse(val accessToken: String, val refreshToken: String, val userProfile: UserProfileResponse)
