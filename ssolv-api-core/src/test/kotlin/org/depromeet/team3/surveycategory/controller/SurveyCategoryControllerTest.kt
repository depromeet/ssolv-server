package org.depromeet.team3.surveycategory.controller

import com.fasterxml.jackson.databind.ObjectMapper
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.config.SecurityTestConfig
import org.depromeet.team3.surveycategory.SurveyCategoryLevel
import org.depromeet.team3.surveycategory.application.CreateSurveyCategoryService
import org.depromeet.team3.surveycategory.application.DeleteSurveyCategoryService
import org.depromeet.team3.surveycategory.application.GetSurveyCategoryService
import org.depromeet.team3.surveycategory.application.UpdateSurveyCategoryService
import org.depromeet.team3.surveycategory.controller.SurveyCategoryController
import org.depromeet.team3.surveycategory.dto.request.CreateSurveyCategoryRequest
import org.depromeet.team3.surveycategory.dto.request.UpdateSurveyCategoryRequest
import org.depromeet.team3.surveycategory.dto.response.CreateSurveyCategoryResponse
import org.depromeet.team3.surveycategory.dto.response.SurveyCategoryItem
import org.depromeet.team3.surveycategory.exception.SurveyCategoryException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import kotlinx.coroutines.test.runTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

@WebMvcTest(SurveyCategoryController::class)
@org.springframework.test.context.ContextConfiguration(classes = [org.depromeet.team3.CoreApiApplication::class])
@Import(SecurityTestConfig::class)
@ActiveProfiles("test")
@WithMockUser
@DisplayName("[SURVEY CATEGORY] 설문 카테고리 컨트롤러 테스트")
class SurveyCategoryControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockitoBean
    private lateinit var createSurveyCategoryService: CreateSurveyCategoryService

    @MockitoBean
    private lateinit var getSurveyCategoryService: GetSurveyCategoryService

    @MockitoBean
    private lateinit var updateSurveyCategoryService: UpdateSurveyCategoryService

    @MockitoBean
    private lateinit var deleteSurveyCategoryService: DeleteSurveyCategoryService

    @Test
    @DisplayName("설문 카테고리 목록을 성공적으로 조회한다")
    fun `설문 카테고리 목록을 성공적으로 조회한다`() {
        runTest {
            // given
            val response = listOf(
                SurveyCategoryItem(
                    id = 1L,
                    level = SurveyCategoryLevel.BRANCH,
                    name = "한식",
                    sortOrder = 1,
                    children = emptyList()
                )
            )

            getSurveyCategoryService.stub {
                onBlocking { invoke() }.doReturn(response)
            }

            // when & then
            val mvcResult = mockMvc.perform(get("/api/v1/survey-categories"))
                .andExpect(request().asyncStarted())
                .andReturn()

            mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk)
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").isArray)
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].level").value("BRANCH"))
                .andExpect(jsonPath("$.data[0].name").value("한식"))
                .andExpect(jsonPath("$.data[0].sortOrder").value(1))
                .andExpect(jsonPath("$.data[0].children").isArray)
                .andExpect(jsonPath("$.error").doesNotExist())
        }
    }

    @Test
    @DisplayName("계층 구조가 있는 설문 카테고리 목록을 성공적으로 조회한다")
    fun `계층 구조가 있는 설문 카테고리 목록을 성공적으로 조회한다`() {
        runTest {
            // given
            val response = listOf(
                SurveyCategoryItem(
                    id = 1L,
                    level = SurveyCategoryLevel.BRANCH,
                    name = "한식",
                    sortOrder = 1,
                    children = listOf(
                        SurveyCategoryItem(
                            id = 2L,
                            level = SurveyCategoryLevel.LEAF,
                            name = "김치찌개",
                            sortOrder = 1,
                            children = emptyList()
                        ),
                        SurveyCategoryItem(
                            id = 3L,
                            level = SurveyCategoryLevel.LEAF,
                            name = "불고기",
                            sortOrder = 2,
                            children = emptyList()
                        )
                    )
                ),
                SurveyCategoryItem(
                    id = 4L,
                    level = SurveyCategoryLevel.BRANCH,
                    name = "중식",
                    sortOrder = 2,
                    children = listOf(
                        SurveyCategoryItem(
                            id = 5L,
                            level = SurveyCategoryLevel.LEAF,
                            name = "짜장면",
                            sortOrder = 1,
                            children = emptyList()
                        )
                    )
                )
            )

            getSurveyCategoryService.stub {
                onBlocking { invoke() }.doReturn(response)
            }

            // when & then
            val mvcResult = mockMvc.perform(get("/api/v1/survey-categories"))
                .andExpect(request().asyncStarted())
                .andReturn()

            mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk)
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").isArray)
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].name").value("한식"))
                .andExpect(jsonPath("$.data[0].children").isArray)
                .andExpect(jsonPath("$.data[0].children[0].id").value(2))
                .andExpect(jsonPath("$.data[0].children[0].name").value("김치찌개"))
                .andExpect(jsonPath("$.data[0].children[1].id").value(3))
                .andExpect(jsonPath("$.data[0].children[1].name").value("불고기"))
                .andExpect(jsonPath("$.data[1].id").value(4))
                .andExpect(jsonPath("$.data[1].name").value("중식"))
                .andExpect(jsonPath("$.data[1].children[0].id").value(5))
                .andExpect(jsonPath("$.data[1].children[0].name").value("짜장면"))
                .andExpect(jsonPath("$.error").doesNotExist())
        }
    }

    @Test
    @DisplayName("설문 카테고리를 성공적으로 생성한다")
    fun `설문 카테고리를 성공적으로 생성한다`() {
        runTest {
            // given
            val request = CreateSurveyCategoryRequest(
                parentId = null,
                level = SurveyCategoryLevel.BRANCH,
                name = "한식",
                sortOrder = 1
            )
            
            val response = CreateSurveyCategoryResponse(
                id = 1L,
                parentId = null,
                level = SurveyCategoryLevel.BRANCH,
                name = "한식",
                sortOrder = 1
            )

            // Mock 설정 - 생성된 카테고리 응답 반환
            createSurveyCategoryService.stub {
                onBlocking { invoke(any()) }.doReturn(response)
            }

            // when & then
            val mvcResult = mockMvc.perform(
                post("/api/v1/survey-categories")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
                    .with(csrf())
            )
                .andExpect(request().asyncStarted())
                .andReturn()

            mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data").exists())
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.parentId").isEmpty)
                .andExpect(jsonPath("$.data.level").value("BRANCH"))
                .andExpect(jsonPath("$.data.name").value("한식"))
                .andExpect(jsonPath("$.data.sortOrder").value(1))
                .andExpect(jsonPath("$.error").doesNotExist())
        }
    }

    @Test
    @DisplayName("설문 카테고리를 성공적으로 수정한다")
    fun `설문 카테고리를 성공적으로 수정한다`() {
        runTest {
            // given
            val categoryId = 1L
            val request = UpdateSurveyCategoryRequest(
                parentId = null,
                level = SurveyCategoryLevel.BRANCH,
                name = "전통한식",
                sortOrder = 2
            )

            // Mock 설정 - 실제 서비스 호출을 방지
            updateSurveyCategoryService.stub {
                onBlocking { invoke(any(), any()) }.doAnswer { }
            }

            // when & then
            val mvcResult = mockMvc.perform(
                put("/api/v1/survey-categories/$categoryId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
                    .with(csrf())
            )
                .andExpect(request().asyncStarted())
                .andReturn()

            mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data").exists())
                .andExpect(jsonPath("$.error").doesNotExist())
        }
    }

    @Test
    @DisplayName("설문 카테고리를 성공적으로 삭제한다")
    fun `설문 카테고리를 성공적으로 삭제한다`() {
        runTest {
            // given
            val categoryId = 1L

            // Mock 설정 - 실제 서비스 호출을 방지
            deleteSurveyCategoryService.stub {
                onBlocking { invoke(any()) }.doAnswer { }
            }

            // when & then
            val mvcResult = mockMvc.perform(delete("/api/v1/survey-categories/$categoryId")
                .with(csrf()))
                .andExpect(request().asyncStarted())
                .andReturn()

            mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data").exists())
                .andExpect(jsonPath("$.error").doesNotExist())
        }
    }

    @Test
    @DisplayName("존재하지 않는 카테고리 수정 시 404 에러가 발생한다")
    fun `존재하지 않는 카테고리 수정 시 404 에러가 발생한다`() {
        runTest {
            // given
            val categoryId = 999L
            val request = UpdateSurveyCategoryRequest(
                parentId = null,
                level = SurveyCategoryLevel.BRANCH,
                name = "전통한식",
                sortOrder = 2
            )

            // Mock 설정 - 예외 발생 시뮬레이션
            updateSurveyCategoryService.stub {
                onBlocking { invoke(any(), any()) }.doThrow(SurveyCategoryException(ErrorCode.CATEGORY_NOT_FOUND, mapOf("id" to categoryId)))
            }

            // when & then
            val mvcResult = mockMvc.perform(
                put("/api/v1/survey-categories/$categoryId")
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
                .andExpect(jsonPath("$.error.code").value("C4041"))
        }
    }

    @Test
    @DisplayName("하위 카테고리가 있는 카테고리 삭제 시 409 에러가 발생한다")
    fun `하위 카테고리가 있는 카테고리 삭제 시 409 에러가 발생한다`() {
        runTest {
            // given
            val categoryId = 1L

            deleteSurveyCategoryService.stub {
                onBlocking { invoke(any()) }.doThrow(SurveyCategoryException(ErrorCode.CATEGORY_HAS_CHILDREN, mapOf("categoryId" to categoryId)))
            }

            // when & then
            val mvcResult = mockMvc.perform(delete("/api/v1/survey-categories/$categoryId")
                .with(csrf()))
                .andExpect(request().asyncStarted())
                .andReturn()

            mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.error.code").value("C4092"))
        }
    }

    @Test
    @DisplayName("중복된 sortOrder로 카테고리 생성 시 409 에러가 발생한다")
    fun `중복된 sortOrder로 카테고리 생성 시 409 에러가 발생한다`() {
        runTest {
            // given
            val request = CreateSurveyCategoryRequest(
                parentId = null,
                level = SurveyCategoryLevel.BRANCH,
                name = "한식",
                sortOrder = 1
            )

            // Mock 설정 - 중복된 sortOrder 예외 발생 시뮬레이션
            createSurveyCategoryService.stub {
                onBlocking { invoke(any()) }.doThrow(SurveyCategoryException(ErrorCode.DUPLICATE_CATEGORY_ORDER, mapOf("sortOrder" to 1, "parentId" to null)))
            }

            // when & then
            val mvcResult = mockMvc.perform(
                post("/api/v1/survey-categories")
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
                .andExpect(jsonPath("$.error.code").value("C4095"))
                .andExpect(jsonPath("$.error.detail.sortOrder").value(1))
                .andExpect(jsonPath("$.error.detail.parentId").isEmpty)
        }
    }

    @Test
    @DisplayName("중복된 sortOrder로 카테고리 수정 시 409 에러가 발생한다")
    fun `중복된 sortOrder로 카테고리 수정 시 409 에러가 발생한다`() {
        runTest {
            // given
            val categoryId = 1L
            val request = UpdateSurveyCategoryRequest(
                parentId = null,
                level = SurveyCategoryLevel.BRANCH,
                name = "전통한식",
                sortOrder = 2
            )

            // Mock 설정 - 중복된 sortOrder 예외 발생 시뮬레이션
            updateSurveyCategoryService.stub {
                onBlocking { invoke(any(), any()) }.doThrow(SurveyCategoryException(ErrorCode.DUPLICATE_CATEGORY_ORDER, mapOf("sortOrder" to 2, "parentId" to null)))
            }

            // when & then
            val mvcResult = mockMvc.perform(
                put("/api/v1/survey-categories/$categoryId")
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
                .andExpect(jsonPath("$.error.code").value("C4095"))
                .andExpect(jsonPath("$.error.detail.sortOrder").value(2))
                .andExpect(jsonPath("$.error.detail.parentId").isEmpty)
        }
    }

    @Test
    @DisplayName("잘못된 요청 데이터로 카테고리 생성 시 400 에러가 발생한다")
    fun `잘못된 요청 데이터로 카테고리 생성 시 400 에러가 발생한다`() {
        // given
        val invalidRequest = CreateSurveyCategoryRequest(
            parentId = null,
            level = SurveyCategoryLevel.BRANCH,
            name = "", // 빈 문자열 - 유효성 검사 실패
            sortOrder = 1
        )

        // when & then
        mockMvc.perform(
            post("/api/v1/survey-categories")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidRequest))
                .with(csrf())
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.data").doesNotExist())
            .andExpect(jsonPath("$.error").exists())
            .andExpect(jsonPath("$.error.code").value("C002"))
    }
}
