package org.depromeet.team3.config

import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager
import org.depromeet.team3.common.GooglePlacesApiProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

@Configuration
@EnableConfigurationProperties(GooglePlacesApiProperties::class)
class GooglePlacesRestClientConfiguration(
    private val properties: GooglePlacesApiProperties
) {

    private val logger = KotlinLogging.logger { GooglePlacesRestClientConfiguration::class.java.name }

    companion object {
        const val MAX_CONNECTION_TOTAL = 10
        const val MAX_PER_ROUTE = 5
    }

    @Bean
    fun googlePlacesRestClient(): RestClient {
        return RestClient.builder()
            .requestFactory(googlePlacesHttpRequestFactory())
            .baseUrl(properties.baseUrl)
            .defaultHeaders { headers ->
                headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                headers.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            }
            .defaultStatusHandler(HttpStatusCode::is4xxClientError) { request, response ->
                logger.error {
                    "Google Places API client error. " +
                        "Status: ${response.statusCode}, " +
                        "URL: ${request.uri}, " +
                        "Method: ${request.method}"
                }
            }
            .defaultStatusHandler(HttpStatusCode::is5xxServerError) { request, response ->
                logger.error {
                    "Google Places API server error. " +
                        "Status: ${response.statusCode}, " +
                        "URL: ${request.uri}, " +
                        "Method: ${request.method}"
                }
            }
            .build()
    }

    @Bean
    fun googlePlacesHttpRequestFactory(): ClientHttpRequestFactory {
        val httpClient = HttpClients.custom()
            .setConnectionManager(googlePlacesConnectionManager())
            .build()

        return HttpComponentsClientHttpRequestFactory(httpClient).apply {
            setConnectTimeout(Duration.ofSeconds(5))
            setReadTimeout(Duration.ofSeconds(5))
            setConnectionRequestTimeout(Duration.ofSeconds(1))
        }
    }

    @Bean
    fun googlePlacesConnectionManager(): PoolingHttpClientConnectionManager {
        val manager = PoolingHttpClientConnectionManager()
        manager.maxTotal = MAX_CONNECTION_TOTAL
        manager.defaultMaxPerRoute = MAX_PER_ROUTE

        return manager
    }
}
