package org.depromeet.team3.config

import org.depromeet.team3.common.resolver.MeetingIdArgumentResolver
import org.depromeet.team3.common.resolver.UserIdArgumentResolver
import org.springframework.context.annotation.Configuration
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Web MVC 설정
 */
@Configuration
class WebMvcConfig(
    private val userIdArgumentResolver: UserIdArgumentResolver,
    private val meetingIdArgumentResolver: MeetingIdArgumentResolver
) : WebMvcConfigurer {

    /**
     * 커스텀 ArgumentResolver 등록
     */
    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(userIdArgumentResolver)
        resolvers.add(meetingIdArgumentResolver)
    }

    /**
     * CORS 설정
     * 로컬 환경에서 Core API (8080)에서 Place API (8081)의 Swagger 문서를 가져올 수 있도록 허용
     */
    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/**")
            .allowedOrigins(
                "http://localhost:8080",
                "http://localhost:8081",
                "https://api.ssolv.site",
                "https://ssolv.site"
            )
            .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
            .allowedHeaders("*")
            .allowCredentials(true)
    }
}
