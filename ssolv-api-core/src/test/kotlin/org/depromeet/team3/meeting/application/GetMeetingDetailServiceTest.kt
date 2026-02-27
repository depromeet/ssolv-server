package org.depromeet.team3.meeting.application

import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.meeting.MeetingRepository
import org.depromeet.team3.meeting.dto.response.*
import org.depromeet.team3.meeting.exception.MeetingException
import org.depromeet.team3.meeting.util.MeetingTestDataFactory
import org.depromeet.team3.meetingattendee.MeetingAttendeeRepository
import org.depromeet.team3.meetingattendee.MuzziColor
import org.depromeet.team3.meetingattendee.util.MeetingAttendeeTestDataFactory
import org.depromeet.team3.station.StationRepository
import org.depromeet.team3.station.util.StationTestDataFactory
import org.depromeet.team3.survey.SurveyRepository
import org.depromeet.team3.survey.util.SurveyTestDataFactory
import org.depromeet.team3.surveycategory.SurveyCategoryRepository
import org.depromeet.team3.surveycategory.util.SurveyCategoryTestDataFactory
import org.depromeet.team3.surveyresult.SurveyResultRepository
import org.depromeet.team3.surveyresult.util.SurveyResultTestDataFactory
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.whenever
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
@DisplayName("[MEETING] 모임 상세 조회 서비스 테스트")
class GetMeetingDetailServiceTest {

    @Mock
    private lateinit var meetingRepository: MeetingRepository

    @Mock
    private lateinit var stationRepository: StationRepository

    @Mock
    private lateinit var meetingAttendeeRepository: MeetingAttendeeRepository

    @Mock
    private lateinit var surveyRepository: SurveyRepository

    @Mock
    private lateinit var surveyResultRepository: SurveyResultRepository

    @Mock
    private lateinit var surveyCategoryRepository: SurveyCategoryRepository
    
    @Mock
    private lateinit var inviteTokenService: InviteTokenService

    private lateinit var getMeetingDetailService: GetMeetingDetailService

    @BeforeEach
    fun setUp() {
        getMeetingDetailService = GetMeetingDetailService(
            meetingRepository,
            stationRepository,
            meetingAttendeeRepository,
            surveyRepository,
            surveyResultRepository,
            surveyCategoryRepository,
            inviteTokenService
        )
    }

    @Test
    @DisplayName("모임 상세 정보를 성공적으로 조회한다")
    fun `모임 상세 정보를 성공적으로 조회한다`() {
        // given
        val meetingId = 1L
        val userId = 234L
        val now = LocalDateTime.now()
        val meetingEndAt = now.plusDays(1)
        val meetingCreatedAt = now.minusDays(2)
        val meetingUpdatedAt = now.minusDays(1)
        val meeting = MeetingTestDataFactory.createMeeting(
            id = meetingId,
            name = "점심 메뉴 정하기",
            hostUserId = 123L,
            attendeeCount = 8,
            isClosed = false,
            stationId = 1L,
            endAt = meetingEndAt,
            createdAt = meetingCreatedAt,
            updatedAt = meetingUpdatedAt
        )

        val station = StationTestDataFactory.createStation(
            id = 1L,
            name = "강남"
        )

        val attendee1 = MeetingAttendeeTestDataFactory.createMeetingAttendee(
            id = 1L,
            meetingId = meetingId,
            userId = 456L,
            attendeeNickname = "아따맘마",
            muzziColor = MuzziColor.BANANA
        )

        val attendee2 = MeetingAttendeeTestDataFactory.createMeetingAttendee(
            id = 2L,
            meetingId = meetingId,
            userId = 789L,
            attendeeNickname = "아따맘마마마",
            muzziColor = MuzziColor.BROCCOLI
        )

        val survey1 = SurveyTestDataFactory.createSurvey(
            id = 1L,
            meetingId = meetingId,
            participantId = 456L
        )

        val survey2 = SurveyTestDataFactory.createSurvey(
            id = 2L,
            meetingId = meetingId,
            participantId = 789L
        )

        // Mock 설정
        whenever(meetingRepository.findById(meetingId)).thenReturn(meeting)
        whenever(stationRepository.findById(1L)).thenReturn(station)
        whenever(meetingAttendeeRepository.findByMeetingId(meetingId)).thenReturn(listOf(attendee1, attendee2))
        whenever(surveyRepository.findByMeetingId(meetingId)).thenReturn(listOf(survey1, survey2))
        whenever(surveyResultRepository.findBySurveyIdIn(listOf(1L, 2L))).thenReturn(emptyList())
        whenever(inviteTokenService.generateToken(meeting)).thenReturn("test-token")

        // when
        val result = getMeetingDetailService.invoke(meetingId, userId)

        // then
        assertEquals(userId, result.currentUserId)
        assertEquals("점심 메뉴 정하기", result.meetingInfo.title)
        assertEquals(123L, result.meetingInfo.hostUserId)
        assertEquals(8, result.meetingInfo.totalParticipantCnt)
        assertEquals("강남", result.meetingInfo.stationName)
        assertEquals(2, result.participantList.size)
        assertEquals(meetingEndAt, result.meetingInfo.endAt)
        assertEquals(meetingCreatedAt, result.meetingInfo.createdAt)
        assertEquals(meetingUpdatedAt, result.meetingInfo.updatedAt)
        assertEquals("test-token", result.meetingInfo.token)
        
        val participant1 = result.participantList.find { it.userId == 456L }
        assertNotNull(participant1)
        assertEquals("아따맘마", participant1.attendeeNickname)
        assertEquals("banana", participant1.color)
    }

