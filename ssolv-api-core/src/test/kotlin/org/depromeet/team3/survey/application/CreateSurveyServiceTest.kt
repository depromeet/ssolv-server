package org.depromeet.team3.survey.application

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.common.util.CoroutineDispatchers
import org.depromeet.team3.meetingattendee.MeetingAttendeeJpaRepository
import org.depromeet.team3.meeting.MeetingJpaRepository
import org.depromeet.team3.survey.Survey
import org.depromeet.team3.survey.SurveyRepository
import org.depromeet.team3.survey.dto.request.SurveyCreateRequest
import org.depromeet.team3.survey.exception.SurveyException
import org.depromeet.team3.survey.util.SurveyTestDataFactory
import org.depromeet.team3.surveycategory.SurveyCategory
import org.depromeet.team3.surveycategory.SurveyCategoryRepository
import org.depromeet.team3.surveycategory.SurveyCategoryLevel
import org.depromeet.team3.surveyresult.SurveyResultRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.transaction.support.TransactionTemplate
import kotlin.test.assertEquals

@ExtendWith(MockitoExtension::class)
@DisplayName("[SURVEY] 설문 생성 서비스 테스트")
class CreateSurveyServiceTest {

    @Mock
    private lateinit var surveyRepository: SurveyRepository

    @Mock
    private lateinit var surveyResultRepository: SurveyResultRepository

    @Mock
    private lateinit var surveyCategoryRepository: SurveyCategoryRepository

    @Mock
    private lateinit var meetingJpaRepository: MeetingJpaRepository

    @Mock
    private lateinit var meetingAttendeeJpaRepository: MeetingAttendeeJpaRepository

    private lateinit var transactionTemplate: TransactionTemplate
    private lateinit var coroutineDispatchers: CoroutineDispatchers
    private lateinit var createSurveyService: CreateSurveyService

    @BeforeEach
    fun setUp() {
        coroutineDispatchers = mock()
        whenever(coroutineDispatchers.VT).thenReturn(Dispatchers.Unconfined)
        transactionTemplate = mock()
        whenever(transactionTemplate.execute<Any>(any())).thenAnswer { invocation ->
            val callback = invocation.getArgument<org.springframework.transaction.support.TransactionCallback<Any>>(0)
            callback.doInTransaction(mock())
        }
        createSurveyService = CreateSurveyService(
            surveyRepository,
            surveyResultRepository,
            surveyCategoryRepository,
            meetingJpaRepository,
            meetingAttendeeJpaRepository,
            transactionTemplate,
            coroutineDispatchers
        )
    }

    @Test
    @DisplayName("설문을 성공적으로 생성한다")
    fun `설문을 성공적으로 생성한다`() {
        runBlocking {
            // given
            val meetingId = 1L
            val userId = 1L
            val participantId = 1L
            
            val testScenario = SurveyTestDataFactory.createSurveyTestScenario(
                meetingId = meetingId,
                participantId = participantId
            )

            // Mock 설정
            whenever(meetingJpaRepository.existsById(meetingId)).thenReturn(true)
            whenever(meetingAttendeeJpaRepository.existsByMeetingIdAndUserId(meetingId, participantId)).thenReturn(true)
            whenever(surveyRepository.existsByMeetingIdAndParticipantId(meetingId, participantId)).thenReturn(false)
            whenever(surveyRepository.save(any())).thenReturn(testScenario.survey)
            // 5번 카테고리도 추가 (요청된 카테고리 수와 일치하도록)
            val category5 = SurveyTestDataFactory.createSurveyCategory(id = 5L, name = "기타")
            whenever(surveyCategoryRepository.findAllById(testScenario.request.selectedCategoryList))
                .thenReturn(listOf(testScenario. cuisineCategory, testScenario.japaneseCuisineCategory, category5))
            whenever(surveyResultRepository.saveAll(any())).thenReturn(emptyList())

            // when
            val result = createSurveyService.invoke(meetingId, userId, testScenario.request)

            // then
            assertEquals("설문 제출이 완료되었습니다", result.message)
            verify(surveyRepository).save(any())
            verify(surveyResultRepository).saveAll(any())
        }
    }

