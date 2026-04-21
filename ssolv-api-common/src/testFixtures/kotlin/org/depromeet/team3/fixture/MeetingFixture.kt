package org.depromeet.team3.fixture

import org.depromeet.team3.auth.UserEntity
import org.depromeet.team3.meeting.Meeting
import org.depromeet.team3.meeting.MeetingEntity
import org.depromeet.team3.station.StationEntity
import java.time.LocalDateTime

object MeetingFixture {

    /** MeetingRepository(도메인 인터페이스) mock stub용 — Meeting 도메인 객체 */
    fun create(
        id: Long? = 1L,
        name: String = "테스트 모임",
        hostUserId: Long = 99L,
        attendeeCount: Int = 5,
        isClosed: Boolean = false,
        stationId: Long = 1L,
        endAt: LocalDateTime? = LocalDateTime.now().plusDays(1),
    ) = Meeting(
        id = id,
        name = name,
        hostUserId = hostUserId,
        attendeeCount = attendeeCount,
        isClosed = isClosed,
        stationId = stationId,
        endAt = endAt,
    )

    fun createEntity(
        id: Long? = 1L,
        name: String = "테스트 모임",
        attendeeCount: Int = 5,
        isClosed: Boolean = false,
        endAt: LocalDateTime? = LocalDateTime.now().plusDays(1),
        hostUser: UserEntity = UserFixture.createEntity(id = 99L),
        station: StationEntity = StationFixture.createEntity(),
    ) = MeetingEntity(
        id = id,
        name = name,
        attendeeCount = attendeeCount,
        isClosed = isClosed,
        endAt = endAt,
        hostUser = hostUser,
        station = station,
    )

    fun createEntityWithoutId(
        name: String = "테스트 모임",
        attendeeCount: Int = 5,
        isClosed: Boolean = false,
        endAt: LocalDateTime? = LocalDateTime.now().plusDays(1),
        hostUser: UserEntity = UserFixture.createEntityWithoutId(),
        station: StationEntity = StationFixture.createEntityWithoutId(),
    ) = createEntity(
        id = null,
        name = name,
        attendeeCount = attendeeCount,
        isClosed = isClosed,
        endAt = endAt,
        hostUser = hostUser,
        station = station,
    )
}
