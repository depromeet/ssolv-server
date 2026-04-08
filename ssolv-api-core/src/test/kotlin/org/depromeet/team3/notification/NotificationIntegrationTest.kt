package org.depromeet.team3.notification

import com.ninjasquad.springmockk.MockkBean
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.depromeet.team3.annotation.IntegrationTest
import org.depromeet.team3.auth.UserRepository
import org.depromeet.team3.common.constants.RedisStreamConstants
import org.depromeet.team3.fixture.MeetingFixture
import org.depromeet.team3.fixture.StationFixture
import org.depromeet.team3.fixture.UserFixture
import org.depromeet.team3.meeting.MeetingJpaRepository
import org.depromeet.team3.meeting.application.MeetingExpirationListener
import org.depromeet.team3.meetingattendee.MeetingAttendeeJpaRepository
import org.depromeet.team3.notification.application.MeetingNotificationConsumer
import org.depromeet.team3.notification.domain.DevicePlatform
import org.depromeet.team3.notification.domain.FcmClient
import org.depromeet.team3.notification.infrastructure.DeviceTokenEntity
import org.depromeet.team3.notification.infrastructure.DeviceTokenJpaRepository
import org.depromeet.team3.placelike.application.PlaceLikeSseService
import org.depromeet.team3.station.StationJpaRepository
import org.depromeet.team3.survey.SurveyJpaRepository
import org.depromeet.team3.survey.application.CreateSurveyService
import org.depromeet.team3.survey.dto.request.SurveyCreateRequest
import org.depromeet.team3.surveycategory.SurveyCategoryJpaRepository
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.connection.stream.RecordId
import org.springframework.data.redis.core.StreamOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations

@IntegrationTest
class NotificationIntegrationTest {

    @Autowired private lateinit var createSurveyService: CreateSurveyService
    @Autowired private lateinit var meetingNotificationConsumer: MeetingNotificationConsumer

    @MockkBean private lateinit var stringRedisTemplate: StringRedisTemplate
    @MockkBean private lateinit var fcmClient: FcmClient
    @MockkBean private lateinit var meetingExpirationListener: MeetingExpirationListener
    @MockkBean private lateinit var placeLikeSseService: PlaceLikeSseService

    @Autowired private lateinit var meetingJpaRepository: MeetingJpaRepository
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var stationJpaRepository: StationJpaRepository
    @Autowired private lateinit var meetingAttendeeJpaRepository: MeetingAttendeeJpaRepository
    @Autowired private lateinit var surveyJpaRepository: SurveyJpaRepository
    @Autowired private lateinit var surveyCategoryJpaRepository: SurveyCategoryJpaRepository
    @Autowired private lateinit var deviceTokenJpaRepository: DeviceTokenJpaRepository

    @Test
    @DisplayName("7명의 참여자 중 마지막 인원이 설문을 완료하면 알림 스트림이 발행되고, FCM이 발송된다")
    fun `7명 전원 설문 완료 시 알림 발송 메인 흐름 검증`() = runBlocking {
        // given — 7명 모임 구성
        val participantsCount = 7
        val users = (1..participantsCount).map { i ->
            userRepository.save(UserFixture.createEntityWithoutId(socialId = "user-$i", email = "user$i@test.com", nickname = "user-$i"))
        }
        users.forEach { user ->
            deviceTokenJpaRepository.save(DeviceTokenEntity(userId = user.id!!, fcmToken = "token-${user.id}", platform = DevicePlatform.IOS))
        }
        val station = stationJpaRepository.save(StationFixture.createEntityWithoutId())
        val meeting = meetingJpaRepository.save(MeetingFixture.createEntityWithoutId(attendeeCount = participantsCount, hostUser = users[0], station = station))
        users.forEach { user ->
            meetingAttendeeJpaRepository.save(
                org.depromeet.team3.fixture.MeetingAttendeeFixture.createEntityWithoutId(meeting = meeting, user = user)
            )
        }
        val cat = surveyCategoryJpaRepository.save(
            org.depromeet.team3.fixture.SurveyCategoryFixture.createEntityWithoutId()
        )

        // given — Redis mock 설정
        val streamOps = mockk<StreamOperations<String, String, String>>()
        val valueOps = mockk<ValueOperations<String, String>>()
        every { stringRedisTemplate.opsForStream<String, String>() } returns streamOps
        every { stringRedisTemplate.opsForValue() } returns valueOps
        every { valueOps.setIfAbsent(any(), any(), any()) } returns true
        every { streamOps.add(any<MapRecord<String, String, String>>()) } returns RecordId.of("123-0")
        every { streamOps.acknowledge(any(), any<MapRecord<String, String, String>>()) } returns 1L
        every { stringRedisTemplate.delete(any<String>()) } returns true

        // when — 1~6번째 유저 설문 완료 (알림 미발행)
        for (i in 0 until participantsCount - 1) {
            createSurveyService.invoke(meeting.id!!, users[i].id!!, SurveyCreateRequest(listOf(cat.id!!)))
            verify(exactly = 0) {
                streamOps.add(match { it.stream == RedisStreamConstants.MEETING_NOTIFICATION_STREAM })
            }
        }

        // when — 마지막(7번째) 유저 설문 완료
        createSurveyService.invoke(meeting.id!!, users.last().id!!, SurveyCreateRequest(listOf(cat.id!!)))

        // then — 알림 스트림 7회 + 계산 스트림 1회 발행
        verify(exactly = participantsCount) {
            streamOps.add(match { it.stream == RedisStreamConstants.MEETING_NOTIFICATION_STREAM })
        }
        verify(exactly = 1) {
            streamOps.add(match { it.stream == RedisStreamConstants.MEETING_CALCULATION_STREAM })
        }

        // then — FCM 발송 검증
        coEvery { fcmClient.sendMulticast(any(), any(), any(), any()) } returns Unit
        users.forEach { user ->
            val mockRecord = MapRecord.create(
                RedisStreamConstants.MEETING_NOTIFICATION_STREAM,
                mapOf("meetingId" to meeting.id.toString(), "userId" to user.id.toString())
            ).withId(RecordId.of("123-${user.id}"))
            meetingNotificationConsumer.onMessage(mockRecord)
        }
        users.forEach { user ->
            coVerify(timeout = 5000) {
                fcmClient.sendMulticast(
                    tokens = match { it.contains("token-${user.id}") && it.size == 1 },
                    title = any(), body = any(), data = any()
                )
            }
        }
    }

