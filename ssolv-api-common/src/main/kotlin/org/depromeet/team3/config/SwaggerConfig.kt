package org.depromeet.team3.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class SwaggerConfig : WebMvcConfigurer {

    @Bean
    fun customOpenAPI(): OpenAPI = OpenAPI()
        .addServersItem(Server().url("/"))
        .info(apiInfo())
        .components(securityComponents())
        .addSecurityItem(SecurityRequirement().addList("Bearer Authentication"))

    private fun apiInfo(): Info = Info()
        .title("디프만 3팀 Swagger")
        .description("디프만 3팀의 Swagger API 문서입니다.")
        .version("1.0")

    private fun securityComponents(): Components = Components()
        .addSecuritySchemes(
            "Bearer Authentication",
            SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .`in`(SecurityScheme.In.HEADER)
                .name("Authorization")
                .description("JWT Access Token 을 입력하세요 (Bearer 제외)"),
        )

    override fun configureContentNegotiation(configurer: ContentNegotiationConfigurer) {
        configurer
            .favorParameter(false)
            .ignoreAcceptHeader(false)
            .defaultContentType(MediaType.APPLICATION_JSON)
            .mediaType("json", MediaType.APPLICATION_JSON)
    }
}
