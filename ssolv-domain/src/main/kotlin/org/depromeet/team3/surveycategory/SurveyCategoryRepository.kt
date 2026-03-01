package org.depromeet.team3.surveycategory

interface SurveyCategoryRepository {

    suspend fun save(surveyCategory: SurveyCategory): SurveyCategory

    suspend fun findById(id: Long): SurveyCategory?
    
    suspend fun findAllById(ids: List<Long>): List<SurveyCategory>

    suspend fun findActive(): List<SurveyCategory>

    suspend fun existsByParentIdAndIsDeletedFalse(parentId: Long): Boolean
    
    suspend fun findByIdAndIsDeletedFalse(id: Long): SurveyCategory?
    
    suspend fun existsByNameAndParentIdAndIsDeletedFalse(name: String, parentId: Long?, excludeId: Long? = null): Boolean
    
    suspend fun existsBySortOrderAndParentIdAndIsDeletedFalseAndIdNot(sortOrder: Int, parentId: Long?, excludeId: Long? = null): Boolean
    
    suspend fun countChildrenByParentIdAndIsDeletedFalse(parentId: Long): Long
    
    suspend fun findByName(name: String): SurveyCategory?
}