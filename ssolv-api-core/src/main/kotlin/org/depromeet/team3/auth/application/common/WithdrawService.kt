package org.depromeet.team3.auth.application.common

import org.depromeet.team3.auth.AuthProvider
import org.depromeet.team3.auth.User
import org.depromeet.team3.auth.UserCommandRepository
import org.depromeet.team3.auth.UserQueryRepository
import org.depromeet.team3.auth.client.KakaoOAuthClient
import org.depromeet.team3.auth.exception.AuthException
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.meeting.MeetingRepository
import org.depromeet.team3.meetingattendee.MeetingAttendeeRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.LocalDateTime

/**
 * 회원 탈퇴 Service
 */
@Service
class WithdrawService(
    private val userQueryRepository: UserQueryRepository,
    private val userCommandRepository: UserCommandRepository,
    private val kakaoOAuthClient: KakaoOAuthClient,
    private val meetingRepository: MeetingRepository,
    private val meetingAttendeeRepository: MeetingAttendeeRepository
) {
    private val log = LoggerFactory.getLogger(WithdrawService::class.java)

    /**
     * 회원 탈퇴 처리
     * 1. 호스팅 중인 모임 검증 (다른 참석자가 있는 모임이 있으면 탈퇴 불가)
     * 2. 로컬 데이터 삭제 (Transaction)
     * 3. 소셜 플랫폼 연동 해제 (Transaction 커밋 후 실행)
     */
    @Transactional
    fun withdraw(userId: Long) {
        val user = userQueryRepository.findById(userId)
            ?: throw AuthException(ErrorCode.USER_NOT_FOUND)

        // 1. 호스팅 중인 모임 검증 (트랜잭션 참여)
        validateHostedMeetings(userId)

        // 2. 로컬 데이터 삭제 (소프트 딜리트)
        val withdrawnUser = user.copy(
            email = "withdrawn_${System.currentTimeMillis()}_${user.email}",
            socialId = "withdrawn_${System.currentTimeMillis()}_${user.socialId}",
            deletedAt = LocalDateTime.now()
        )
        userCommandRepository.save(withdrawnUser)

        // 3. 소셜 플랫폼 연동 해제 (트랜잭션 커밋 성공 후 비동기/지연 실행)
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() {
                    unlinkSocial(user)
                }
            })
        } else {
            unlinkSocial(user)
        }
    }

    private fun unlinkSocial(user: User) {
        try {
            when (user.provider) {
                AuthProvider.KAKAO -> kakaoOAuthClient.unlink(user.socialId)
                AuthProvider.APPLE -> {
                    // 애플 연동 해제는 추후 구현
                }
            }
        } catch (e: Exception) {
            log.error("소셜 연동 해제 실패 - userId: {}, provider: {}, error: {}", user.id, user.provider, e.message)
        }
    }

    /**
     * 호스팅 중인 모임 검증
     * 다른 참석자가 있는 모임을 호스팅 중이면 탈퇴 불가
     */
    private fun validateHostedMeetings(userId: Long) {
        val hostedMeetings = meetingRepository.findMeetingsByUserId(userId)
        
        for (meeting in hostedMeetings) {
            val meetingId = meeting.id ?: continue
            val attendees = meetingAttendeeRepository.findByMeetingId(meetingId)
            
            // 호스트 본인 외에 다른 참석자가 있는지 확인
            val hasOtherAttendees = attendees.any { it.userId != userId }
            
            if (hasOtherAttendees) {
                log.warn("탈퇴 시도 실패 - userId: {}, 다른 참석자가 있는 모임 호스팅 중: meetingId: {}", userId, meetingId)
                throw AuthException(ErrorCode.CANNOT_WITHDRAW_WITH_ACTIVE_MEETINGS)
            }
        }
    }
}
