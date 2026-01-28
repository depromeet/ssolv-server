package org.depromeet.team3.config

import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager
import org.depromeet.team3.llm.properties.GeminiProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

@Configuration
@ConditionalOnProperty(prefix = "api.gemini", name = ["api-key"])
@EnableConfigurationProperties(GeminiProperties::class)
class GeminiRestClientConfiguration {

    private val logger = KotlinLogging.logger {}

    @Bean
    fun geminiRestClient(geminiProperties: GeminiProperties): RestClient {
        return RestClient.builder()
            .requestFactory(geminiHttpRequestFactory())
            .baseUrl(geminiProperties.baseUrl)
            .defaultHeaders { headers ->
                headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                headers.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            }
            .defaultStatusHandler({ it.is4xxClientError }) { _, response ->
                logger.error { "Gemini API client error. Status: ${response.statusCode}" }
            }
            .defaultStatusHandler({ it.is5xxServerError }) { _, response ->
                logger.error { "Gemini API server error. Status: ${response.statusCode}" }
            }
            .build()
    }

    @Bean
    fun geminiHttpRequestFactory(): ClientHttpRequestFactory {
        val httpClient = HttpClients.custom()
            .setConnectionManager(geminiConnectionManager())
            .build()

        return HttpComponentsClientHttpRequestFactory(httpClient).apply {
            setConnectTimeout(Duration.ofSeconds(10))
            setReadTimeout(Duration.ofSeconds(30))
            setConnectionRequestTimeout(Duration.ofSeconds(2))
        }
    }

    @Bean
    fun geminiConnectionManager(): PoolingHttpClientConnectionManager {
        return PoolingHttpClientConnectionManager().apply {
            maxTotal = 20
            defaultMaxPerRoute = 10
        }
    }
}