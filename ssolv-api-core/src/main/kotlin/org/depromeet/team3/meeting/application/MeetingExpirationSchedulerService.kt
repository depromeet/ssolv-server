package org.depromeet.team3.meeting.application

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

/*
 * 모임 만료 시간을 관리하고 Redis TTL을 이용해 만료 이벤트를 예약하는 서비스
 */
@Service
class MeetingExpirationSchedulerService(private val stringRedisTemplate: StringRedisTemplate) {
    fun scheduleExpiration(meetingId: Long, endAt: LocalDateTime) {
        val now = LocalDateTime.now(ZoneId.of("Asia/Seoul"))
        val duration = Duration.between(now, endAt)

        val ttl = if (duration.isNegative || duration.isZero) {
            Duration.ofSeconds(1)
        } else {
            duration
        }

        stringRedisTemplate.opsForValue().set("meeting:expire:$meetingId", "expire", ttl)
    }

    fun cancelExpiration(meetingId: Long) {
        stringRedisTemplate.delete("meeting:expire:$meetingId")
    }
}
