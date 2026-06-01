package org.depromeet.team3.place.application.execution

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

class GooglePlacesBurstControlTest {

    private val inFlightCalls = AtomicInteger(0)
    private val peakInFlightCalls = AtomicInteger(0)

    @Test
    @DisplayName("병렬 호출 수를 제한하지 않으면 요청 집중으로 응답 지연과 실패가 함께 증가한다")
    fun unboundedFanOutIncreasesLatencyAndFailuresUnderGooglePlacesBurst() {
        runBlocking {
            val totalMeetings = 5
            val keywordsPerMeeting = 6
            val totalCalls = totalMeetings * keywordsPerMeeting

            val withoutLimit = runScenario(
                name = "without-limit",
                totalMeetings = totalMeetings,
                keywordsPerMeeting = keywordsPerMeeting,
                limiter = NoopLimiter,
            )

            val withLimit = runScenario(
                name = "with-limit",
                totalMeetings = totalMeetings,
                keywordsPerMeeting = keywordsPerMeeting,
                limiter = SemaphoreLimiter(globalLimit = 15, requestLimit = 4),
            )

            printReport(totalCalls, withoutLimit, withLimit)

            assertThat(withoutLimit.peakConcurrency).isGreaterThan(20)
            assertThat(withoutLimit.failureCount).isGreaterThan(0)
            assertThat(withoutLimit.p95LatencyMs).isGreaterThan(withLimit.p95LatencyMs)

            assertThat(withLimit.peakConcurrency).isLessThanOrEqualTo(15)
            assertThat(withLimit.failureCount).isZero()
        }
    }

    private suspend fun runScenario(
        name: String,
        totalMeetings: Int,
        keywordsPerMeeting: Int,
        limiter: GoogleCallLimiter,
    ): ScenarioResult {
        resetCounters()

        val records = Collections.synchronizedList(mutableListOf<CallRecord>())
        val startedAt = System.currentTimeMillis()

        coroutineScope {
            (1..totalMeetings).map { meetingNo ->
                async(Dispatchers.Default) {
                    (1..keywordsPerMeeting).map { keywordNo ->
                        async {
                            limiter.execute(meetingNo) {
                                records.add(mockGooglePlacesCall(meetingNo, keywordNo))
                            }
                        }
                    }.awaitAll()
                }
            }.awaitAll()
        }

        val elapsedMs = System.currentTimeMillis() - startedAt
        val snapshot = records.toList()

        return ScenarioResult(
            name = name,
            successCount = snapshot.count { it.success },
            failureCount = snapshot.count { !it.success },
            peakConcurrency = peakInFlightCalls.get(),
            avgLatencyMs = snapshot.map { it.latencyMs }.average(),
            p95LatencyMs = percentile(snapshot.map { it.latencyMs }, 95),
            elapsedMs = elapsedMs,
        )
    }

    private suspend fun mockGooglePlacesCall(meetingNo: Int, keywordNo: Int): CallRecord {
        val startedAt = System.nanoTime()
        val current = inFlightCalls.incrementAndGet()
        peakInFlightCalls.updateAndGet { previous -> maxOf(previous, current) }

        return try {
            val overload = (current - SOFT_CONCURRENCY_LIMIT).coerceAtLeast(0)
            val latencyMs = BASE_LATENCY_MS + (overload * LATENCY_PENALTY_MS)
            delay(latencyMs)

            val success = current <= HARD_CONCURRENCY_LIMIT
            CallRecord(
                meetingNo = meetingNo,
                keywordNo = keywordNo,
                success = success,
                latencyMs = elapsedMillis(startedAt),
            )
        } finally {
            inFlightCalls.decrementAndGet()
        }
    }

    private fun resetCounters() {
        inFlightCalls.set(0)
        peakInFlightCalls.set(0)
    }

    private fun printReport(totalCalls: Int, withoutLimit: ScenarioResult, withLimit: ScenarioResult) {
        println(
            """
            |
            |Google Places burst control simulation
            |Total simulated calls: $totalCalls
            |
            |Scenario           Success  Failure  Peak concurrency  Avg latency  P95 latency  Elapsed
            |${withoutLimit.toReportRow()}
            |${withLimit.toReportRow()}
            |
            |Interpretation:
            |Without a concurrency limit, simultaneous Google Places calls exceeded the simulated safe threshold.
            |The burst increased response latency and produced rate-limit failures before all calls completed.
            |With request/global limits, peak concurrency stayed within the safe threshold and all calls completed successfully.
            """.trimMargin(),
        )
    }

    private fun ScenarioResult.toReportRow(): String = "%-18s %7d %8d %17d %10.1fms %10dms %8dms".format(
        name,
        successCount,
        failureCount,
        peakConcurrency,
        avgLatencyMs,
        p95LatencyMs,
        elapsedMs,
    )

    private fun percentile(values: List<Long>, percentile: Int): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val index = ceil((percentile / 100.0) * sorted.size).toInt().coerceIn(1, sorted.size) - 1
        return sorted[index]
    }

    private fun elapsedMillis(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000

    private interface GoogleCallLimiter {
        suspend fun execute(meetingNo: Int, block: suspend () -> Unit)
    }

    private object NoopLimiter : GoogleCallLimiter {
        override suspend fun execute(meetingNo: Int, block: suspend () -> Unit) {
            block()
        }
    }

    private class SemaphoreLimiter(globalLimit: Int, private val requestLimit: Int) : GoogleCallLimiter {
        private val globalSemaphore = Semaphore(globalLimit)
        private val requestSemaphores = java.util.concurrent.ConcurrentHashMap<Int, Semaphore>()

        override suspend fun execute(meetingNo: Int, block: suspend () -> Unit) {
            val requestSemaphore = requestSemaphores.computeIfAbsent(meetingNo) { Semaphore(requestLimit) }
            requestSemaphore.withPermit {
                globalSemaphore.withPermit {
                    block()
                }
            }
        }
    }

    private data class CallRecord(val meetingNo: Int, val keywordNo: Int, val success: Boolean, val latencyMs: Long)

    private data class ScenarioResult(
        val name: String,
        val successCount: Int,
        val failureCount: Int,
        val peakConcurrency: Int,
        val avgLatencyMs: Double,
        val p95LatencyMs: Long,
        val elapsedMs: Long,
    )

    private companion object {
        private const val SOFT_CONCURRENCY_LIMIT = 15
        private const val HARD_CONCURRENCY_LIMIT = 20
        private const val BASE_LATENCY_MS = 120L
        private const val LATENCY_PENALTY_MS = 35L
    }
}
