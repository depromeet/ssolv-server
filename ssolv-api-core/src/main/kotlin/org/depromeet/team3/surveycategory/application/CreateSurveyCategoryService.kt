package org.depromeet.team3.surveycategory.application

import kotlinx.coroutines.withContext
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.common.util.CoroutineDispatchers
import org.depromeet.team3.surveycategory.SurveyCategoryEntity
import org.depromeet.team3.surveycategory.SurveyCategoryJpaRepository
import org.depromeet.team3.surveycategory.dto.request.CreateSurveyCategoryRequest
import org.depromeet.team3.surveycategory.dto.response.CreateSurveyCategoryResponse
import org.depromeet.team3.surveycategory.exception.SurveyCategoryException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@Service
class CreateSurveyCategoryService(
    private val surveyCategoryJpaRepository: SurveyCategoryJpaRepository,
    private val transactionTemplate: TransactionTemplate,
    private val coroutineDispatchers: CoroutineDispatchers
) {

    suspend operator fun invoke(request: CreateSurveyCategoryRequest): CreateSurveyCategoryResponse =
        withContext(coroutineDispatchers.VT) {
            transactionTemplate.execute {
                // sortOrder 중복 검증 (JPA 메서드 직접 호출, blocking)
                val sortOrderExists = if (request.parentId == null) {
                    surveyCategoryJpaRepository.existsBySortOrderAndParentIsNullAndIsDeletedFalse(request.sortOrder)
                } else {
                    surveyCategoryJpaRepository.existsBySortOrderAndParentIdIsAndIsDeletedFalse(
                        request.sortOrder, request.parentId
                    )
                }

                if (sortOrderExists) {
                    throw SurveyCategoryException(
                        errorCode = ErrorCode.DUPLICATE_CATEGORY_ORDER,
                        detail = mapOf("sortOrder" to request.sortOrder, "parentId" to request.parentId)
                    )
                }

                val parentEntity: SurveyCategoryEntity? = request.parentId?.let {
                    surveyCategoryJpaRepository.findById(it).orElseThrow {
                        SurveyCategoryException(
                            errorCode = ErrorCode.PARENT_CATEGORY_NOT_FOUND,
                            detail = mapOf("parentCategoryId" to it)
                        )
                    }
                }

                val entity = SurveyCategoryEntity(
                    parent = parentEntity,
                    level = request.level,
                    name = request.name,
                    sortOrder = request.sortOrder,
                    isDeleted = false
                )

                val saved = surveyCategoryJpaRepository.save(entity)

                CreateSurveyCategoryResponse(
                    id = saved.id!!,
                    parentId = saved.parent?.id,
                    level = saved.level,
                    name = saved.name,
                    sortOrder = saved.sortOrder
                )
            }!!
        }
}