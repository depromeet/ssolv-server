package org.depromeet.team3.common.util

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.extension.kotlin.asContextElement
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext

/**
 * MDC + OTel SpanContext를 코루틴 전환 시 함께 전파합니다.
 * withContext(Dispatchers.IO) 대신 이 함수를 사용하면
 * 로그의 request_id/user_id와 Tempo 트레이스가 스레드 전환 후에도 유지됩니다.
 */
suspend fun <T> withTracingContext(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    block: suspend CoroutineScope.() -> T
): T = withContext(dispatcher + MDCContext() + Context.current().asContextElement(), block)

/**
 * Redis Stream 메시지 페이로드의 traceparent/tracestate를 복원하여 부모 OTel Context를 반환합니다.
 * 컨슈머에서 scope.launch() 시 이 컨텍스트를 asContextElement()로 전달하면
 * HTTP 요청부터 비동기 처리까지 단일 트레이스로 연결됩니다.
 * traceparent가 없거나 비어있으면 Context.current()를 그대로 반환합니다.
 */
private val mapGetter = object : TextMapGetter<Map<String, String>> {
    override fun keys(carrier: Map<String, String>) = carrier.keys
    override fun get(carrier: Map<String, String>?, key: String) = carrier?.get(key)
}

fun extractParentContext(messagePayload: Map<String, String>): Context {
    val traceparent = messagePayload["traceparent"] ?: ""
    if (traceparent.isBlank()) return Context.current()
    return GlobalOpenTelemetry.getPropagators().textMapPropagator
        .extract(Context.current(), messagePayload, mapGetter)
}
