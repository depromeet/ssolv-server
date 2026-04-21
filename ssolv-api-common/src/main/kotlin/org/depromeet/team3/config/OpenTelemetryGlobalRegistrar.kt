package org.depromeet.team3.config

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.OpenTelemetry
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration

/**
 * Spring Boot 3 + micrometer-tracing-bridge-otel 는 OpenTelemetry bean 을 자동 구성하지만
 * GlobalOpenTelemetry 에는 등록하지 않는다. 이 경우 GlobalOpenTelemetry.getTracer(...) 는
 * no-op tracer 를 반환하고, 수동 span 의 traceId/spanId 가 모두 0 으로 찍혀 OTLP 단계에서 drop 된다.
 *
 * 여기서 Spring 이 구성한 OpenTelemetry 를 한 번 전역에 등록해, 기존 코드의
 * GlobalOpenTelemetry.getTracer / getPropagators 호출이 실제 SDK 로 연결되도록 한다.
 */
@Configuration
class OpenTelemetryGlobalRegistrar(private val openTelemetry: OpenTelemetry) {
    private val logger = LoggerFactory.getLogger(OpenTelemetryGlobalRegistrar::class.java)

    @PostConstruct
    fun register() {
        try {
            GlobalOpenTelemetry.set(openTelemetry)
            logger.info("Registered Spring-managed OpenTelemetry as GlobalOpenTelemetry: {}", openTelemetry::class.java.name)
        } catch (e: IllegalStateException) {
            logger.warn("GlobalOpenTelemetry already set — skipping registration: {}", e.message)
        }
    }
}
