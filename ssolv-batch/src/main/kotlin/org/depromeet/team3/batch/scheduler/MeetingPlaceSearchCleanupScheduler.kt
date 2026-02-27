package org.depromeet.team3.batch.scheduler

import org.depromeet.team3.meetingplacesearch.MeetingPlaceSearchRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 만료된 모임 장소 검색 결과를 주기적으로 삭제하는 스케줄러
 * 
 * - 실행 주기: 매시간
 * - 삭제 조건: expiresAt < 현재 시간
 */
@Component
class MeetingPlaceSearchCleanupScheduler(
    private val repository: MeetingPlaceSearchRepository
) {
    private val logger = LoggerFactory.getLogger(MeetingPlaceSearchCleanupScheduler::class.java)

    @Scheduled(cron = "0 0 * * * *")  // 매시간 정각에 실행
    @Transactional
    fun deleteExpired() {
        val now = LocalDateTime.now()

        try {
            val deletedCount = repository.deleteExpired(now)
            logger.debug("만료된 모임 장소 검색 결과 삭제 완료: {}건", deletedCount)
        } catch (e: Exception) {
            logger.error("모임 장소 검색 결과 삭제 중 오류 발생", e)
        }
    }
}
