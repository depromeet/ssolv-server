package org.depromeet.team3.common.util

import org.depromeet.team3.auth.AuthProvider
import org.depromeet.team3.auth.UserEntity
import org.depromeet.team3.meeting.MeetingEntity
import org.depromeet.team3.meetingattendee.MeetingAttendeeEntity
import org.depromeet.team3.meetingattendee.MuzziColor
import org.depromeet.team3.station.StationEntity
import org.depromeet.team3.survey.SurveyEntity
import org.depromeet.team3.surveyresult.SurveyResultEntity
import org.depromeet.team3.surveycategory.SurveyCategoryEntity
import org.depromeet.team3.surveycategory.SurveyCategoryLevel
import java.time.LocalDateTime

/**
 * JPA Entity 기반 테스트 데이터 팩토리.
 * 서비스가 JpaRepository를 직접 사용하도록 리팩토링된 이후
 * 단위 테스트에서 Entity 객체를 생성하기 위해 사용.
 */
object TestEntityFactory {

    fun createUserEntity(
        id: Long? = 1L,
        provider: AuthProvider = AuthProvider.KAKAO,
        socialId: String = "kakao-123",
        email: String = "test@example.com",
        nickname: String = "테스트유저",
        profileImage: String? = null,
        refreshToken: String? = null,
        deletedAt: LocalDateTime? = null
    ) = UserEntity(
        id = id,
        provider = provider,
        socialId = socialId,
        email = email,
        nickname = nickname,
        profileImage = profileImage,
        refreshToken = refreshToken,
        deletedAt = deletedAt
    )

    fun createStationEntity(
        id: Long? = 1L,
        name: String = "강남",
        locX: Double = 127.027,
        locY: Double = 37.497
    ) = StationEntity(
        id = id,
        name = name,
        locX = locX,
        locY = locY
    )

    fun createMeetingEntity(
        id: Long? = 1L,
        name: String = "테스트 모임",
        attendeeCount: Int = 5,
        isClosed: Boolean = false,
        endAt: LocalDateTime? = LocalDateTime.now().plusDays(1),
        hostUser: UserEntity = createUserEntity(id = 99L),
        station: StationEntity = createStationEntity()
    ) = MeetingEntity(
        id = id,
        name = name,
        attendeeCount = attendeeCount,
        isClosed = isClosed,
        endAt = endAt,
        hostUser = hostUser,
        station = station
    )

    fun createMeetingAttendeeEntity(
        id: Long? = 1L,
        meeting: MeetingEntity = createMeetingEntity(),
        user: UserEntity = createUserEntity(),
        attendeeNickname: String? = "참가자",
        muzziColor: MuzziColor = MuzziColor.DEFAULT
    ) = MeetingAttendeeEntity(
        id = id,
        meeting = meeting,
        user = user,
        attendeeNickname = attendeeNickname,
        muzziColor = muzziColor
    )

    fun createSurveyEntity(
        id: Long? = 1L,
        meeting: MeetingEntity = createMeetingEntity(),
        participant: MeetingAttendeeEntity = createMeetingAttendeeEntity()
    ) = SurveyEntity(
        id = id,
        meeting = meeting,
        participant = participant
    )

    fun createSurveyCategoryEntity(
        id: Long? = 1L,
        level: SurveyCategoryLevel = SurveyCategoryLevel.LEAF,
        name: String = "한식",
        sortOrder: Int = 1,
        parent: SurveyCategoryEntity? = null,
        isDeleted: Boolean = false
    ) = SurveyCategoryEntity(
        id = id,
        level = level,
        name = name,
        sortOrder = sortOrder,
        parent = parent,
        isDeleted = isDeleted
    )

    fun createSurveyResultEntity(
        id: Long? = 1L,
        survey: SurveyEntity = createSurveyEntity(),
        surveyCategory: SurveyCategoryEntity = createSurveyCategoryEntity()
    ) = SurveyResultEntity(
        id = id,
        survey = survey,
        surveyCategory = surveyCategory
    )
}