    @Test
    @DisplayName("존재하지 않는 모임에 설문 생성 시 예외가 발생한다")
    fun `존재하지 않는 모임에 설문 생성 시 예외가 발생한다`() {
        runBlocking {
            // given
            val meetingId = 999L
            val userId = 1L
            val request = SurveyTestDataFactory.createMinimalSurveyCreateRequest()

            whenever(meetingJpaRepository.existsById(meetingId)).thenReturn(false)

            // when & then
            try {
                createSurveyService.invoke(meetingId, userId, request)
                org.junit.jupiter.api.fail("Should have thrown SurveyException")
            } catch (e: SurveyException) {
                assertEquals(ErrorCode.MEETING_NOT_FOUND, e.errorCode)
            }
        }
    }

    @Test
    @DisplayName("존재하지 않는 참가자가 설문 생성 시 예외가 발생한다")
    fun `존재하지 않는 참가자가 설문 생성 시 예외가 발생한다`() {
        runBlocking {
            // given
            val meetingId = 1L
            val userId = 999L
            val request = SurveyTestDataFactory.createMinimalSurveyCreateRequest()

            whenever(meetingJpaRepository.existsById(meetingId)).thenReturn(true)
            whenever(meetingAttendeeJpaRepository.existsByMeetingIdAndUserId(meetingId, userId)).thenReturn(false)

            // when & then
            val exception = assertThrows<SurveyException> {
                runBlocking {
                    createSurveyService.invoke(meetingId, userId, request)
                }
            }

            assertEquals(ErrorCode.PARTICIPANT_NOT_FOUND, exception.errorCode)
        }
    }

    @Test
    @DisplayName("중복 설문 제출 시 예외가 발생한다")
    fun `중복 설문 제출 시 예외가 발생한다`() {
        runBlocking {
            // given
            val meetingId = 1L
            val userId = 1L
            val request = SurveyTestDataFactory.createMinimalSurveyCreateRequest()

            whenever(meetingJpaRepository.existsById(meetingId)).thenReturn(true)
            whenever(meetingAttendeeJpaRepository.existsByMeetingIdAndUserId(meetingId, userId)).thenReturn(true)
            whenever(surveyRepository.existsByMeetingIdAndParticipantId(meetingId, userId)).thenReturn(true)

            // when & then
            try {
                createSurveyService.invoke(meetingId, userId, request)
                org.junit.jupiter.api.fail("Should have thrown SurveyException")
            } catch (e: SurveyException) {
                assertEquals(ErrorCode.SURVEY_ALREADY_SUBMITTED, e.errorCode)
            }
        }
    }

    @Test
    @DisplayName("다른 사용자의 설문을 제출하려고 할 때 예외가 발생한다")
    fun `다른 사용자의 설문을 제출하려고 할 때 예외가 발생한다`() {
        runBlocking {
            // given
            val meetingId = 1L
            val userId = 1L
            val request = SurveyTestDataFactory.createSurveyCreateRequest()

            whenever(meetingJpaRepository.existsById(meetingId)).thenReturn(true)
            whenever(meetingAttendeeJpaRepository.existsByMeetingIdAndUserId(meetingId, userId)).thenReturn(false)

            // when & then
            val exception = assertThrows<SurveyException> {
                runBlocking {
                    createSurveyService.invoke(meetingId, userId, request)
                }
            }

            assertEquals(ErrorCode.PARTICIPANT_NOT_FOUND, exception.errorCode)
        }
    }

