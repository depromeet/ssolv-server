package org.depromeet.team3.batch.scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

/**
 * 코루틴 기반 Watchdog 패턴이 적용된 분산 락 매니저
 *
 * - 실제 작업이 실행되는 동안 주기적으로 락의 TTL을 연장(Safe Extend)합니다.
 * - 작업이 끝나면 즉시 안전하게 락을 해제(Safe Release)하여 처리량을 극대화합니다.
 */
@Component
class CoroutineWatchdogManager(
    private val stringRedisTemplate: StringRedisTemplate
) {
    private val logger = LoggerFactory.getLogger(CoroutineWatchdogManager::class.java)
    
    companion object {
        private const val MIN_TTL_MILLIS = 5000L
        private const val MAX_CONSECUTIVE_FAILURES = 3
    }

    // 1. 안전한 해제를 위한 루아 스크립트 (내가 잡은 락일 때만 해제)
    private val unlockScript = DefaultRedisScript(
        """
        if redis.call("get", KEYS[1]) == ARGV[1] then
            return redis.call("del", KEYS[1])
        else
            return 0
        end
        """.trimIndent(),
        Long::class.java
    )

    // 2. 안전한 연장을 위한 루아 스크립트 (내가 잡은 락일 때만 연장)
    // pexpire는 밀리초 단위로 TTL을 설정합니다.
    private val extendScript = DefaultRedisScript(
        """
        if redis.call("get", KEYS[1]) == ARGV[1] then
            return redis.call("pexpire", KEYS[1], ARGV[2])
        else
            return 0
        end
        """.trimIndent(),
        Long::class.java
    )

    /**
     * @param lockKey 락 키
     * @param initialTtlMillis 최초 락 획득 시 부여할 TTL (기본 10초)
     * @param extensionMillis 갱신 시 부여할 TTL (기본 10초)
     * @param action 임계 구역에서 실행할 실제 비즈니스 로직
     */
    suspend fun executeWithLock(
        lockKey: String,
        initialTtlMillis: Long = 10000,
        extensionMillis: Long = 10000,
        action: suspend () -> Unit
    ) {
        val actualInitialTtl = maxOf(initialTtlMillis, MIN_TTL_MILLIS)
        val actualExtensionTtl = maxOf(extensionMillis, MIN_TTL_MILLIS)
        val lockValue = UUID.randomUUID().toString()

        // 1. 최초 락 획득 시도
        val acquired = stringRedisTemplate.opsForValue()
            .setIfAbsent(lockKey, lockValue, Duration.ofMillis(actualInitialTtl)) ?: false

        if (!acquired) {
            logger.debug("{} : Failed to acquire lock", lockKey)
            return
        }


        coroutineScope {
            // 2. 비즈니스 로직 코루틴 생성
            val actionJob = launch { action() }

            // 3. 워치독 코루틴 실행
            val watchdogJob = launch(Dispatchers.IO) {
                var consecutiveFailures = 0
                while (isActive) {
                    // 남은 TTL의 절반 시점마다 연장 시도
                    delay(minOf(actualInitialTtl, actualExtensionTtl) / 2)

                    try {
                        val result = stringRedisTemplate.execute(
                            extendScript,
                            listOf(lockKey),
                            lockValue,
                            actualExtensionTtl.toString()
                        )

                        if (result == null || result <= 0L) {
                            logger.warn("{} : Lock lost. Cancelling action.", lockKey)
                            actionJob.cancel()
                            break
                        }
                        consecutiveFailures = 0
                        logger.debug("{} : Lock extended by {}ms.", lockKey, actualExtensionTtl)
                    } catch (e: Exception) {
                        logger.error("Watchdog: 락 연장 시도 중 에러 발생. 대상 키: {}", lockKey, e)
                        if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                            logger.error("{} : Watchdog consecutive failures exceeded limit. Cancelling action.", lockKey)
                            actionJob.cancel()
                            break
                        }
                    }
                }
            }

            try {
                // 4. 비즈니스 로직 완료 대기
                actionJob.join()
            } finally {
                // 5. 작업이 끝나거나 취소되면 워치독 종료
                watchdogJob.cancel()

                // 6. 안전한 락 해제 (Safe Release)
                try {
                    val result = stringRedisTemplate.execute(
                        unlockScript,
                        listOf(lockKey),
                        lockValue
                    )
                    if (result != null && result > 0L) {
                        logger.debug("🔓 {} : Releasing lock.", lockKey)
                    } else {
                        logger.warn("{} : Lock release failed (already expired).", lockKey)
                    }
                } catch (e: Exception) {
                    logger.error("락 해제 중 에러 발생. 대상 키: {}", lockKey, e)
                }
            }
        }
    }
}