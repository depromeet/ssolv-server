package org.depromeet.team3.place.util

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

/**
 * API 레이어에서 사용하는 경량 분산 락 서비스
 * 
 * - 타임아웃이 명확한 API 요청의 중복 실행(Cache Stampede)을 방지하기 위해 사용합니다.
 * - 배치의 긴 작업을 위한 Watchdog 기능은 포함하지 않으며, 고정 TTL 방식을 사용합니다.
 */
@Component
class DistributedLockService(
    private val redisTemplate: StringRedisTemplate
) {
    private val logger = LoggerFactory.getLogger(DistributedLockService::class.java)

    // 안전한 해제를 위한 루아 스크립트 (내가 잡은 락일 때만 해제)
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

    /**
     * @param lockKey 락 키
     * @param ttl 락 유지 시간 (기본 10초)
     * @param action 락 획득 시 실행할 로직
     * @return 락 획득 실패 시 null, 성공 시 action의 결과
     */
    suspend fun <T> withLock(
        lockKey: String,
        ttl: Duration = Duration.ofSeconds(10),
        action: suspend () -> T
    ): T? {
        val lockValue = UUID.randomUUID().toString()
        
        // 1. 락 획득 시도 (SETNX)
        val acquired = redisTemplate.opsForValue()
            .setIfAbsent(lockKey, lockValue, ttl) ?: false

        if (!acquired) {
            logger.debug("Lock [{}] is already held by another process.", lockKey)
            return null
        }

        return try {
            logger.debug("Successfully acquired lock [{}].", lockKey)
            action()
        } finally {
            // 2. 안전한 락 해제
            try {
                val result = redisTemplate.execute(
                    unlockScript,
                    listOf(lockKey),
                    lockValue
                )
                if (result != null && result > 0L) {
                    logger.debug("Successfully released lock [{}].", lockKey)
                } else {
                    logger.warn("Failed to release lock [{}] (already expired or value changed).", lockKey)
                }
            } catch (e: Exception) {
                logger.error("Error occurred while releasing lock [{}].", lockKey, e)
            }
        }
    }
}