    @Test
    @DisplayName("존재하지 않는 설문 카테고리로 설문 생성 시 예외가 발생한다")
    fun `존재하지 않는 설문 카테고리로 설문 생성 시 예외가 발생한다`() {
        runBlocking {
            // given
            val meetingId = 1L
            val userId = 1L
            val request = SurveyTestDataFactory.createSurveyCreateRequest(selectedCategoryList = listOf(999L))

            whenever(meetingJpaRepository.existsById(meetingId)).thenReturn(true)
            whenever(meetingAttendeeJpaRepository.existsByMeetingIdAndUserId(meetingId, userId)).thenReturn(true)
            whenever(surveyRepository.existsByMeetingIdAndParticipantId(meetingId, userId)).thenReturn(false)
            whenever(surveyRepository.save(any())).thenReturn(
                SurveyTestDataFactory.createSurvey(meetingId = meetingId, participantId = userId)
            )
            whenever(surveyCategoryRepository.findAllById(listOf(999L))).thenReturn(emptyList())

            // when & then
            try {
                createSurveyService.invoke(meetingId, userId, request)
                org.junit.jupiter.api.fail("Should have thrown SurveyException")
            } catch (e: SurveyException) {
                assertEquals(ErrorCode.SURVEY_CATEGORY_NOT_FOUND, e.errorCode)
            }
        }
    }

    @Test
    @DisplayName("빈 카테고리 목록으로 설문 생성 시 예외가 발생한다")
    fun `빈 카테고리 목록으로 설문 생성 시 예외가 발생한다`() {
        runBlocking {
            // given
            val meetingId = 1L
            val userId = 1L
            val request = SurveyTestDataFactory.createEmptySurveyCreateRequest()

            whenever(meetingJpaRepository.existsById(meetingId)).thenReturn(true)
            whenever(meetingAttendeeJpaRepository.existsByMeetingIdAndUserId(meetingId, userId)).thenReturn(true)
            whenever(surveyRepository.existsByMeetingIdAndParticipantId(meetingId, userId)).thenReturn(false)
            whenever(surveyRepository.save(any())).thenReturn(
                SurveyTestDataFactory.createSurvey(meetingId = meetingId, participantId = userId)
            )

            // when & then
            try {
                createSurveyService.invoke(meetingId, userId, request)
                org.junit.jupiter.api.fail("Should have thrown SurveyException")
            } catch (e: SurveyException) {
                assertEquals(ErrorCode.INVALID_PARAMETER, e.errorCode)
            }
        }
    }

    @Test
    @DisplayName("LEAF 카테고릴를 선택했는데 부모 BRANCH 카테고리가 선택되지 않은 경우 예외가 발생한다")
    fun `LEAF 카테고릴를 선택했는데 부모 BRANCH 카테고리가 선택되지 않은 경우 예외가 발생한다`() {
        runBlocking {
            // given
            val meetingId = 1L
            val userId = 1L
            
            // BRANCH 카테고리 (id: 1)와 그 자식 LEAF 카테고리 (id: 2) 생성
            val branchCategory = SurveyTestDataFactory.createSurveyCategory(
                id = 1L,
                parentId = null,
                level = SurveyCategoryLevel.BRANCH,
                name = "음식"
            )
            val leafCategory = SurveyTestDataFactory.createSurveyCategory(
                id = 2L,
                parentId = 1L,
                level = SurveyCategoryLevel.LEAF,
                name = "한식"
            )
            
            // LEAF만 선택하고 BRANCH는 선택하지 않은 요청
            val request = SurveyTestDataFactory.createSurveyCreateRequest(selectedCategoryList = listOf(2L))

            whenever(meetingJpaRepository.existsById(meetingId)).thenReturn(true)
            whenever(meetingAttendeeJpaRepository.existsByMeetingIdAndUserId(meetingId, userId)).thenReturn(true)
            whenever(surveyRepository.existsByMeetingIdAndParticipantId(meetingId, userId)).thenReturn(false)
            whenever(surveyRepository.save(any())).thenReturn(
                SurveyTestDataFactory.createSurvey(meetingId = meetingId, participantId = userId)
            )
            whenever(surveyCategoryRepository.findAllById(listOf(2L))).thenReturn(listOf(leafCategory))

            // when & then
            try {
                createSurveyService.invoke(meetingId, userId, request)
                org.junit.jupiter.api.fail("Should have thrown SurveyException")
            } catch (e: SurveyException) {
                assertEquals(ErrorCode.SURVEY_BRANCH_CATEGORY_REQUIRED, e.errorCode)
            }
        }
    }

