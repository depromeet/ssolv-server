package org.depromeet.team3.surveycategory.application

import org.depromeet.team3.surveycategory.SurveyCategory
import org.depromeet.team3.surveycategory.SurveyCategoryQuery
import org.depromeet.team3.surveycategory.dto.response.SurveyCategoryItem
import org.springframework.stereotype.Service

@Service
class GetSurveyCategoryService(
    private val surveyCategoryQuery: SurveyCategoryQuery
) {

    suspend operator fun invoke(): List<SurveyCategoryItem> {
        // DB에서 모든 활성 카테고리 조회
        val allCategories = surveyCategoryQuery.findActive()
        
        // 계층 구조 생성
        return buildHierarchyFromDatabase(allCategories)
    }

    private fun buildHierarchyFromDatabase(categories: List<SurveyCategory>): List<SurveyCategoryItem> {
        val categoryMap = categories.associateBy { it.id }
        val rootCategories = categories.filter { it.parentId == null }
            .sortedBy { it.sortOrder }
        
        return rootCategories.map { category ->
            buildCategoryItem(category, categoryMap)
        }
    }

    private fun buildCategoryItem(category: SurveyCategory, categoryMap: Map<Long?, SurveyCategory>): SurveyCategoryItem {
        val children = categoryMap.values
            .filter { it.parentId == category.id }
            .sortedBy { it.sortOrder }
            .map { buildCategoryItem(it, categoryMap) }
        
        return SurveyCategoryItem(
            id = category.id!!,
            level = category.level,
            name = category.name,
            sortOrder = category.sortOrder,
            children = children
        )
    }
}