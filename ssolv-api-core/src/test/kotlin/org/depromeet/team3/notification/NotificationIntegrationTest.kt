package org.depromeet.team3.notification

import com.ninjasquad.springmockk.MockkBean
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.depromeet.team3.CoreApiApplication
import org.depromeet.team3.auth.UserRepository
import org.depromeet.team3.common.util.TestEntityFactory
import org.depromeet.team3.meeting.MeetingJpaRepository
import org.depromeet.team3.meetingattendee.MeetingAttendeeJpaRepository
import org.depromeet.team3.notification.application.MeetingNotificationConsumer
import org.depromeet.team3.notification.application.SendMeetingResultNotificationService
import org.depromeet.team3.notification.domain.FcmClient
import org.depromeet.team3.meeting.application.MeetingExpirationListener
import org.depromeet.team3.station.StationJpaRepository
import org.depromeet.team3.survey.SurveyJpaRepository
import org.depromeet.team3.survey.application.CreateSurveyService
import org.depromeet.team3.survey.dto.request.SurveyCreateRequest
import org.depromeet.team3.surveycategory.SurveyCategoryJpaRepository
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.connection.stream.RecordId
import org.springframework.data.redis.core.StreamOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional

@SpringBootTest(classes = [CoreApiApplication::class])
@ActiveProfiles("test")
class NotificationIntegrationTest {

    @Autowired
    private lateinit var createSurveyService: CreateSurveyService

    @Autowired
    private lateinit var meetingNotificationConsumer: MeetingNotificationConsumer

    @MockkBean
    private lateinit var stringRedisTemplate: StringRedisTemplate

    @MockkBean
    private lateinit var fcmClient: FcmClient

    @MockkBean
    private lateinit var meetingExpirationListener: MeetingExpirationListener

    @Autowired
    private lateinit var meetingJpaRepository: MeetingJpaRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var stationJpaRepository: StationJpaRepository

    @Autowired
    private lateinit var meetingAttendeeJpaRepository: MeetingAttendeeJpaRepository

    @Autowired
    private lateinit var surveyJpaRepository: SurveyJpaRepository

    @Autowired
    private lateinit var surveyCategoryJpaRepository: SurveyCategoryJpaRepository

    @Autowired
    private lateinit var deviceTokenJpaRepository: org.depromeet.team3.notification.infrastructure.DeviceTokenJpaRepository

    @Test
    @DisplayName("7명의 참여자 중 마지막 인원이 설문을 완료하면 알림 스트림이 발행되고, FCM이 발송된다")
    fun `7명 전원 설문 완료 시 알림 발송 메인 흐름 검증`() = runBlocking {
        // 1. 초기 데이터 설정 (7명 모임)
        val participantsCount = 7
        val users = (1..participantsCount).map { i ->
            userRepository.save(TestEntityFactory.createUserEntity(id = null, socialId = "user-$i", email = "user$i@test.com"))
        }
        
        // 모든 유저의 디바이스 토큰 등록
        users.forEach { user ->
            deviceTokenJpaRepository.save(org.depromeet.team3.notification.infrastructure.DeviceTokenEntity(
                userId = user.id!!, fcmToken = "token-${user.id}", platform = org.depromeet.team3.notification.domain.DevicePlatform.IOS
            ))
        }

        val station = stationJpaRepository.save(TestEntityFactory.createStationEntity(id = null))
        val host = users[0]
        
        val meeting = meetingJpaRepository.save(TestEntityFactory.createMeetingEntity(
            id = null, hostUser = host, station = station, attendeeCount = participantsCount
        ))
        
        // 모든 참여자 등록
        users.forEach { user ->
            meetingAttendeeJpaRepository.save(TestEntityFactory.createMeetingAttendeeEntity(id = null, meeting = meeting, user = user))
        }

        // 2. 카테고리 설정
        val cat = surveyCategoryJpaRepository.save(TestEntityFactory.createSurveyCategoryEntity(id = null))

        // 3. Redis Mock 설정
        val streamOps = mockk<StreamOperations<String, String, String>>()
        every { stringRedisTemplate.opsForStream<String, String>() } returns streamOps
        every { streamOps.add(any<MapRecord<String, String, String>>()) } returns RecordId.of("123-0")
        every { streamOps.acknowledge(any(), any<MapRecord<String, String, String>>()) } returns 1L
        every { stringRedisTemplate.delete(any<String>()) } returns true  // cancelExpiration 호출 시 만료 키 삭제

        // 4. 1번째부터 6번째 유저까지 설문 완료 (알림 안 울려야 함)
        for (i in 0 until participantsCount - 1) {
            createSurveyService.invoke(meeting.id!!, users[i].id!!, SurveyCreateRequest(listOf(cat.id!!)))
            
            // then: 아직 전원이 아니므로 알림 스트림이 발행되지 않아야 함
            verify(exactly = 0) { 
                streamOps.add(match { it.stream == "meeting_notification_stream" }) 
            }
        }

        // 5. 마지막 7번째 유저 설문 완료 (알림 울려야 함)
        createSurveyService.invoke(meeting.id!!, users.last().id!!, SurveyCreateRequest(listOf(cat.id!!)))

        // then: 알림 스트림(meeting_notification_stream)이 참여자 수(7명)만큼 발행되었는지 확인
        verify(exactly = participantsCount) { 
            streamOps.add(match { it.stream == "meeting_notification_stream" }) 
        }

        // 6. 리스너(Subscriber) 동작 및 FCM 발송 검증
        coEvery { fcmClient.sendMulticast(any(), any(), any(), any()) } returns Unit

        // 각 유저별로 발행된 메시지를 수신하는 시뮬레이션
        users.forEach { user ->
            val mockRecord = MapRecord.create(
                "meeting_notification_stream", 
                mapOf(
                    "meetingId" to meeting.id.toString(),
                    "userId" to user.id.toString()
                )
            ).withId(RecordId.of("123-${user.id}"))

            meetingNotificationConsumer.onMessage(mockRecord)
            
            // 최종 검증: 각 참여자별로 알림이 발송되었는가?
            coVerify(timeout = 5000) { 
                fcmClient.sendMulticast(
                    tokens = match { it.contains("token-${user.id}") && it.size == 1 },
                    title = any(),
                    body = any(),
                    data = any()
                )
            }
        }
    }
}