package org.depromeet.team3.surveycategory.application

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.surveycategory.SurveyCategoryEntity
import org.depromeet.team3.surveycategory.SurveyCategoryJpaRepository
import org.depromeet.team3.surveycategory.SurveyCategoryLevel
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
@DisplayName("설문 카테고리 삭제 서비스 테스트")
class DeleteSurveyCategoryServiceTest {

    @Mock
    private lateinit var surveyCategoryJpaRepository: SurveyCategoryJpaRepository

    private lateinit var transactionTemplate: TransactionTemplate
    private lateinit var deleteSurveyCategoryService: DeleteSurveyCategoryService

    @BeforeEach
    fun setUp() {
        transactionTemplate = mock()
        whenever(transactionTemplate.execute<Any>(any())).thenAnswer { invocation ->
            val callback = invocation.getArgument<org.springframework.transaction.support.TransactionCallback<Any>>(0)
            callback.doInTransaction(mock())
        }
        deleteSurveyCategoryService = DeleteSurveyCategoryService(
            surveyCategoryJpaRepository,
            transactionTemplate
        )
    }

    private fun createEntity(
        id: Long = 1L,
        level: SurveyCategoryLevel = SurveyCategoryLevel.LEAF,
        name: String = "한식",
        sortOrder: Int = 1,
        isDeleted: Boolean = false
    ) = SurveyCategoryEntity(
        id = id,
        level = level,
        name = name,
        sortOrder = sortOrder,
        isDeleted = isDeleted
    )

    @Test
    @DisplayName("하위 카테고리가 없는 카테고리를 성공적으로 삭제한다")
    fun `하위 카테고리가 없는 카테고리를 성공적으로 삭제한다`() = runBlocking {
        val categoryId = 1L
        val entity = createEntity(id = categoryId, name = "김치찌개")

        whenever(surveyCategoryJpaRepository.findByIdAndIsDeletedFalse(categoryId)).thenReturn(entity)
        whenever(surveyCategoryJpaRepository.existsByParentIdAndIsDeletedFalse(categoryId)).thenReturn(false)
        whenever(surveyCategoryJpaRepository.save(any())).thenReturn(entity)

        deleteSurveyCategoryService(categoryId)

        verify(surveyCategoryJpaRepository).findByIdAndIsDeletedFalse(categoryId)
        verify(surveyCategoryJpaRepository).existsByParentIdAndIsDeletedFalse(categoryId)
        verify(surveyCategoryJpaRepository).save(argThat { this.isDeleted })
    }

    @Test
    @DisplayName("존재하지 않는 카테고리 삭제 시 예외가 발생한다")
    fun `존재하지 않는 카테고리 삭제 시 예외가 발생한다`() = runBlocking {
        val categoryId = 999L
        whenever(surveyCategoryJpaRepository.findByIdAndIsDeletedFalse(categoryId)).thenReturn(null)

        val exception = assertThrows<SurveyCategoryException> {
            deleteSurveyCategoryService(categoryId)
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.CATEGORY_NOT_FOUND)
    }

    @Test
    @DisplayName("하위 카테고리가 있는 카테고리 삭제 시 예외가 발생한다")
    fun `하위 카테고리가 있는 카테고리 삭제 시 예외가 발생한다`() = runBlocking {
        val categoryId = 1L
        val entity = createEntity(id = categoryId, level = SurveyCategoryLevel.BRANCH, name = "한식")

        whenever(surveyCategoryJpaRepository.findByIdAndIsDeletedFalse(categoryId)).thenReturn(entity)
        whenever(surveyCategoryJpaRepository.existsByParentIdAndIsDeletedFalse(categoryId)).thenReturn(true)

        val exception = assertThrows<SurveyCategoryException> {
            deleteSurveyCategoryService(categoryId)
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.CATEGORY_HAS_CHILDREN)
    }
}
