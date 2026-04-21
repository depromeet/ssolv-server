package org.depromeet.team3.config

import org.depromeet.team3.common.resolver.MeetingIdArgumentResolver
import org.depromeet.team3.common.resolver.UserIdArgumentResolver
import org.springframework.context.annotation.Configuration
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Web MVC 설정
 */
@Configuration
class WebMvcConfig(
    private val userIdArgumentResolver: UserIdArgumentResolver,
    private val meetingIdArgumentResolver: MeetingIdArgumentResolver,
) : WebMvcConfigurer {

    /**
     * 커스텀 ArgumentResolver 등록
     */
    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(userIdArgumentResolver)
        resolvers.add(meetingIdArgumentResolver)
    }
}
