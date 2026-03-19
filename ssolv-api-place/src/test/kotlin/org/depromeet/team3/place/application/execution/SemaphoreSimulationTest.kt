package org.depromeet.team3.place.application.execution

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class SemaphoreSimulationTest {

    @Test
    fun simulateWithoutSemaphore() = runBlocking {
        val concurrentRequests = AtomicInteger(0)
        var maxConcurrent = 0

        val timeStart = System.currentTimeMillis()
        // 1번의 요청(모임)당 검색되어야 할 키워드가 6개라고 가정 (기본 5개 + Fallback 1개)
        val keywords = listOf("한식 맛집", "일식 맛집", "중식 맛집", "양식 맛집", "강남역 맛집", "Fallback 맛집") 

        keywords.map { 
            async(Dispatchers.Default) {
                val current = concurrentRequests.incrementAndGet()
                synchronized(this@SemaphoreSimulationTest) {
                    if (current > maxConcurrent) maxConcurrent = current
                }
                
                // Google API Latency 모킹 (100ms 지연)
                delay(100) 
                
                concurrentRequests.decrementAndGet()
            }
        }.awaitAll()

        println("=== ❌ 적용 전 (Semaphore 없음) ===")
        println("발생한 동시 API 호출 수: $maxConcurrent 개")
        println("총 소요 시간: ${System.currentTimeMillis() - timeStart}ms")
    }

    @Test
    fun simulateWithSemaphore() = runBlocking {
        val semaphore = Semaphore(4) // 요청당 최대 4개로 제한
        val concurrentRequests = AtomicInteger(0)
        var maxConcurrent = 0

        val timeStart = System.currentTimeMillis()
        val keywords = listOf("한식 맛집", "일식 맛집", "중식 맛집", "양식 맛집", "강남역 맛집", "Fallback 맛집")

        keywords.map { 
            async(Dispatchers.Default) {
                semaphore.withPermit {
                    val current = concurrentRequests.incrementAndGet()
                    synchronized(this@SemaphoreSimulationTest) {
                        if (current > maxConcurrent) maxConcurrent = current
                    }
                    
                    // Google API Latency 모킹 (100ms 지연)
                    delay(100) 
                    
                    concurrentRequests.decrementAndGet()
                }
            }
        }.awaitAll()

        println("=== ✅ 적용 후 (Semaphore 4 제한) ===")
        println("발생한 동시 API 호출 수: $maxConcurrent 개")
        println("총 소요 시간: ${System.currentTimeMillis() - timeStart}ms")
    }
}