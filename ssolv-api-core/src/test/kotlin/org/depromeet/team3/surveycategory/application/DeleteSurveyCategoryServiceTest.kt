package org.depromeet.team3.surveycategory.application

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.depromeet.team3.annotation.UnitTest
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.fixture.SurveyCategoryFixture
import org.depromeet.team3.surveycategory.SurveyCategoryJpaRepository
import org.depromeet.team3.surveycategory.SurveyCategoryLevel
import org.depromeet.team3.surveycategory.exception.SurveyCategoryException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mock
import org.mockito.kotlin.*
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate

@UnitTest
@DisplayName("설문 카테고리 삭제 서비스 테스트")
class DeleteSurveyCategoryServiceTest {

    @Mock private lateinit var surveyCategoryJpaRepository: SurveyCategoryJpaRepository

    private lateinit var transactionTemplate: TransactionTemplate
    private lateinit var deleteSurveyCategoryService: DeleteSurveyCategoryService

    @BeforeEach
    fun setUp() {
        transactionTemplate = mock()
        whenever(transactionTemplate.execute(any<TransactionCallback<Any>>())).thenAnswer { invocation ->
            val callback = invocation.getArgument<TransactionCallback<Any>>(0)
            callback.doInTransaction(mock())
        }
        deleteSurveyCategoryService = DeleteSurveyCategoryService(surveyCategoryJpaRepository, transactionTemplate)
    }

    @Test
    @DisplayName("하위 카테고리가 없는 카테고리를 성공적으로 삭제한다")
    fun `하위 카테고리가 없는 카테고리를 성공적으로 삭제한다`() = runTest {
        // given
        val categoryId = 1L
        val entity = SurveyCategoryFixture.createEntity(id = categoryId, name = "김치찌개")
        whenever(surveyCategoryJpaRepository.findByIdAndIsDeletedFalse(categoryId)).thenReturn(entity)
        whenever(surveyCategoryJpaRepository.existsByParentIdAndIsDeletedFalse(categoryId)).thenReturn(false)
        doReturn(entity).whenever(surveyCategoryJpaRepository).save(any())

        // when
        deleteSurveyCategoryService(categoryId)

        // then
        verify(surveyCategoryJpaRepository).findByIdAndIsDeletedFalse(categoryId)
        verify(surveyCategoryJpaRepository).existsByParentIdAndIsDeletedFalse(categoryId)
        verify(surveyCategoryJpaRepository).save(argThat { this.isDeleted })
    }

    @Test
    @DisplayName("존재하지 않는 카테고리 삭제 시 예외가 발생한다")
    fun `존재하지 않는 카테고리 삭제 시 예외가 발생한다`() = runTest {
        // given
        whenever(surveyCategoryJpaRepository.findByIdAndIsDeletedFalse(999L)).thenReturn(null)

        // when & then
        val exception = assertThrows<SurveyCategoryException> { deleteSurveyCategoryService(999L) }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.CATEGORY_NOT_FOUND)
    }

    @Test
    @DisplayName("하위 카테고리가 있는 카테고리 삭제 시 예외가 발생한다")
    fun `하위 카테고리가 있는 카테고리 삭제 시 예외가 발생한다`() = runTest {
        // given
        val categoryId = 1L
        val entity = SurveyCategoryFixture.createEntity(id = categoryId, level = SurveyCategoryLevel.BRANCH)
        whenever(surveyCategoryJpaRepository.findByIdAndIsDeletedFalse(categoryId)).thenReturn(entity)
        whenever(surveyCategoryJpaRepository.existsByParentIdAndIsDeletedFalse(categoryId)).thenReturn(true)

        // when & then
        val exception = assertThrows<SurveyCategoryException> { deleteSurveyCategoryService(categoryId) }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.CATEGORY_HAS_CHILDREN)
    }
}
