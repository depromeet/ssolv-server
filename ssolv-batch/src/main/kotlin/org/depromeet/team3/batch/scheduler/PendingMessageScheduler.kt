package org.depromeet.team3.batch.scheduler

import org.slf4j.LoggerFactory
import org.springframework.data.domain.Range
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Redis Streams의 미처리 메시지를 감시하고 재시도하는 스케줄러 (WatchDog)
 *
 * 주요 역할:
 * 1. 3분마다 각 스트림의 PEL(Pending Entries List)을 확인
 * 2. 특정 시간(3분) 이상 ACK되지 않은 메시지를 감지하여 강제 재처리 수행
 * 3. 서버 장애나 일시적 오류로 누락될 수 있는 알림 및 장소 검색 추천 요청의 완결성 보장
 */
@Component
@org.springframework.context.annotation.Profile("!test")
class PendingMessageScheduler(
    private val stringRedisTemplate: StringRedisTemplate
) {
    private val logger = LoggerFactory.getLogger(PendingMessageScheduler::class.java)

    private val consumers = listOf(
        "meeting_notification_stream" to "meeting_notification_group",
        "meeting_calculation_stream" to "meeting_calculation_group"
    )

    // 3분마다 실행
    @Scheduled(fixedDelay = 180000)
    fun processPendingMessages() {
        logger.debug("--- [WatchDog] 미처리 스트림 메시지 점검 시작 ---")

        for ((streamKey, groupName) in consumers) {
            try {
                // 1. 해당 그룹의 Pending Summary 확인
                val pendingSummary = stringRedisTemplate.opsForStream<String, String>()
                    .pending(streamKey, groupName)

                if (pendingSummary == null || pendingSummary.totalPendingMessages == 0L) {
                    continue
                }

                logger.info("스트림 {} 의 미처리 메시지 {} 건 발견", streamKey, pendingSummary.totalPendingMessages)

                val pendingMessages = stringRedisTemplate.opsForStream<String, String>()
                    .pending(
                        streamKey, 
                        org.springframework.data.redis.connection.stream.Consumer.from(groupName, "any"), 
                        Range.unbounded<String>(), 
                        100L
                    )

                if (pendingMessages.isEmpty) {
                    continue
                }

                for (pending in pendingMessages) {
                    // 3분 이상 처리되지 않은 메시지만 대상
                    if (pending.elapsedTimeSinceLastDelivery.toMillis() > Duration.ofMinutes(3).toMillis()) {
                        logger.warn("WatchDog: {} 에서 3분 이상 정체된 메시지 ({}) 발견. 재발행 수행", streamKey, pending.id)

                        // 3. 메시지 본문 조회
                        val messageRecordList = stringRedisTemplate.opsForStream<String, String>()
                            .range(streamKey, Range.just(pending.idAsString))
                            
                        if (messageRecordList.isNullOrEmpty()) {
                            // 이미 지워졌거나 만료된 메시지이면 ACK 처리만 수행하여 PEL에서 제거
                            stringRedisTemplate.opsForStream<String, String>().acknowledge(streamKey, groupName, pending.id)
                            continue
                        }

                        val messageRecord = messageRecordList[0]

                        try {
                            // 4. 새 메시지로 다시 발행 (Re-publish)
                            val reRecord = MapRecord.create(streamKey, messageRecord.value)
                            stringRedisTemplate.opsForStream<String, String>().add(reRecord)
                            
                            // 5. 기존 펜딩 메시지는 ACK 처리하여 PEL에서 제거 (무한 재발행 방지)
                            stringRedisTemplate.opsForStream<String, String>().acknowledge(streamKey, groupName, pending.id)
                            
                            logger.info("WatchDog: 메시지 ({}) 재발행 및 기존 메시지 ACK 완료", pending.id)
                        } catch (e: Exception) {
                            logger.error("WatchDog: 메시지 ({}) 재발행 도중 오류 발생", pending.id, e)
                        }
                    }
                }
            } catch (e: Exception) {
                logger.error("WatchDog: 스트림 {} 에 대한 Pending 확인 오류", streamKey, e)
            }
        }
    }
}