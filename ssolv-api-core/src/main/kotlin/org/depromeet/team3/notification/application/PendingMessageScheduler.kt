package org.depromeet.team3.notification.application

import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.stream.PendingMessagesSummary
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
    private val stringRedisTemplate: StringRedisTemplate,
    private val meetingNotificationConsumer: MeetingNotificationConsumer,
    private val placeSearchConsumer: org.depromeet.team3.place.application.execution.PlaceSearchConsumer
) {
    private val logger = LoggerFactory.getLogger(PendingMessageScheduler::class.java)

    // 모니터링할 대상: (Stream Key, Group Name, 해당 Listener)
    private val consumers = listOf(
        Triple("meeting_notification_stream", "meeting_notification_group", meetingNotificationConsumer),
        Triple("meeting_calculation_stream", "meeting_calculation_group", placeSearchConsumer)
    )

    // 3분마다 실행
    @Scheduled(fixedDelay = 180000)
    fun processPendingMessages() {
        logger.debug("--- [WatchDog] 미처리 스트림 메시지 점검 시작 ---")

        for ((streamKey, groupName) in consumers.map { Triple(it.first, it.second, it.third) }) {
            try {
                // 1. 해당 그룹의 Pending Summary 확인
                val pendingSummary: PendingMessagesSummary? = stringRedisTemplate.opsForStream<String, String>()
                    .pending(streamKey, groupName)

                if (pendingSummary == null || pendingSummary.totalPendingMessages == 0L) {
                    continue
                }

                logger.info("스트림 {} 의 미처리 메시지 {} 건 발견", streamKey, pendingSummary.totalPendingMessages)

                // 2. Pending 메시지 상세 조회 (최근 1시간 내, N개 제한)
                val pendingMessages = stringRedisTemplate.opsForStream<String, String>()
                    .pending(
                        streamKey, 
                        // groupName 대신 Consumer 인스턴스를 전달하든지 직접 그룹을 전달하든지 오버로딩에 맞춤
                        org.springframework.data.redis.connection.stream.Consumer.from(groupName, "any"), 
                        org.springframework.data.domain.Range.unbounded<String>(), 
                        100L
                    )

                if (pendingMessages.isEmpty) {
                    continue
                }

                for (pending in pendingMessages) {
                    // 3분 이상 처리되지 않은 메시지만 대상
                    if (pending.elapsedTimeSinceLastDelivery.toMillis() > Duration.ofMinutes(3).toMillis()) {
                        logger.warn("WatchDog: {} 에서 3분 이상 정체된 메시지 ({}) 발견. 강제 재처리 수행", streamKey, pending.id)

                        // 메시지 본문 직접 조회 시도
                        val messageRecordList = stringRedisTemplate.opsForStream<String, String>()
                            .range(streamKey, org.springframework.data.domain.Range.just(pending.idAsString))
                            
                        if (messageRecordList.isNullOrEmpty()) {
                            // 이미 지워졌거나 만료된 메시지이면 ACK 처리만 수행
                            stringRedisTemplate.opsForStream<String, String>().acknowledge(streamKey, groupName, pending.id)
                            continue
                        }

                        val messageRecord = messageRecordList[0]

                        try {
                            // 리스너를 직접 호출하여 재처리
                            if (streamKey == "meeting_notification_stream") {
                                meetingNotificationConsumer.onMessage(messageRecord)
                            } else if (streamKey == "meeting_calculation_stream") {
                                placeSearchConsumer.onMessage(messageRecord)
                            }
                        } catch (e: Exception) {
                            logger.error("WatchDog: 메시지 ({}) 재처리 도중 오류 발생", pending.id, e)
                        }
                    }
                }
            } catch (e: Exception) {
                logger.error("WatchDog: 스트림 {} 에 대한 Pending 확인 오류", streamKey, e)
            }
        }
    }
}
