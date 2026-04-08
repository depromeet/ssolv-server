package org.depromeet.team3.survey.application

import kotlinx.coroutines.test.runTest
import org.depromeet.team3.annotation.UnitTest
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.fixture.MeetingAttendeeFixture
import org.depromeet.team3.fixture.MeetingFixture
import org.depromeet.team3.fixture.StationFixture
import org.depromeet.team3.fixture.SurveyCategoryFixture
import org.depromeet.team3.fixture.SurveyFixture
import org.depromeet.team3.fixture.UserFixture
import org.depromeet.team3.survey.fixture.SurveyRequestFixture
import org.depromeet.team3.meeting.MeetingJpaRepository
import org.depromeet.team3.meeting.application.MeetingExpirationSchedulerService
import org.depromeet.team3.meetingattendee.MeetingAttendeeJpaRepository
import org.depromeet.team3.survey.SurveyEntity
import org.depromeet.team3.survey.SurveyJpaRepository
import org.depromeet.team3.survey.exception.SurveyException
import org.depromeet.team3.surveycategory.SurveyCategoryJpaRepository
import org.depromeet.team3.surveycategory.SurveyCategoryLevel
import org.depromeet.team3.surveyresult.SurveyResultJpaRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mock
import org.mockito.Mockito.lenient
import org.mockito.kotlin.*
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
import java.util.Optional
import kotlin.test.assertEquals

@UnitTest
@DisplayName("[SURVEY] 설문 생성 서비스 테스트")
class CreateSurveyServiceTest {

    @Mock private lateinit var surveyJpaRepository: SurveyJpaRepository
    @Mock private lateinit var surveyResultJpaRepository: SurveyResultJpaRepository
    @Mock private lateinit var surveyCategoryJpaRepository: SurveyCategoryJpaRepository
    @Mock private lateinit var meetingJpaRepository: MeetingJpaRepository
    @Mock private lateinit var meetingAttendeeJpaRepository: MeetingAttendeeJpaRepository
    @Mock private lateinit var stringRedisTemplate: org.springframework.data.redis.core.StringRedisTemplate
    @Mock private lateinit var meetingExpirationSchedulerService: MeetingExpirationSchedulerService

    private lateinit var transactionTemplate: TransactionTemplate
    private lateinit var createSurveyService: CreateSurveyService

    private val meetingId = 1L
    private val userId = 1L
    private val hostUser = UserFixture.createEntity(id = 99L)
    private val station = StationFixture.createEntity(id = 1L)
    private val meetingEntity = MeetingFixture.createEntity(id = meetingId, hostUser = hostUser, station = station)
    private val attendeeEntity = MeetingAttendeeFixture.createEntity(
        id = 10L, meeting = meetingEntity, user = UserFixture.createEntity(id = userId)
    )
    private val savedSurveyEntity = SurveyFixture.createEntity(id = 1L, meeting = meetingEntity, participant = attendeeEntity)

    @BeforeEach
    fun setUp() {
        transactionTemplate = mock()
        whenever(transactionTemplate.execute(any<TransactionCallback<Any>>())).thenAnswer { invocation ->
            val callback = invocation.getArgument<TransactionCallback<Any>>(0)
            callback.doInTransaction(mock())
        }
        lenient().whenever(surveyJpaRepository.save(any<SurveyEntity>())).thenReturn(savedSurveyEntity)
        createSurveyService = CreateSurveyService(
            surveyJpaRepository, surveyResultJpaRepository, surveyCategoryJpaRepository,
            meetingJpaRepository, meetingAttendeeJpaRepository, transactionTemplate,
            stringRedisTemplate, meetingExpirationSchedulerService
        )
    }

