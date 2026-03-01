package org.depromeet.team3.surveycategory.application

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.common.util.CoroutineDispatchers
import org.depromeet.team3.surveycategory.SurveyCategoryLevel
import org.depromeet.team3.surveycategory.SurveyCategoryRepository
import org.depromeet.team3.surveycategory.dto.request.UpdateSurveyCategoryRequest
import org.depromeet.team3.surveycategory.exception.SurveyCategoryException
import org.depromeet.team3.survey.util.SurveyTestDataFactory
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.kotlin.*
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime

@ExtendWith(MockitoExtension::class)
@DisplayName("설문 카테고리 수정 서비스 테스트")
class UpdateSurveyCategoryServiceTest {

    @Mock
    private lateinit var surveyCategoryRepository: SurveyCategoryRepository

    private lateinit var transactionTemplate: TransactionTemplate
    private lateinit var coroutineDispatchers: CoroutineDispatchers
    private lateinit var updateSurveyCategoryService: UpdateSurveyCategoryService

    @BeforeEach
    fun setUp() {
        coroutineDispatchers = mock()
        whenever(coroutineDispatchers.VT).thenReturn(Dispatchers.Unconfined)
        transactionTemplate = mock()
        whenever(transactionTemplate.execute<Any>(any())).thenAnswer { invocation ->
            val callback = invocation.getArgument<org.springframework.transaction.support.TransactionCallback<Any>>(0)
            callback.doInTransaction(mock())
        }
        updateSurveyCategoryService = UpdateSurveyCategoryService(
            surveyCategoryRepository,
            transactionTemplate,
            coroutineDispatchers
        )
    }

    @Test
    @DisplayName("존재하는 카테고리를 성공적으로 수정한다")
    fun `존재하는 카테고리를 성공적으로 수정한다`() = runBlocking {
        // given
        val categoryId = 1L
        val existingCategory = SurveyTestDataFactory.createSurveyCategory(
            id = categoryId,
            level = SurveyCategoryLevel.BRANCH,
            name = "한식",
            sortOrder = 1
        )

        val updateRequest = UpdateSurveyCategoryRequest(
            parentId = null,
            level = SurveyCategoryLevel.BRANCH,
            name = "전통한식",
            sortOrder = 2
        )

        whenever(surveyCategoryRepository.findByIdAndIsDeletedFalse(categoryId)).thenReturn(existingCategory)

        // when
        updateSurveyCategoryService(categoryId, updateRequest)

        // then
        verify(surveyCategoryRepository).findByIdAndIsDeletedFalse(categoryId)
        verify(surveyCategoryRepository).save(
            argThat { id == categoryId && name == "전통한식" && sortOrder == 2 }
        )
    }

    @Test
    @DisplayName("존재하지 않는 카테고리 수정 시 예외가 발생한다")
    fun `존재하지 않는 카테고리 수정 시 예외가 발생한다`() = runBlocking {
        // given
        val categoryId = 999L
        val updateRequest = UpdateSurveyCategoryRequest(
            parentId = null,
            level = SurveyCategoryLevel.BRANCH,
            name = "전통한식",
            sortOrder = 2
        )

        whenever(surveyCategoryRepository.findByIdAndIsDeletedFalse(categoryId)).thenReturn(null)

        // when & then
        val exception = assertThrows<SurveyCategoryException> {
            updateSurveyCategoryService(categoryId, updateRequest)
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.CATEGORY_NOT_FOUND)
    }

    @Test
    @DisplayName("카테고리의 모든 필드를 수정할 수 있다")
    fun `카테고리의 모든 필드를 수정할 수 있다`() = runBlocking {
        // given
        val categoryId = 1L
        val existingCategory = SurveyTestDataFactory.createSurveyCategory(
            id = categoryId,
            level = SurveyCategoryLevel.BRANCH,
            name = "한식",
            sortOrder = 1
        )

        val updateRequest = UpdateSurveyCategoryRequest(
            parentId = 2L,
            level = SurveyCategoryLevel.LEAF,
            name = "피해야할 재료",
            sortOrder = 5
        )

        whenever(surveyCategoryRepository.findByIdAndIsDeletedFalse(categoryId)).thenReturn(existingCategory)
        whenever(surveyCategoryRepository.countChildrenByParentIdAndIsDeletedFalse(categoryId)).thenReturn(0L)
        whenever(surveyCategoryRepository.existsByNameAndParentIdAndIsDeletedFalse("피해야할 재료", 2L, categoryId)).thenReturn(false)
        whenever(surveyCategoryRepository.existsBySortOrderAndParentIdAndIsDeletedFalseAndIdNot(5, 2L, categoryId)).thenReturn(false)

        // when
        updateSurveyCategoryService(categoryId, updateRequest)

        // then
        verify(surveyCategoryRepository).save(
            argThat { id == categoryId && parentId == 2L && level == SurveyCategoryLevel.LEAF && name == "피해야할 재료" && sortOrder == 5 }
        )
    }
}
