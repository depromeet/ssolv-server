package org.depromeet.team3.place.application.execution

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import kotlin.system.measureTimeMillis

class CoroutineConcurrencyVsParallelismTest {

    private val singleDispatcher = Executors
        .newSingleThreadExecutor()
        .asCoroutineDispatcher()

    @Test
    fun `병행 처리 - 단일 스레드에서 컨텍스트 스위칭`() {
        runBlocking {
            val time = measureTimeMillis {
                withContext(singleDispatcher) {
                    coroutineScope {
                        async { heavyWork("A") }
                        async { heavyWork("B") }
                        async { heavyWork("C") }
                    }
                }
            }
            println("single-thread time = $time ms")
        }
    }

    @Test
    fun `병렬 처리 - Dispatchers Default를 사용한 멀티 스레드 실행`() {
        runBlocking {
            val time = measureTimeMillis {
                coroutineScope {
                    async(Dispatchers.Default) { heavyWork("A") }
                    async(Dispatchers.Default) { heavyWork("B") }
                    async(Dispatchers.Default) { heavyWork("C") }
                }
            }
            println("multi-thread time = $time ms")
        }
    }

    private suspend fun heavyWork(name: String) {
        println("[$name] 시작 - ${Thread.currentThread().name}")
        var sum = 0L
        for (i in 1..1_000_000_00) {
            sum += i
        }
        println("[$name] 끝   - ${Thread.currentThread().name}")
    }
}
