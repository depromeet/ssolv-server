package org.depromeet.team3.surveycategory.application

import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.surveycategory.SurveyCategoryRepository
import org.depromeet.team3.surveycategory.exception.SurveyCategoryException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DeleteSurveyCategoryService(
    private val surveyCategoryRepository: SurveyCategoryRepository
) {

    @Transactional
    suspend operator fun invoke(id: Long): Unit {
        // 1. 삭제할 카테고리 조회
        val categoryToDelete = surveyCategoryRepository.findById(id)
            ?: throw SurveyCategoryException(ErrorCode.CATEGORY_NOT_FOUND, mapOf("id" to id))

        // 2. 하위 카테고리 존재 여부 확인
        val hasChildren = surveyCategoryRepository.existsByParentIdAndIsDeletedFalse(id)
        
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
        surveyCategoryRepository.save(deletedCategory)
    }
}
