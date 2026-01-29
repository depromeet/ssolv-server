package org.depromeet.team3.common.resolver

import org.depromeet.team3.common.annotation.MeetingId
import org.depromeet.team3.util.MeetingIdParser
import org.springframework.core.MethodParameter
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.servlet.HandlerMapping

@Component
class MeetingIdArgumentResolver : HandlerMethodArgumentResolver {

    override fun supportsParameter(parameter: MethodParameter): Boolean {
        return parameter.hasParameterAnnotation(MeetingId::class.java) &&
                (parameter.parameterType == Long::class.javaObjectType || 
                 parameter.parameterType == Long::class.java)
    }

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Long? {
        val annotation = parameter.getParameterAnnotation(MeetingId::class.java)!!
        val name = annotation.value.ifBlank { parameter.parameterName } ?: return null
        
        // 1. PathVariables에서 찾기
        val pathVariables = webRequest.getAttribute(
            HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
            NativeWebRequest.SCOPE_REQUEST
        ) as? Map<*, *>
        
        val value = pathVariables?.get(name)?.toString() 
            // 2. RequestParams에서 찾기 (Path에 없으면)
            ?: webRequest.getParameter(name)
            ?: return null

        return MeetingIdParser.parse(value)
    }
}