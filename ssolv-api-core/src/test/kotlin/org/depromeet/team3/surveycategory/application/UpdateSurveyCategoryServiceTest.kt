package org.depromeet.team3.surveycategory.application

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.surveycategory.SurveyCategoryEntity
import org.depromeet.team3.surveycategory.SurveyCategoryJpaRepository
import org.depromeet.team3.surveycategory.SurveyCategoryLevel
import org.depromeet.team3.surveycategory.dto.request.UpdateSurveyCategoryRequest
import org.depromeet.team3.surveycategory.exception.SurveyCategoryException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.kotlin.*
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.transaction.support.TransactionTemplate

@ExtendWith(MockitoExtension::class)
@DisplayName("설문 카테고리 수정 서비스 테스트")
class UpdateSurveyCategoryServiceTest {

    @Mock
    private lateinit var surveyCategoryJpaRepository: SurveyCategoryJpaRepository

    private lateinit var transactionTemplate: TransactionTemplate
    private lateinit var updateSurveyCategoryService: UpdateSurveyCategoryService

    @BeforeEach
    fun setUp() {
        whenever(Dispatchers.IO).thenReturn(Dispatchers.Unconfined)
        transactionTemplate = mock()
        whenever(transactionTemplate.execute<Any>(any())).thenAnswer { invocation ->
            val callback = invocation.getArgument<org.springframework.transaction.support.TransactionCallback<Any>>(0)
            callback.doInTransaction(mock())
        }
        updateSurveyCategoryService = UpdateSurveyCategoryService(
            surveyCategoryJpaRepository,
            transactionTemplate,
            coroutineDispatchers
        )
    }

    private fun createEntity(
        id: Long = 1L,
        level: SurveyCategoryLevel = SurveyCategoryLevel.BRANCH,
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

    @Test
    @DisplayName("존재하는 카테고리를 성공적으로 수정한다")
    fun `존재하는 카테고리를 성공적으로 수정한다`() = runBlocking {
        val categoryId = 1L
        val entity = createEntity(id = categoryId)

        val updateRequest = UpdateSurveyCategoryRequest(
            parentId = null,
            level = SurveyCategoryLevel.BRANCH,
            name = "전통한식",
            sortOrder = 2
        )

        whenever(surveyCategoryJpaRepository.findByIdAndIsDeletedFalse(categoryId)).thenReturn(entity)
        whenever(surveyCategoryJpaRepository.existsByNameAndParentIsNullAndIsDeletedFalseAndIdNot("전통한식", categoryId)).thenReturn(false)
        whenever(surveyCategoryJpaRepository.existsBySortOrderAndParentIsNullAndIsDeletedFalseAndIdNot(2, categoryId)).thenReturn(false)
        whenever(surveyCategoryJpaRepository.save(any())).thenReturn(entity)

        updateSurveyCategoryService(categoryId, updateRequest)

        verify(surveyCategoryJpaRepository).findByIdAndIsDeletedFalse(categoryId)
        verify(surveyCategoryJpaRepository).save(argThat { name == "전통한식" && sortOrder == 2 })
    }

    @Test
    @DisplayName("존재하지 않는 카테고리 수정 시 예외가 발생한다")
    fun `존재하지 않는 카테고리 수정 시 예외가 발생한다`() = runBlocking {
        val categoryId = 999L
        val updateRequest = UpdateSurveyCategoryRequest(
            parentId = null,
            level = SurveyCategoryLevel.BRANCH,
            name = "전통한식",
            sortOrder = 2
        )

        whenever(surveyCategoryJpaRepository.findByIdAndIsDeletedFalse(categoryId)).thenReturn(null)

        val exception = assertThrows<SurveyCategoryException> {
            updateSurveyCategoryService(categoryId, updateRequest)
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.CATEGORY_NOT_FOUND)
    }

    @Test
    @DisplayName("LEAF 변경 시 자식이 있으면 예외가 발생한다")
    fun `LEAF 변경 시 자식이 있으면 예외가 발생한다`() = runBlocking {
        val categoryId = 1L
        val entity = createEntity(id = categoryId, level = SurveyCategoryLevel.BRANCH)

        val updateRequest = UpdateSurveyCategoryRequest(
            parentId = null,
            level = SurveyCategoryLevel.LEAF,
            name = "한식",
            sortOrder = 1
        )

        whenever(surveyCategoryJpaRepository.findByIdAndIsDeletedFalse(categoryId)).thenReturn(entity)
        whenever(surveyCategoryJpaRepository.countByParentIdAndIsDeletedFalse(categoryId)).thenReturn(2L)

        val exception = assertThrows<SurveyCategoryException> {
            updateSurveyCategoryService(categoryId, updateRequest)
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.INVALID_CATEGORY_LEVEL_CHANGE)
    }
}