    @Test
    @DisplayName("LEAF 카테고리와 부모 BRANCH 카테고리를 함께 선택하면 설문 생성에 성공한다")
    fun `LEAF 카테고리와 부모 BRANCH 카테고리를 함께 선택하면 설문 생성에 성공한다`() {
        runBlocking {
            // given
            val meetingId = 1L
            val userId = 1L
            
            // BRANCH 카테고리 (id: 1)와 그 자식 LEAF 카테고리 (id: 2) 생성
            val branchCategory = SurveyTestDataFactory.createSurveyCategory(
                id = 1L,
                parentId = null,
                level = SurveyCategoryLevel.BRANCH,
                name = "음식"
            )
            val leafCategory = SurveyTestDataFactory.createSurveyCategory(
                id = 2L,
                parentId = 1L,
                level = SurveyCategoryLevel.LEAF,
                name = "한식"
            )
            
            // BRANCH와 LEAF를 함께 선택한 요청
            val request = SurveyTestDataFactory.createSurveyCreateRequest(selectedCategoryList = listOf(1L, 2L))

            whenever(meetingJpaRepository.existsById(meetingId)).thenReturn(true)
            whenever(meetingAttendeeJpaRepository.existsByMeetingIdAndUserId(meetingId, userId)).thenReturn(true)
            whenever(surveyRepository.existsByMeetingIdAndParticipantId(meetingId, userId)).thenReturn(false)
            whenever(surveyRepository.save(any())).thenReturn(
                SurveyTestDataFactory.createSurvey(meetingId = meetingId, participantId = userId)
            )
            whenever(surveyCategoryRepository.findAllById(listOf(1L, 2L))).thenReturn(listOf(branchCategory, leafCategory))
            whenever(surveyResultRepository.saveAll(any())).thenReturn(emptyList())

            // when
            val result = createSurveyService.invoke(meetingId, userId, request)

            // then
            assertEquals("설문 제출이 완료되었습니다", result.message)
            verify(surveyRepository).save(any())
            verify(surveyResultRepository).saveAll(any())
        }
    }

    @Test
    @DisplayName("participantId가 attendeeId로 와도 동일 사용자라면 설문 생성에 성공한다")
    fun `attendeeId 로 전송해도 성공`() {
        runBlocking {
            // given
            val meetingId = 10L
            val userId = 42L

            val request = SurveyTestDataFactory.createMinimalSurveyCreateRequest()

            // Survey 저장 결과
            val savedSurvey = Survey(
                id = 100L,
                meetingId = meetingId,
                participantId = userId
            )

            // Mock 설정
            whenever(meetingJpaRepository.existsById(meetingId)).thenReturn(true)
            whenever(meetingAttendeeJpaRepository.existsByMeetingIdAndUserId(meetingId, userId)).thenReturn(true)
            whenever(surveyRepository.existsByMeetingIdAndParticipantId(meetingId, userId)).thenReturn(false)
            whenever(surveyRepository.save(any())).thenReturn(savedSurvey)
            whenever(surveyCategoryRepository.findAllById(request.selectedCategoryList))
                .thenReturn(listOf(SurveyTestDataFactory.createSurveyCategory(id = 1L)))
            whenever(surveyResultRepository.saveAll(any())).thenReturn(emptyList())

            // when
            val result = createSurveyService.invoke(meetingId, userId, request)

            // then
            assertEquals("설문 제출이 완료되었습니다", result.message)
            verify(surveyRepository).save(any())
            verify(surveyResultRepository).saveAll(any())
        }
    }

