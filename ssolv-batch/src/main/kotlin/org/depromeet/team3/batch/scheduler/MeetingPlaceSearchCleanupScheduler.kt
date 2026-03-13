package org.depromeet.team3.batch.scheduler

import org.depromeet.team3.meetingplacesearch.MeetingPlaceSearchRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking

/**
 * 만료된 모임 장소 검색 결과를 주기적으로 삭제하는 스케줄러
 */
@Component
class MeetingPlaceSearchCleanupScheduler(
    private val repository: MeetingPlaceSearchRepository,
    private val watchdogManager: CoroutineWatchdogManager,
    private val transactionTemplate: TransactionTemplate
) {
    private val logger = LoggerFactory.getLogger(MeetingPlaceSearchCleanupScheduler::class.java)

    @Scheduled(cron = "0 0 * * * *")  // 매시간 정각에 실행
    fun deleteExpired() {
        val lockKey = "lock:cleanup:meeting-place-search"
        
        runBlocking {
            watchdogManager.executeWithLock(lockKey, 10000, 10000) {
                try {
                    transactionTemplate.execute {
                        val now = LocalDateTime.now()
                        val deletedCount = repository.deleteExpired(now)
                        logger.info("만료된 모임 장소 검색 결과 삭제 완료: {}건", deletedCount)
                    }
                } catch (e: Exception) {
                    logger.error("모임 장소 검색 결과 삭제 중 오류 발생", e)
                }
            }
        }
    }
}