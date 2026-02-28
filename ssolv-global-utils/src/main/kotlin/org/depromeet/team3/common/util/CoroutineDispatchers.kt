package org.depromeet.team3.common.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

object CoroutineDispatchers {
    /**
     * Virtual Thread 기반 Dispatcher
     *
     * - Blocking I/O 구간 격리 목적
     * - 기존 동기 코드 유지
     * - 스레드 점유 비용 최소화
     */
    val VT: CoroutineDispatcher by lazy {
        Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()
    }
}