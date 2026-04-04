package org.depromeet.team3.common.util

import io.opentelemetry.context.Context
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