    @Test
    @DisplayName("LEAF 카테고리를 5개 초과로 선택하면 예외가 발생한다")
    fun `LEAF 카테고리를 5개 초과로 선택하면 예외가 발생한다`() {
        runBlocking {
            // given
            val meetingId = 1L
            val userId = 1L
            
            // BRANCH 카테고리 1개와 LEAF 카테고리 6개 생성
            val branchCategory = SurveyTestDataFactory.createSurveyCategory(
                id = 1L,
                parentId = null,
                level = SurveyCategoryLevel.BRANCH,
                name = "음식"
            )
            val leafCategories = (2L..7L).map { id ->
                SurveyTestDataFactory.createSurveyCategory(
                    id = id,
                    parentId = 1L,
                    level = SurveyCategoryLevel.LEAF,
                    name = "카테고리$id"
                )
            }
            
            // BRANCH 1개와 LEAF 6개를 선택한 요청 (총 7개)
            val request = SurveyTestDataFactory.createSurveyCreateRequest(
                selectedCategoryList = listOf(1L) + leafCategories.mapNotNull { it.id }
            )

            whenever(meetingJpaRepository.existsById(meetingId)).thenReturn(true)
            whenever(meetingAttendeeJpaRepository.existsByMeetingIdAndUserId(meetingId, userId)).thenReturn(true)
            whenever(surveyRepository.existsByMeetingIdAndParticipantId(meetingId, userId)).thenReturn(false)
            whenever(surveyRepository.save(any())).thenReturn(
                SurveyTestDataFactory.createSurvey(meetingId = meetingId, participantId = userId)
            )
            whenever(surveyCategoryRepository.findAllById(request.selectedCategoryList))
                .thenReturn(listOf(branchCategory) + leafCategories)

            // when & then
            try {
                createSurveyService.invoke(meetingId, userId, request)
                org.junit.jupiter.api.fail("Should have thrown SurveyException")
            } catch (e: SurveyException) {
                assertEquals(ErrorCode.SURVEY_LEAF_CATEGORY_LIMIT_EXCEEDED, e.errorCode)
            }
        }
    }

    @Test
    @DisplayName("LEAF 카테고리를 정확히 5개 선택하면 설문 생성에 성공한다")
    fun `LEAF 카테고리를 정확히 5개 선택하면 설문 생성에 성공한다`() {
        runBlocking {
            // given
            val meetingId = 1L
            val userId = 1L
            
            // BRANCH 카테고리 1개와 LEAF 카테고리 5개 생성
            val branchCategory = SurveyTestDataFactory.createSurveyCategory(
                id = 1L,
                parentId = null,
                level = SurveyCategoryLevel.BRANCH,
                name = "음식"
            )
            val leafCategories = (2L..6L).map { id ->
                SurveyTestDataFactory.createSurveyCategory(
                    id = id,
                    parentId = 1L,
                    level = SurveyCategoryLevel.LEAF,
                    name = "카테고리$id"
                )
            }
            
            // BRANCH 1개와 LEAF 5개를 선택한 요청 (총 6개)
            val request = SurveyTestDataFactory.createSurveyCreateRequest(
                selectedCategoryList = listOf(1L) + leafCategories.mapNotNull { it.id }
            )

            whenever(meetingJpaRepository.existsById(meetingId)).thenReturn(true)
            whenever(meetingAttendeeJpaRepository.existsByMeetingIdAndUserId(meetingId, userId)).thenReturn(true)
            whenever(surveyRepository.existsByMeetingIdAndParticipantId(meetingId, userId)).thenReturn(false)
            whenever(surveyRepository.save(any())).thenReturn(
                SurveyTestDataFactory.createSurvey(meetingId = meetingId, participantId = userId)
            )
            whenever(surveyCategoryRepository.findAllById(request.selectedCategoryList))
                .thenReturn(listOf(branchCategory) + leafCategories)
            whenever(surveyResultRepository.saveAll(any())).thenReturn(emptyList())

            // when
            val result = createSurveyService.invoke(meetingId, userId, request)

            // then
            assertEquals("설문 제출이 완료되었습니다", result.message)
            verify(surveyRepository).save(any())
            verify(surveyResultRepository).saveAll(any())
        }
    }
}
