package org.depromeet.team3.surveycategory.application

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.common.util.CoroutineDispatchers
import org.depromeet.team3.surveycategory.SurveyCategory
import org.depromeet.team3.surveycategory.SurveyCategoryRepository
import org.depromeet.team3.surveycategory.dto.request.CreateSurveyCategoryRequest
import org.depromeet.team3.surveycategory.dto.response.CreateSurveyCategoryResponse
import org.depromeet.team3.surveycategory.exception.SurveyCategoryException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@Service
class CreateSurveyCategoryService(
    private val surveyCategoryRepository: SurveyCategoryRepository,
    private val transactionTemplate: TransactionTemplate,
    private val coroutineDispatchers: CoroutineDispatchers
) {

    suspend operator fun invoke(request: CreateSurveyCategoryRequest): CreateSurveyCategoryResponse = withContext(coroutineDispatchers.VT) {
        transactionTemplate.execute {
            // sortOrder 중복 검증
            if (runBlocking { surveyCategoryRepository.existsBySortOrderAndParentIdAndIsDeletedFalseAndIdNot(
                    sortOrder = request.sortOrder,
                    parentId = request.parentId,
                    excludeId = null
                ) }
            ) {
                throw SurveyCategoryException(
                    errorCode = ErrorCode.DUPLICATE_CATEGORY_ORDER,
                    detail = mapOf(
                        "sortOrder" to request.sortOrder,
                        "parentId" to request.parentId
                    )
                )
            }

            val surveyCategory = SurveyCategory(
                parentId = request.parentId,
                level = request.level,
                name = request.name,
                sortOrder = request.sortOrder,
                isDeleted = false
            )

            val savedCategory = runBlocking { surveyCategoryRepository.save(surveyCategory) }
            
            val categoryId = requireNotNull(savedCategory.id) { 
                "Saved category id is null for category: ${savedCategory}" 
            }
            
            CreateSurveyCategoryResponse(
                id = categoryId,
                parentId = savedCategory.parentId,
                level = savedCategory.level,
                name = savedCategory.name,
                sortOrder = savedCategory.sortOrder
            )
        } ?: throw IllegalStateException("Transaction result is null")
    }
}