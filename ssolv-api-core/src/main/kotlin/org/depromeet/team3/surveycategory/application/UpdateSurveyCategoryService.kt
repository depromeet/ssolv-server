package org.depromeet.team3.surveycategory.application

import kotlinx.coroutines.withContext
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.common.util.CoroutineDispatchers
import org.depromeet.team3.surveycategory.SurveyCategoryJpaRepository
import org.depromeet.team3.surveycategory.SurveyCategoryLevel
import org.depromeet.team3.surveycategory.dto.request.UpdateSurveyCategoryRequest
import org.depromeet.team3.surveycategory.exception.SurveyCategoryException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@Service
class UpdateSurveyCategoryService(
    private val surveyCategoryJpaRepository: SurveyCategoryJpaRepository,
    private val transactionTemplate: TransactionTemplate,
    private val coroutineDispatchers: CoroutineDispatchers
) {

    suspend operator fun invoke(id: Long, request: UpdateSurveyCategoryRequest): Unit =
        withContext(coroutineDispatchers.VT) {
            transactionTemplate.execute {
                // 1. 기존 카테고리 조회 (삭제된 것 제외)
                val existingEntity = surveyCategoryJpaRepository.findByIdAndIsDeletedFalse(id)
                    ?: throw SurveyCategoryException(ErrorCode.CATEGORY_NOT_FOUND, mapOf("id" to id))

                // 2. LEAF 변경 시 자식 존재 여부 확인
                if (request.level == SurveyCategoryLevel.LEAF) {
                    val childCount = surveyCategoryJpaRepository.countByParentIdAndIsDeletedFalse(id)
                    if (childCount > 0) {
                        throw SurveyCategoryException(
                            ErrorCode.INVALID_CATEGORY_LEVEL_CHANGE,
                            mapOf("childCount" to childCount)
                        )
                    }
                }

                // 3. 형제 카테고리 내 이름 중복 검증 (JPA, blocking)
                val nameExists = if (request.parentId == null) {
                    surveyCategoryJpaRepository.existsByNameAndParentIsNullAndIsDeletedFalseAndIdNot(
                        request.name, id
                    )
                } else {
                    surveyCategoryJpaRepository.existsByNameAndParentIdAndIsDeletedFalseAndIdNot(
                        request.name, request.parentId, id
                    )
                }

                if (nameExists) {
                    throw SurveyCategoryException(
                        ErrorCode.DUPLICATE_CATEGORY_NAME,
                        mapOf("name" to request.name, "parentId" to request.parentId)
                    )
                }

                // 4. 형제 카테고리 내 sortOrder 중복 검증 (JPA, blocking)
                val sortOrderExists = if (request.parentId == null) {
                    surveyCategoryJpaRepository.existsBySortOrderAndParentIsNullAndIsDeletedFalseAndIdNot(
                        request.sortOrder, id
                    )
                } else {
                    surveyCategoryJpaRepository.existsBySortOrderAndParentIdAndIsDeletedFalseAndIdNot(
                        request.sortOrder, request.parentId, id
                    )
                }

                if (sortOrderExists) {
                    throw SurveyCategoryException(
                        ErrorCode.DUPLICATE_CATEGORY_ORDER,
                        mapOf("sortOrder" to request.sortOrder, "parentId" to request.parentId)
                    )
                }

                // 5. 부모 엔티티 조회
                val parentEntity = request.parentId?.let {
                    surveyCategoryJpaRepository.findById(it).orElseThrow {
                        SurveyCategoryException(
                            ErrorCode.PARENT_CATEGORY_NOT_FOUND,
                            mapOf("parentCategoryId" to it)
                        )
                    }
                }

                // 6. 필드 업데이트 후 저장
                existingEntity.name = request.name
                existingEntity.sortOrder = request.sortOrder
                existingEntity.level = request.level
                existingEntity.parent = parentEntity
                surveyCategoryJpaRepository.save(existingEntity)
            }
        }
}
