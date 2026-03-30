package org.depromeet.team3.meeting.application

import org.depromeet.team3.common.constants.RedisStreamConstants
import kotlinx.coroutines.runBlocking
import org.depromeet.team3.meetingattendee.MeetingAttendeeRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.redis.connection.Message
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.KeyExpirationEventMessageListener
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.stereotype.Component
import java.time.Duration

/*
 * Redis 키 만료 이벤트를 감지하여 모임 시간 만료 시 장소 검색을 실행하는 리스너
 */
@Component
class MeetingExpirationListener(
    @Qualifier("redisMessageListenerContainer") listenerContainer: RedisMessageListenerContainer,
    private val stringRedisTemplate: StringRedisTemplate,
    private val meetingAttendeeRepository: MeetingAttendeeRepository
) : KeyExpirationEventMessageListener(listenerContainer) {

    private val logger = LoggerFactory.getLogger(MeetingExpirationListener::class.java)

    override fun onMessage(message: Message, pattern: ByteArray?) {
        val expiredKey = message.toString()
        if (!expiredKey.startsWith("meeting:expire:")) {
            return
        }

        val meetingIdStr = expiredKey.removePrefix("meeting:expire:")
        val meetingId = meetingIdStr.toLongOrNull() ?: return

        // 분산 환경 중복 실행 방지를 위한 락 획득 (SETNX)
        val lockKey = "lock:meeting:expire:$meetingId"
        val acquired = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "locked", Duration.ofMinutes(10)) ?: false
        if (!acquired) {
            logger.debug("모임 $meetingId 만료 처리가 이미 다른 인스턴스에서 진행 중입니다.")
            return
        }

        val requestId = "expire-" + java.util.UUID.randomUUID().toString().substring(0, 8)
        org.slf4j.MDC.put(org.depromeet.team3.common.filter.MdcLoggingFilter.REQUEST_ID, requestId)

        try {
            logger.info("모임 $meetingId 만료 발생! 비동기 장소 검색 및 알림 스트림을 발행합니다.")

            // 1. 식당 검색 추천 요청 발행
            val calcRecord = org.springframework.data.redis.connection.stream.MapRecord.create(
                RedisStreamConstants.MEETING_CALCULATION_STREAM, mapOf<String, String>(
                    "meetingId" to meetingId.toString(),
                    "requestId" to requestId
                )
            )
            stringRedisTemplate.opsForStream<String, String>().add(calcRecord)

            // 2. 만료 알림 트리거 발행 (Fan-out: 구성원 한 명 당 1개 메시지)
            runBlocking {
                val attendees = meetingAttendeeRepository.findByMeetingId(meetingId)
                
                attendees.forEach { attendee ->
                    val notarRecord = org.springframework.data.redis.connection.stream.MapRecord.create(
                        RedisStreamConstants.MEETING_NOTIFICATION_STREAM,
                        mapOf<String, String>(
                            "meetingId" to meetingId.toString(),
                            "userId" to attendee.userId.toString(),
                            "requestId" to requestId
                        )
                    )
                    stringRedisTemplate.opsForStream<String, String>().add(notarRecord)
                    logger.debug("모임 {} 만료 알림 발행 완료 (대상 사용자: {})", meetingId, attendee.userId)
                }
                logger.debug("모임 {} 의 참여자 {} 명에 대해 알림 스트림 발행을 완료했습니다.", meetingId, attendees.size)
            }
        } catch (e: Exception) {
            logger.error("모임 $meetingId 만료 처리 중 오류 발생", e)
            io.sentry.Sentry.captureException(e)
        } finally {
            org.slf4j.MDC.clear()
        }
    }
}