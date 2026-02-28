package org.depromeet.team3.common.util

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import org.springframework.stereotype.Component
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Component
open class CoroutineDispatchers {
    /**
     * Virtual Thread 기반 Dispatcher
     * - Blocking I/O 구간 격리 목적
     */
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
    
    val VT: CoroutineDispatcher = executor.asCoroutineDispatcher()

    /**
     * 애플리케이션 종료 시 리소스 반납
     */
    @PreDestroy
    fun shutdown() {
        executor.shutdown()
    }
}