    @Test
    @DisplayName("설문을 성공적으로 생성한다")
    fun `설문을 성공적으로 생성한다`() = runTest {
        // given
        val branchCat = SurveyCategoryFixture.createEntity(id = 1L, level = SurveyCategoryLevel.BRANCH, name = "한식")
        val leafCat = SurveyCategoryFixture.createEntity(id = 3L, level = SurveyCategoryLevel.LEAF, name = "국밥", parent = branchCat)
        val request = SurveyRequestFixture.createRequest(selectedCategoryList = listOf(1L, 3L))

        whenever(meetingJpaRepository.findById(meetingId)).thenReturn(Optional.of(meetingEntity))
        whenever(meetingAttendeeJpaRepository.findByMeetingIdAndUserId(meetingId, userId)).thenReturn(attendeeEntity)
        whenever(surveyJpaRepository.existsByMeetingIdAndParticipantId(meetingId, attendeeEntity.id!!)).thenReturn(false)
        whenever(surveyCategoryJpaRepository.findAllById(listOf(1L, 3L))).thenReturn(mutableListOf(branchCat, leafCat))

        // when
        createSurveyService.invoke(meetingId, userId, request)

        // then
        verify(surveyJpaRepository).save(any<SurveyEntity>())
        verify(surveyResultJpaRepository).saveAll(any<List<org.depromeet.team3.surveyresult.SurveyResultEntity>>())
    }

    @Test
    @DisplayName("이미 설문을 제출한 사용자인 경우 예외가 발생한다")
    fun `중복 제출 시 예외가 발생한다`() = runTest {
        val request = SurveyRequestFixture.createMinimalRequest()
        whenever(meetingJpaRepository.findById(meetingId)).thenReturn(Optional.of(meetingEntity))
        whenever(meetingAttendeeJpaRepository.findByMeetingIdAndUserId(meetingId, userId)).thenReturn(attendeeEntity)
        whenever(surveyJpaRepository.existsByMeetingIdAndParticipantId(meetingId, attendeeEntity.id!!)).thenReturn(true)

        val exception = assertThrows<SurveyException> { createSurveyService.invoke(meetingId, userId, request) }
        assertEquals(ErrorCode.SURVEY_ALREADY_SUBMITTED, exception.errorCode)
    }

    @Test
    @DisplayName("빈 카테고리 목록으로 설문 생성 시 예외가 발생한다")
    fun `빈 카테고리 목록으로 설문 생성 시 예외가 발생한다`() = runTest {
        val request = SurveyRequestFixture.createEmptyRequest()
        whenever(meetingJpaRepository.findById(meetingId)).thenReturn(Optional.of(meetingEntity))
        whenever(meetingAttendeeJpaRepository.findByMeetingIdAndUserId(meetingId, userId)).thenReturn(attendeeEntity)
        whenever(surveyJpaRepository.existsByMeetingIdAndParticipantId(meetingId, attendeeEntity.id!!)).thenReturn(false)

        val exception = assertThrows<SurveyException> { createSurveyService.invoke(meetingId, userId, request) }
        assertEquals(ErrorCode.INVALID_PARAMETER, exception.errorCode)
    }

    @Test
    @DisplayName("존재하지 않는 설문 카테고리로 설문 생성 시 예외가 발생한다")
    fun `존재하지 않는 카테고리 포함 시 예외가 발생한다`() = runTest {
        val request = SurveyRequestFixture.createRequest(selectedCategoryList = listOf(999L))
        whenever(meetingJpaRepository.findById(meetingId)).thenReturn(Optional.of(meetingEntity))
        whenever(meetingAttendeeJpaRepository.findByMeetingIdAndUserId(meetingId, userId)).thenReturn(attendeeEntity)
        whenever(surveyJpaRepository.existsByMeetingIdAndParticipantId(meetingId, attendeeEntity.id!!)).thenReturn(false)
        whenever(surveyCategoryJpaRepository.findAllById(listOf(999L))).thenReturn(mutableListOf())

        val exception = assertThrows<SurveyException> { createSurveyService.invoke(meetingId, userId, request) }
        assertEquals(ErrorCode.SURVEY_CATEGORY_NOT_FOUND, exception.errorCode)
    }