    @Test
    @DisplayName("참가자의 설문 정보를 포함하여 조회한다")
    fun `참가자의 설문 정보를 포함하여 조회한다`() {
        // given
        val meetingId = 1L
        val userId = 234L
        val meetingEndAt = LocalDateTime.now().plusDays(1)
        val meeting = MeetingTestDataFactory.createMeeting(
            id = meetingId,
            name = "저녁 모임",
            hostUserId = 123L,
            attendeeCount = 2,
            isClosed = false,
            stationId = 1L,
            endAt = meetingEndAt
        )

        val station = StationTestDataFactory.createStation(
            id = 1L,
            name = "강남"
        )

        val attendee = MeetingAttendeeTestDataFactory.createMeetingAttendee(
            id = 1L,
            meetingId = meetingId,
            userId = 456L,
            attendeeNickname = "참가자1",
            muzziColor = MuzziColor.DEFAULT
        )

        val survey = SurveyTestDataFactory.createSurvey(
            id = 1L,
            meetingId = meetingId,
            participantId = 456L
        )

        // BRANCH 카테고리 (한식)
        val branchCategory = SurveyCategoryTestDataFactory.createBranchCategory(
            id = 1L,
            name = "한식"
        )

        // LEAF 카테고리들
        val leafCategory1 = SurveyCategoryTestDataFactory.createLeafCategory(
            id = 8L,
            parentId = 1L,
            name = "밥류"
        )

        val leafCategory2 = SurveyCategoryTestDataFactory.createLeafCategory(
            id = 9L,
            parentId = 1L,
            name = "구이·조림류"
        )

        val surveyResults = SurveyResultTestDataFactory.createSurveyResultList(
            surveyId = 1L,
            categoryIds = listOf(1L, 8L, 9L)
        )

        // Mock 설정
        whenever(meetingRepository.findById(meetingId)).thenReturn(meeting)
        whenever(stationRepository.findById(1L)).thenReturn(station)
        whenever(meetingAttendeeRepository.findByMeetingId(meetingId)).thenReturn(listOf(attendee))
        whenever(surveyRepository.findByMeetingId(meetingId)).thenReturn(listOf(survey))
        whenever(surveyResultRepository.findBySurveyIdIn(listOf(1L))).thenReturn(surveyResults)
        whenever(surveyCategoryRepository.findAllById(listOf(1L, 8L, 9L))).thenReturn(listOf(branchCategory, leafCategory1, leafCategory2))

        // when
        val result = getMeetingDetailService.invoke(meetingId, userId)

        // then
        assertTrue(result.participantList.isNotEmpty())
        val participant = result.participantList[0]
        assertTrue(participant.selectedCategories.isNotEmpty())
        
        val selectedCategory = participant.selectedCategories[0]
        assertEquals(1L, selectedCategory.id)
        assertEquals("한식", selectedCategory.name)
        assertEquals(2, selectedCategory.leafCategoryList.size)
        assertTrue(selectedCategory.leafCategoryList.any { it.id == 8L && it.name == "밥류" })
        assertTrue(selectedCategory.leafCategoryList.any { it.id == 9L && it.name == "구이·조림류" })
    }

