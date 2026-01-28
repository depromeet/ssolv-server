package org.depromeet.team3.common.util

import io.github.oshai.kotlinlogging.KLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClientException
import kotlin.random.Random

object RetryUtil {

    suspend fun <T> retryWithExponentialBackoff(
        operation: String,
        logger: KLogger,
        maxRetries: Int = 3,
        initialDelayMillis: Long = 100L,
        maxDelayMillis: Long = 2000L,
        jitterMaxMillis: Long = 100L,
        operationDetail: String = "",
        block: suspend () -> T
    ): T {
        var lastException: Exception? = null
        var delayMillis = initialDelayMillis

        for (attempt in 0 until maxRetries) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpClientErrorException) {
                val statusCode = e.statusCode.value()
                if (statusCode in listOf(401, 403, 404)) {
                    throw e
                }
                if (statusCode == 429 || statusCode in 500..504) {
                    lastException = e
                    if (attempt < maxRetries - 1) {
                        val jitter = Random.nextLong(0, jitterMaxMillis)
                        val totalDelay = delayMillis + jitter
                        logger.warn(e) {
                            "$operation 재시도 (${attempt + 1}/${maxRetries - 1}) - 상태코드: $statusCode, $operationDetail, ${totalDelay}ms 후 재시도 (지터: ${jitter}ms)"
                        }
                        delay(totalDelay)
                        delayMillis = minOf(delayMillis * 2, maxDelayMillis)
                    }
                } else {
                    throw e
                }
            } catch (e: RestClientException) {
                lastException = e
                if (attempt < maxRetries - 1) {
                    val jitter = Random.nextLong(0, jitterMaxMillis)
                    val totalDelay = delayMillis + jitter
                    logger.warn(e) {
                        "$operation 재시도 (${attempt + 1}/${maxRetries - 1}) - 네트워크 오류: ${e.message}, $operationDetail, ${totalDelay}ms 후 재시도 (지터: ${jitter}ms)"
                    }
                    delay(totalDelay)
                    delayMillis = minOf(delayMillis * 2, maxDelayMillis)
                }
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries - 1) {
                    val jitter = Random.nextLong(0, jitterMaxMillis)
                    val totalDelay = delayMillis + jitter
                    logger.warn(e) {
                        "$operation 재시도 (${attempt + 1}/${maxRetries - 1}) - 예외: ${e.javaClass.simpleName}, $operationDetail, ${totalDelay}ms 후 재시도 (지터: ${jitter}ms)"
                    }
                    delay(totalDelay)
                    delayMillis = minOf(delayMillis * 2, maxDelayMillis)
                }
            }
        }

        logger.error(lastException) { "$operation 최종 실패 (${maxRetries - 1}회 재시도 후), $operationDetail" }
        throw lastException ?: Exception("$operation failed")
    }
}