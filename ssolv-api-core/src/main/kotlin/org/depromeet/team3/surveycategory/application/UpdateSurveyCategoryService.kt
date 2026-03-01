package org.depromeet.team3.surveycategory.application

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.common.util.CoroutineDispatchers
import org.depromeet.team3.surveycategory.SurveyCategory
import org.depromeet.team3.surveycategory.SurveyCategoryLevel
import org.depromeet.team3.surveycategory.SurveyCategoryRepository
import org.depromeet.team3.surveycategory.dto.request.UpdateSurveyCategoryRequest
import org.depromeet.team3.surveycategory.exception.SurveyCategoryException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@Service
class UpdateSurveyCategoryService(
    private val surveyCategoryRepository: SurveyCategoryRepository,
    private val transactionTemplate: TransactionTemplate,
    private val coroutineDispatchers: CoroutineDispatchers
) {

    suspend operator fun invoke(id: Long, request: UpdateSurveyCategoryRequest): Unit = withContext(coroutineDispatchers.VT) {
        transactionTemplate.execute {
            // 1. 기존 카테고리 조회 및 삭제 상태 검증
            val existingCategory = runBlocking { surveyCategoryRepository.findByIdAndIsDeletedFalse(id) }
                ?: throw SurveyCategoryException(ErrorCode.CATEGORY_NOT_FOUND, mapOf("id" to id))

            // 2. LEAF/BRANCH 규칙 검증
            runBlocking { validateLeafBranchRules(existingCategory, request) }

            // 3. 현재 카테고리가 BRANCH에서 LEAF로 변경되는 경우, 자식이 있는지 확인
            if (existingCategory.level == SurveyCategoryLevel.BRANCH &&
                request.level == SurveyCategoryLevel.LEAF) {
                val childCount = runBlocking { surveyCategoryRepository.countChildrenByParentIdAndIsDeletedFalse(id) }
                if (childCount > 0) {
                    throw SurveyCategoryException(ErrorCode.INVALID_CATEGORY_LEVEL_CHANGE, mapOf("childCount" to childCount))
                }
            }

            // 5. 형제 카테고리 내 이름 중복 검증
            if (runBlocking { surveyCategoryRepository.existsByNameAndParentIdAndIsDeletedFalse(request.name, request.parentId, id) }) {
                throw SurveyCategoryException(ErrorCode.DUPLICATE_CATEGORY_NAME, mapOf("name" to request.name, "parentId" to request.parentId))
            }

            // 6. 형제 카테고리 내 순서 중복 검증
            if (runBlocking { surveyCategoryRepository.existsBySortOrderAndParentIdAndIsDeletedFalseAndIdNot(request.sortOrder, request.parentId, id) }) {
                throw SurveyCategoryException(ErrorCode.DUPLICATE_CATEGORY_ORDER, mapOf("sortOrder" to request.sortOrder, "parentId" to request.parentId))
            }

            // 7. 업데이트된 카테고리 생성
            val updatedCategory = existingCategory.copy(
                parentId = request.parentId,
                level = request.level,
                name = request.name,
                sortOrder = request.sortOrder
            )

            // 8. 저장
            runBlocking { surveyCategoryRepository.save(updatedCategory) }
        }
    }

    private suspend fun validateLeafBranchRules(existingCategory: SurveyCategory, request: UpdateSurveyCategoryRequest) {
        // BRANCH 카테고리는 자식을 가질 수 있어야 함
        if (request.level == SurveyCategoryLevel.BRANCH) {
            // BRANCH 카테고리는 자식을 가질 수 있으므로 추가 검증 불필요
        }
        
        // LEAF 카테고리는 자식을 가질 수 없음
        if (request.level == SurveyCategoryLevel.LEAF) {
            // LEAF로 설정하는 경우, 현재 자식이 있는지 확인
            val childCount = surveyCategoryRepository.countChildrenByParentIdAndIsDeletedFalse(existingCategory.id!!)
            if (childCount > 0) {
                throw SurveyCategoryException(ErrorCode.INVALID_CATEGORY_LEVEL_CHANGE, mapOf("childCount" to childCount))
            }
        }
    }
}
