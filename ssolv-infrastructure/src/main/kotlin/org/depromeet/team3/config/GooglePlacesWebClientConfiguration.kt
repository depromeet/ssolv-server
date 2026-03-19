package org.depromeet.team3.config

import io.netty.channel.ChannelOption
import org.depromeet.team3.common.GooglePlacesApiProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.time.Duration

@Configuration
class GooglePlacesWebClientConfiguration(
    private val googlePlacesApiProperties: GooglePlacesApiProperties
) {

    @Bean
    fun googlePlacesWebClient(builder: WebClient.Builder): WebClient {
        val httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
            .responseTimeout(Duration.ofSeconds(5))

        return builder
            .baseUrl(googlePlacesApiProperties.baseUrl)
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .build()
    }
}