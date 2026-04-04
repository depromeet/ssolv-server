package org.depromeet.team3.auth.application.common
import org.depromeet.team3.common.util.withTracingContext
import kotlinx.coroutines.Dispatchers
import org.depromeet.team3.auth.AuthProvider
import org.depromeet.team3.auth.UserEntity
import org.depromeet.team3.auth.UserRepository
import org.depromeet.team3.auth.client.KakaoOAuthClient
import org.depromeet.team3.auth.exception.AuthException
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.meeting.MeetingJpaRepository
import org.depromeet.team3.meetingattendee.MeetingAttendeeJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 회원 탈퇴 Service
 * withContext(VT) + transactionTemplate.execute 로 트랜잭션 경계를 동일 스레드에 고정.
 */
@Service
class WithdrawService(
    private val userJpaRepository: UserRepository,
    private val kakaoOAuthClient: KakaoOAuthClient,
    private val meetingJpaRepository: MeetingJpaRepository,
    private val meetingAttendeeJpaRepository: MeetingAttendeeJpaRepository,
    private val transactionTemplate: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(WithdrawService::class.java)

    /**
     * 회원 탈퇴 처리
     * 1. 호스팅 중인 모임 검증 (다른 참석자가 있는 모임이 있으면 탈퇴 불가)
     * 2. 로컬 데이터 삭제
     * 3. 소셜 플랫폼 연동 해제 (트랜잭션 커밋 후 비동기)
     */
    suspend fun withdraw(userId: Long): Unit = withTracingContext() {
        transactionTemplate.execute {
            val entity = userJpaRepository.findByIdOrNull(userId)
                ?: throw AuthException(ErrorCode.USER_NOT_FOUND)

            // 1. 호스팅 중인 모임 검증
            validateHostedMeetings(userId)

            // 2. 로컬 데이터 소프트 딜리트
            val currentTime = System.currentTimeMillis()
            entity.nickname = "withdrawn_${currentTime}_${entity.nickname}"
            entity.email = "withdrawn_${currentTime}_${entity.email}"
            entity.socialId = "withdrawn_${currentTime}_${entity.socialId}"
            entity.deletedAt = LocalDateTime.now()
            userJpaRepository.save(entity)

            // 3. 소셜 연동 해제는 트랜잭션 커밋 후 비동기 실행 (트랜잭션 활성 상태 체크)
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                val provider = entity.provider
                val socialId = entity.socialId
                val id = entity.id
                try {
                    TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                        override fun afterCommit() {
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    unlinkSocial(provider, socialId)
                                } catch (e: Exception) {
                                    log.error("소셜 연동 해제 중 에러 발생 (userId: $id)", e)
                                }
                            }
                        }
                    })
                } catch (e: Exception) {
                    log.error("트랜잭션 동기화 등록 실패 - 비동기 처리 생략: {}", e.message)
                }
            }
        }
    }

    private suspend fun unlinkSocial(provider: AuthProvider, socialId: String) {
        try {
            when (provider) {
                AuthProvider.KAKAO -> kakaoOAuthClient.unlink(socialId)
                AuthProvider.APPLE -> {
                    // 애플 연동 해제는 추후 구현
                }
            }
        } catch (e: Exception) {
            log.error("소셜 연동 해제 실패 - provider: {}, error: {}", provider, e.message)
        }
    }

    /**
     * 호스팅 중인 모임 검증 (blocking, transactionTemplate.execute 내부에서 호출)
     */
    private fun validateHostedMeetings(userId: Long) {
        val now = LocalDateTime.now()
        val hostedMeetings = meetingJpaRepository.findByHostUserId(userId)

        for (meeting in hostedMeetings) {
            // 1. 이미 종료되었거나 기간이 만료된 모임은 탈퇴를 방해하지 않음
            if (meeting.isClosed) continue
            
            val endAt = meeting.endAt
            if (endAt != null && endAt.isBefore(now)) continue

            val meetingId = meeting.id ?: continue
            val attendees = meetingAttendeeJpaRepository.findByMeetingId(meetingId)

            // 2. 호스트 본인을 제외한 다른 참석자가 한 명이라도 있는지 확인
            val hasOtherAttendees = attendees.any { it.user.id != null && it.user.id != userId }
            
            if (hasOtherAttendees) {
                log.warn("탈퇴 시도 실패 - userId: {}, 다른 참석자가 있는 활성 모임 호스팅 중: meetingId: {}", userId, meetingId)
                throw AuthException(ErrorCode.CANNOT_WITHDRAW_WITH_ACTIVE_MEETINGS)
            }
        }
    }
}

