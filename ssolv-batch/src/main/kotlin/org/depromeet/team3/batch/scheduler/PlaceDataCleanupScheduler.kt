package org.depromeet.team3.batch.scheduler

import org.depromeet.team3.place.PlaceJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 구글 약관(ToS) 준수를 위해 오래된 장소 정보를 주기적으로 삭제하는 스케줄러
 * 
 * - 실행 주기: 매일 새벽 3시
 * - 삭제 조건: updatedAt < 현재 시간 - 30일
 */
@Component
class PlaceDataCleanupScheduler(
    private val placeJpaRepository: PlaceJpaRepository
) {
    private val logger = LoggerFactory.getLogger(PlaceDataCleanupScheduler::class.java)

    @Scheduled(cron = "0 0 3 * * *")  // 매일 새벽 3시에 실행
    @Transactional
    fun cleanupStalePlaceData() {
        val thirtyDaysAgo = LocalDateTime.now().minusDays(30)

        try {
            val deletedCount = placeJpaRepository.deleteByUpdatedAtBefore(thirtyDaysAgo)
            logger.debug("30일 경과 장소 데이터 삭제 완료: {}건", deletedCount)
        } catch (e: Exception) {
            logger.error("장소 데이터 정리 중 오류 발생", e)
        }
    }
}