    @Test
    @DisplayName("이미 전송된 동일한 유저/모임의 알림 메시지가 수신되면 FCM 발송이 중복되지 않는다")
    fun `중복 메시지 멱등성 검증`() = runBlocking {
        // given
        val mockRecord = MapRecord.create(
            RedisStreamConstants.MEETING_NOTIFICATION_STREAM,
            mapOf("meetingId" to "1", "userId" to "100")
        ).withId(RecordId.of("999-0"))

        val valueOps = mockk<ValueOperations<String, String>>()
        every { stringRedisTemplate.opsForValue() } returns valueOps
        every { valueOps.setIfAbsent(any(), any(), any()) } returns false

        // when
        meetingNotificationConsumer.onMessage(mockRecord)

        // then
        coVerify(exactly = 0) { fcmClient.sendMulticast(any(), any(), any(), any()) }
    }

    @Test
    @DisplayName("FCM 전송 중 예외 발생 시 acknowledge가 호출되지 않아야 한다")
    fun `FCM 전송 실패 시 재시도를 위해 ACK를 생략한다`() = runBlocking {
        // given
        val user = userRepository.save(UserFixture.createEntityWithoutId(socialId = "fail-user", email = "fail@test.com", nickname = "fail-user"))
        deviceTokenJpaRepository.save(DeviceTokenEntity(userId = user.id!!, fcmToken = "fail-token", platform = DevicePlatform.IOS))
        val station = stationJpaRepository.save(StationFixture.createEntityWithoutId())
        val meeting = meetingJpaRepository.save(MeetingFixture.createEntityWithoutId(hostUser = user, station = station))

        val mockRecord = MapRecord.create(
            RedisStreamConstants.MEETING_NOTIFICATION_STREAM,
            mapOf("meetingId" to meeting.id.toString(), "userId" to user.id.toString())
        ).withId(RecordId.of("888-0"))

        val streamOps = mockk<StreamOperations<String, String, String>>()
        val valueOps = mockk<ValueOperations<String, String>>()
        every { stringRedisTemplate.opsForStream<String, String>() } returns streamOps
        every { stringRedisTemplate.opsForValue() } returns valueOps
        every { valueOps.setIfAbsent(any(), any(), any()) } returns true
        coEvery { fcmClient.sendMulticast(any(), any(), any(), any()) } throws RuntimeException("FCM 서버 장애")

        // when
        meetingNotificationConsumer.onMessage(mockRecord)

        // then — 에러 발생 시 ACK 미호출
        kotlinx.coroutines.delay(500)
        verify(exactly = 0) { streamOps.acknowledge(any(), any<MapRecord<String, String, String>>()) }
    }

    @Test
    @DisplayName("토큰이 없는 유저의 경우 알림 발송은 스킵되지만 메시지는 정상 처리(ACK)되어야 한다")
    fun `토큰 부재 시 알림 스킵 및 정상 ACK 처리 검증`() = runBlocking {
        // given — 토큰 미등록 유저
        val user = userRepository.save(UserFixture.createEntityWithoutId(socialId = "no-token-user", email = "notoken@test.com", nickname = "no-token-user"))
        val station = stationJpaRepository.save(StationFixture.createEntityWithoutId())
        val meeting = meetingJpaRepository.save(MeetingFixture.createEntityWithoutId(hostUser = user, station = station))

        val mockRecord = MapRecord.create(
            RedisStreamConstants.MEETING_NOTIFICATION_STREAM,
            mapOf("meetingId" to meeting.id.toString(), "userId" to user.id.toString())
        ).withId(RecordId.of("777-0"))

        val streamOps = mockk<StreamOperations<String, String, String>>()
        val valueOps = mockk<ValueOperations<String, String>>()
        every { stringRedisTemplate.opsForStream<String, String>() } returns streamOps
        every { stringRedisTemplate.opsForValue() } returns valueOps
        every { valueOps.setIfAbsent(any(), any(), any()) } returns true
        every { streamOps.acknowledge(any(), any<MapRecord<String, String, String>>()) } returns 1L

        // when
        meetingNotificationConsumer.onMessage(mockRecord)

        // then — FCM 미전송, ACK 1회 호출
        coVerify(exactly = 0) { fcmClient.sendMulticast(any(), any(), any(), any()) }
        verify(exactly = 1, timeout = 5000) {
            streamOps.acknowledge(RedisStreamConstants.MEETING_NOTIFICATION_GROUP, any<MapRecord<String, String, String>>())
        }
    }
}
