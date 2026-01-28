package org.depromeet.team3.meeting.util

import org.depromeet.team3.auth.AuthProvider
import org.depromeet.team3.auth.UserEntity
import org.depromeet.team3.meeting.Meeting
import org.depromeet.team3.meeting.MeetingEntity
import org.depromeet.team3.station.StationEntity
import org.depromeet.team3.station.util.StationTestDataFactory
import java.time.LocalDateTime

/**
 * Meeting 관련 테스트 데이터 팩토리 클래스
 */
object MeetingTestDataFactory {

    fun createUserEntity(
        id: Long? = null,
        provider: AuthProvider = AuthProvider.KAKAO,
        socialId: String = "test_social_id",
        email: String = "test@example.com",
        nickname: String = "테스트사용자",
        profileImage: String? = "http://example.com/profile.jpg",
        refreshToken: String? = null
    ): UserEntity {
        return UserEntity(
            id = id,
            provider = provider,
            socialId = socialId,
            email = email,
            nickname = nickname,
            profileImage = profileImage,
            refreshToken = refreshToken
        )
    }

    fun createMeetingEntity(
        id: Long = 1L,
        name: String = "테스트 미팅",
        attendeeCount: Int = 2,
        isClosed: Boolean = false,
        endAt: java.time.LocalDateTime? = null,
        hostUser: UserEntity = createUserEntity(),
        station: StationEntity = StationTestDataFactory.createStationEntity()
    ): MeetingEntity {
        val meeting = MeetingEntity(
            id = id,
            name = name,
            attendeeCount = attendeeCount,
            isClosed = isClosed,
            endAt = endAt,
            hostUser = hostUser,
            station = station
        )
        // id를 리플렉션으로 설정 (테스트용)
        if (id != null) {
            val idField = MeetingEntity::class.java.getDeclaredField("id")
            idField.isAccessible = true
            idField.set(meeting, id)
        }
        return meeting
    }

    fun createMeeting(
        id: Long = 1L,
        name: String = "테스트 미팅",
        hostUserId: Long = 1L,
        attendeeCount: Int = 2,
        isClosed: Boolean = false,
        stationId: Long = 1L,
        endAt: LocalDateTime? = null,
        createdAt: LocalDateTime? = LocalDateTime.now(),
        updatedAt: LocalDateTime? = LocalDateTime.now()
    ): Meeting {
        return Meeting(
            id = id,
            name = name,
            hostUserId = hostUserId,
            attendeeCount = attendeeCount,
            isClosed = isClosed,
            stationId = stationId,
            endAt = endAt,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }
}
