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

/**
 * Google Places API Rate Limit 초과(429) 시나리오 시뮬레이션
 *
 * [가정]
 * - 모임방 5개 동시 생성, 모임방당 키워드 6개 → 총 30개 API 요청
 * - Google API Rate Limit: 동시 20개 초과 시 429 발생
 * - 정상 응답 시간: 100ms
 */
class SemaphoreHighLoadTest {

    private val globalConcurrentCalls = AtomicInteger(0)
    private val peakConcurrentCalls = AtomicInteger(0)

    private fun updatePeak() {
        val current = globalConcurrentCalls.get()
        peakConcurrentCalls.updateAndGet { prev -> maxOf(prev, current) }
    }

    private suspend fun mockGooglePlacesApi(): Long {
        val current = globalConcurrentCalls.incrementAndGet()
        updatePeak()
        if (current > 20) {
            globalConcurrentCalls.decrementAndGet()
            throw RuntimeException("429 Too Many Requests")
        }
        delay(100L)
        globalConcurrentCalls.decrementAndGet()
        return 100L
    }

    @Test
    fun `세마포어 동작 테스트`() = runBlocking {
        val totalMeetings = 5
        val keywordsPerMeeting = 6
        val totalRequests = totalMeetings * keywordsPerMeeting

        // ── Before: Semaphore 미적용 ──────────────────────────────────
        globalConcurrentCalls.set(0)
        peakConcurrentCalls.set(0)
        var successBefore = 0
        var errorBefore = 0
        val startBefore = System.currentTimeMillis()

        (1..totalMeetings).map {
            async(Dispatchers.Default) {
                (1..keywordsPerMeeting).map {
                    async {
                        try {
                            mockGooglePlacesApi()
                            synchronized(this@SemaphoreHighLoadTest) { successBefore++ }
                        } catch (e: Exception) {
                            System.err.println("[ERROR] ${e.message}")
                            synchronized(this@SemaphoreHighLoadTest) { errorBefore++ }
                        }
                    }
                }.awaitAll()
            }
        }.awaitAll()

        val elapsedBefore = System.currentTimeMillis() - startBefore
        val peakBefore = peakConcurrentCalls.get()

        println(
            """
            |
            |[Before] Semaphore 미적용
            |  총 요청: ${totalRequests}건  성공: ${successBefore}건  실패(429): ${errorBefore}건
            |  최대 동시 호출: ${peakBefore}개  소요 시간: ${elapsedBefore}ms
            """.trimMargin(),
        )

        // ── After: 2-tier Semaphore (전역 15 + 모임별 4) ─────────────
        globalConcurrentCalls.set(0)
        peakConcurrentCalls.set(0)
        var successAfter = 0
        var errorAfter = 0
        val globalApiSemaphore = Semaphore(15)
        val startAfter = System.currentTimeMillis()

        (1..totalMeetings).map {
            async(Dispatchers.Default) {
                val requestSemaphore = Semaphore(4)
                (1..keywordsPerMeeting).map {
                    async {
                        globalApiSemaphore.withPermit {
                            requestSemaphore.withPermit {
                                try {
                                    mockGooglePlacesApi()
                                    synchronized(this@SemaphoreHighLoadTest) { successAfter++ }
                                } catch (e: Exception) {
                                    synchronized(this@SemaphoreHighLoadTest) { errorAfter++ }
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
        }.awaitAll()

        val elapsedAfter = System.currentTimeMillis() - startAfter
        val peakAfter = peakConcurrentCalls.get()

        println(
            """
            |
            |[After]  2-tier Semaphore (전역 15 + 모임별 4)
            |  총 요청: ${totalRequests}건  성공: ${successAfter}건  실패(429): ${errorAfter}건
            |  최대 동시 호출: ${peakAfter}개  소요 시간: ${elapsedAfter}ms
            """.trimMargin(),
        )
    }
}
