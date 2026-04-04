package org.depromeet.team3.surveycategory.application
import org.depromeet.team3.common.util.withTracingContext
import kotlinx.coroutines.Dispatchers
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.surveycategory.SurveyCategoryJpaRepository
import org.depromeet.team3.surveycategory.exception.SurveyCategoryException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@Service
class DeleteSurveyCategoryService(
    private val surveyCategoryJpaRepository: SurveyCategoryJpaRepository,
    private val transactionTemplate: TransactionTemplate,
) {

    suspend operator fun invoke(id: Long): Unit = withTracingContext() {
        transactionTemplate.execute {
            // 1. 삭제할 카테고리 조회
            val entity = surveyCategoryJpaRepository.findByIdAndIsDeletedFalse(id)
                ?: throw SurveyCategoryException(
                    ErrorCode.CATEGORY_NOT_FOUND,
                    mapOf("id" to id)
                )

            // 2. 하위 카테고리 존재 여부 확인
            val hasChildren = surveyCategoryJpaRepository.existsByParentIdAndIsDeletedFalse(id)

            if (hasChildren) {
                throw SurveyCategoryException(
                    ErrorCode.CATEGORY_HAS_CHILDREN,
                    mapOf(
                        "categoryName" to entity.name,
                        "categoryId" to id
                    )
                )
            }

            // 3. Soft Delete 처리 (var 필드 직접 설정)
            entity.isDeleted = true
            surveyCategoryJpaRepository.save(entity)
        }
    }
}