    @Test
    @DisplayName("LEAF 카테고리를 선택했는데 부모 BRANCH 카테고리가 선택되지 않은 경우 예외가 발생한다")
    fun `LEAF 카테고리만 선택 시 예외가 발생한다`() = runTest {
        val branchCat = SurveyCategoryFixture.createEntity(id = 1L, level = SurveyCategoryLevel.BRANCH, name = "음식")
        val leafCat = SurveyCategoryFixture.createEntity(id = 2L, level = SurveyCategoryLevel.LEAF, name = "한식", parent = branchCat)
        val request = SurveyRequestFixture.createRequest(selectedCategoryList = listOf(2L))

        whenever(meetingJpaRepository.findById(meetingId)).thenReturn(Optional.of(meetingEntity))
        whenever(meetingAttendeeJpaRepository.findByMeetingIdAndUserId(meetingId, userId)).thenReturn(attendeeEntity)
        whenever(surveyJpaRepository.existsByMeetingIdAndParticipantId(meetingId, attendeeEntity.id!!)).thenReturn(false)
        whenever(surveyCategoryJpaRepository.findAllById(listOf(2L))).thenReturn(mutableListOf(leafCat))

        val exception = assertThrows<SurveyException> { createSurveyService.invoke(meetingId, userId, request) }
        assertEquals(ErrorCode.SURVEY_BRANCH_CATEGORY_REQUIRED, exception.errorCode)
    }

    @Test
    @DisplayName("LEAF 카테고리를 5개 초과로 선택하면 예외가 발생한다")
    fun `LEAF 카테고리 5개 초과 시 예외가 발생한다`() = runTest {
        val cats = (11L..16L).map { SurveyCategoryFixture.createEntity(id = it, level = SurveyCategoryLevel.LEAF) }
        val ids = cats.mapNotNull { it.id }
        val request = SurveyRequestFixture.createRequest(selectedCategoryList = ids)

        whenever(meetingJpaRepository.findById(meetingId)).thenReturn(Optional.of(meetingEntity))
        whenever(meetingAttendeeJpaRepository.findByMeetingIdAndUserId(meetingId, userId)).thenReturn(attendeeEntity)
        whenever(surveyJpaRepository.existsByMeetingIdAndParticipantId(meetingId, attendeeEntity.id!!)).thenReturn(false)
        whenever(surveyCategoryJpaRepository.findAllById(ids)).thenReturn(cats.toMutableList())

        val exception = assertThrows<SurveyException> { createSurveyService.invoke(meetingId, userId, request) }
        assertEquals(ErrorCode.SURVEY_LEAF_CATEGORY_LIMIT_EXCEEDED, exception.errorCode)
    }

    @Test
    @DisplayName("LEAF 카테고리를 정확히 5개 선택하면 설문 생성에 성공한다")
    fun `LEAF 카테고리 5개 선택 시 성공한다`() = runTest {
        val parent = SurveyCategoryFixture.createEntity(id = 100L, level = SurveyCategoryLevel.BRANCH)
        val cats = (11L..15L).map { SurveyCategoryFixture.createEntity(id = it, level = SurveyCategoryLevel.LEAF, parent = parent) }
        val allCats = cats + parent
        val ids = allCats.mapNotNull { it.id }
        val request = SurveyRequestFixture.createRequest(selectedCategoryList = ids)

        whenever(meetingJpaRepository.findById(meetingId)).thenReturn(Optional.of(meetingEntity))
        whenever(meetingAttendeeJpaRepository.findByMeetingIdAndUserId(meetingId, userId)).thenReturn(attendeeEntity)
        whenever(surveyJpaRepository.existsByMeetingIdAndParticipantId(meetingId, attendeeEntity.id!!)).thenReturn(false)
        whenever(surveyCategoryJpaRepository.findAllById(ids)).thenReturn(allCats.toMutableList())

        createSurveyService.invoke(meetingId, userId, request)

        verify(surveyJpaRepository).save(any<SurveyEntity>())
    }
}
