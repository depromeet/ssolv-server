package org.depromeet.team3.surveycategory.application

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.common.util.CoroutineDispatchers
import org.depromeet.team3.surveycategory.SurveyCategoryRepository
import org.depromeet.team3.surveycategory.exception.SurveyCategoryException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@Service
class DeleteSurveyCategoryService(
    private val surveyCategoryRepository: SurveyCategoryRepository,
    private val transactionTemplate: TransactionTemplate,
    private val coroutineDispatchers: CoroutineDispatchers
) {

    suspend operator fun invoke(id: Long): Unit = withContext(coroutineDispatchers.VT) {
        transactionTemplate.execute {
            // 1. 삭제할 카테고리 조회
            val categoryToDelete = runBlocking { surveyCategoryRepository.findById(id) }
                ?: throw SurveyCategoryException(ErrorCode.CATEGORY_NOT_FOUND, mapOf("id" to id))

            // 2. 하위 카테고리 존재 여부 확인
            val hasChildren = runBlocking { surveyCategoryRepository.existsByParentIdAndIsDeletedFalse(id) }
            
            if (hasChildren) {
                throw SurveyCategoryException(
                    ErrorCode.CATEGORY_HAS_CHILDREN,
                    mapOf(
                        "categoryName" to categoryToDelete.name,
                        "categoryId" to id
                    )
                )
            }

            // 3. Soft Delete 처리
            val deletedCategory = categoryToDelete.copy(isDeleted = true)
            runBlocking { surveyCategoryRepository.save(deletedCategory) }
        }
    }
}
