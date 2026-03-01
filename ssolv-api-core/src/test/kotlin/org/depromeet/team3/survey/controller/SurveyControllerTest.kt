package org.depromeet.team3.survey.controller

import com.fasterxml.jackson.databind.ObjectMapper
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.common.util.TestAuthHelper
import org.depromeet.team3.config.SecurityTestConfig
import org.depromeet.team3.survey.application.CreateSurveyService
import org.depromeet.team3.survey.dto.request.SurveyCreateRequest
import org.depromeet.team3.survey.dto.response.SurveyCreateResponse
import org.depromeet.team3.survey.exception.SurveyException
import org.depromeet.team3.survey.util.SurveyTestDataFactory
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import kotlinx.coroutines.test.runTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

@WebMvcTest(SurveyController::class)
@Import(SecurityTestConfig::class)
@ActiveProfiles("test")
@DisplayName("[SURVEY] 설문 컨트롤러 테스트")
class SurveyControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockitoBean
    private lateinit var createSurveyService: CreateSurveyService

    @BeforeEach
    fun setUp() {
        // 테스트용 사용자 인증 설정
        TestAuthHelper.setAuthenticatedUser(1L)
    }

    @Test
    @DisplayName("설문을 성공적으로 생성한다")
    fun `설문을 성공적으로 생성한다`() {
        runTest {
            // given
            val meetingId = 1L
            val request = SurveyTestDataFactory.createSurveyCreateRequest()
            val response = SurveyTestDataFactory.createSurveyCreateResponse()

            createSurveyService.stub {
                onBlocking { invoke(any(), any(), any()) }.doReturn(response)
            }

            // when & then
            val mvcResult = mockMvc.perform(
                post("/api/v1/meetings/$meetingId/surveys")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
                    .with(csrf())
            )
                .andExpect(request().asyncStarted())
                .andReturn()

            mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk)
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").exists())
                .andExpect(jsonPath("$.data.message").value("설문 제출이 완료되었습니다"))
                .andExpect(jsonPath("$.error").doesNotExist())
        }
    }

    @Test
    @DisplayName("존재하지 않는 모임에 설문 생성 시 404 에러가 발생한다")
    fun `존재하지 않는 모임에 설문 생성 시 404 에러가 발생한다`() {
        runTest {
            // given
            val meetingId = 999L
            val request = SurveyTestDataFactory.createMinimalSurveyCreateRequest()

            createSurveyService.stub {
                onBlocking { invoke(any(), any(), any()) }.doThrow(SurveyException(ErrorCode.MEETING_NOT_FOUND, mapOf("meetingId" to meetingId)))
            }

            // when & then
            val mvcResult = mockMvc.perform(
                post("/api/v1/meetings/$meetingId/surveys")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
                    .with(csrf())
            )
                .andExpect(request().asyncStarted())
                .andReturn()

            mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.error.code").value("C4043"))
        }
    }

    @Test
    @DisplayName("중복 설문 제출 시 409 에러가 발생한다")
    fun `중복 설문 제출 시 409 에러가 발생한다`() {
        runTest {
            // given
            val meetingId = 1L
            val request = SurveyTestDataFactory.createMinimalSurveyCreateRequest()

            createSurveyService.stub {
                onBlocking { invoke(any(), any(), any()) }.doThrow(SurveyException(ErrorCode.SURVEY_ALREADY_SUBMITTED, mapOf("participantId" to 1L)))
            }

            // when & then
            val mvcResult = mockMvc.perform(
                post("/api/v1/meetings/$meetingId/surveys")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
                    .with(csrf())
            )
                .andExpect(request().asyncStarted())
                .andReturn()

            mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.error.code").value("C4096"))
        }
    }

    @Test
    @DisplayName("잘못된 요청 데이터로 설문 생성 시 400 에러가 발생한다")
    fun `잘못된 요청 데이터로 설문 생성 시 400 에러가 발생한다`() {
        // given
        val meetingId = 1L
        val invalidRequest = SurveyTestDataFactory.createEmptySurveyCreateRequest()

        // when & then
        mockMvc.perform(
            post("/api/v1/meetings/$meetingId/surveys")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidRequest))
                .with(csrf())
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.data").doesNotExist())
            .andExpect(jsonPath("$.error").exists())
    }

    @Test
    @DisplayName("다른 사용자의 설문을 제출하려고 할 때 404 에러가 발생한다")
    fun `다른 사용자의 설문을 제출하려고 할 때 404 에러가 발생한다`() {
        runTest {
            // given
            val meetingId = 1L
            val request = SurveyTestDataFactory.createSurveyCreateRequest()

            // Mock 서비스가 PARTICIPANT_NOT_FOUND 예외를 던지도록 설정
            createSurveyService.stub {
                onBlocking { invoke(any(), any(), any()) }.doThrow(SurveyException(ErrorCode.PARTICIPANT_NOT_FOUND, mapOf("userId" to 999L)))
            }

            // when & then
            val mvcResult = mockMvc.perform(
                post("/api/v1/meetings/$meetingId/surveys")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
                    .with(csrf())
            )
                .andExpect(request().asyncStarted())
                .andReturn()

            mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.error.code").value("C4044"))
        }
    }

    @Test
    @DisplayName("존재하지 않는 설문 카테고리로 설문 생성 시 404 에러가 발생한다")
    fun `존재하지 않는 설문 카테고리로 설문 생성 시 404 에러가 발생한다`() {
        runTest {
            // given
            val meetingId = 1L
            val request = SurveyTestDataFactory.createSurveyCreateRequest(selectedCategoryList = listOf(999L))

            createSurveyService.stub {
                onBlocking { invoke(any(), any(), any()) }.doThrow(SurveyException(ErrorCode.SURVEY_CATEGORY_NOT_FOUND, mapOf("categoryId" to 999L)))
            }

            // when & then
            val mvcResult = mockMvc.perform(
                post("/api/v1/meetings/$meetingId/surveys")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
                    .with(csrf())
            )
                .andExpect(request().asyncStarted())
                .andReturn()

            mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.error.code").value("C4047"))
        }
    }

    @Test
    @DisplayName("LEAF 카테고리만 선택하고 BRANCH 카테고리를 선택하지 않은 경우 400 에러가 발생한다")
    fun `LEAF 카테고리만 선택하고 BRANCH 카테고리를 선택하지 않은 경우 400 에러가 발생한다`() {
        runTest {
            // given
            val meetingId = 1L
            val request = SurveyTestDataFactory.createSurveyCreateRequest(selectedCategoryList = listOf(2L))

            createSurveyService.stub {
                onBlocking { invoke(any(), any(), any()) }.doThrow(SurveyException(ErrorCode.SURVEY_BRANCH_CATEGORY_REQUIRED, mapOf(
                    "leafCategoryId" to 2L,
                    "leafCategoryName" to "한식",
                    "requiredBranchCategoryId" to 1L
                )))
            }

            // when & then
            val mvcResult = mockMvc.perform(
                post("/api/v1/meetings/$meetingId/surveys")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
                    .with(csrf())
            )
                .andExpect(request().asyncStarted())
                .andReturn()

            mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.error.code").value("C4048"))
        }
    }
}