    @Test
    @DisplayName("존재하지 않는 모임 조회 시 예외가 발생한다")
    fun `존재하지 않는 모임 조회 시 예외가 발생한다`() {
        // given
        val meetingId = 999L
        val userId = 234L

        whenever(meetingRepository.findById(meetingId)).thenReturn(null)

        // when & then
        val exception = assertThrows<MeetingException> {
            getMeetingDetailService.invoke(meetingId, userId)
        }

        assertEquals(ErrorCode.MEETING_NOT_FOUND, exception.errorCode)
    }

    @Test
    @DisplayName("설문이 있는 참가자만 participantList에 포함된다")
    fun `설문이 있는 참가자만 participantList에 포함된다`() {
        // given
        val meetingId = 1L
        val userId = 234L
        val meetingEndAt = LocalDateTime.now().plusDays(1)
        val meeting = MeetingTestDataFactory.createMeeting(
            id = meetingId,
            name = "테스트 모임",
            hostUserId = 123L,
            attendeeCount = 2,
            isClosed = false,
            stationId = 1L,
            endAt = meetingEndAt
        )

        val station = StationTestDataFactory.createStation(
            id = 1L,
            name = "강남"
        )

        val attendee1 = MeetingAttendeeTestDataFactory.createMeetingAttendee(
            id = 1L,
            meetingId = meetingId,
            userId = 456L,
            attendeeNickname = "설문한 참가자",
            muzziColor = MuzziColor.DEFAULT
        )

        val attendee2 = MeetingAttendeeTestDataFactory.createMeetingAttendee(
            id = 2L,
            meetingId = meetingId,
            userId = 789L,
            attendeeNickname = "설문 안 한 참가자",
            muzziColor = MuzziColor.BANANA
        )

        val survey1 = SurveyTestDataFactory.createSurvey(
            id = 1L,
            meetingId = meetingId,
            participantId = 456L
        )

        // Mock 설정
        whenever(meetingRepository.findById(meetingId)).thenReturn(meeting)
        whenever(stationRepository.findById(1L)).thenReturn(station)
        whenever(meetingAttendeeRepository.findByMeetingId(meetingId)).thenReturn(listOf(attendee1, attendee2))
        whenever(surveyRepository.findByMeetingId(meetingId)).thenReturn(listOf(survey1))
        whenever(surveyResultRepository.findBySurveyIdIn(listOf(1L))).thenReturn(emptyList())

        // when
        val result = getMeetingDetailService.invoke(meetingId, userId)

        // then
        assertEquals(1, result.participantList.size)

        val participant = result.participantList.first()
        assertEquals(456L, participant.userId)
        assertEquals("설문한 참가자", participant.attendeeNickname)
        assertTrue(participant.selectedCategories.isEmpty())
    }

    @Test
    fun `종료된 모임도 allowClosed true라면 정상 조회된다`() {
        // given
        val meetingId = 2L
        val userId = 999L
        val now = LocalDateTime.now()
        val meeting = MeetingTestDataFactory.createMeeting(
            id = meetingId,
            name = "종료 모임",
            hostUserId = 100L,
            attendeeCount = 1,
            isClosed = true,
            stationId = 3L,
            endAt = now.minusHours(1),
            createdAt = now.minusDays(3),
            updatedAt = now.minusMinutes(30)
        )

        val station = StationTestDataFactory.createStation(
            id = 3L,
            name = "강남"
        )

        val attendee = MeetingAttendeeTestDataFactory.createMeetingAttendee(
            id = 10L,
            meetingId = meetingId,
            userId = userId,
            attendeeNickname = "참가자",
            muzziColor = MuzziColor.DEFAULT
        )

        whenever(meetingRepository.findById(meetingId)).thenReturn(meeting)
        whenever(stationRepository.findById(3L)).thenReturn(station)
        whenever(meetingAttendeeRepository.findByMeetingId(meetingId)).thenReturn(listOf(attendee))
        whenever(surveyRepository.findByMeetingId(meetingId)).thenReturn(emptyList())

        // when
        val result = getMeetingDetailService.invoke(meetingId, userId, allowClosed = true)

        // then
        assertEquals(meetingId, result.meetingInfo.id)
        assertTrue(result.meetingInfo.isClosed)
        assertEquals("종료 모임", result.meetingInfo.title)
        assertTrue(result.participantList.isEmpty())
    }
}

