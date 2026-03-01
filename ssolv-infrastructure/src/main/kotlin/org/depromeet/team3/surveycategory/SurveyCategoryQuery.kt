package org.depromeet.team3.surveycategory

import kotlinx.coroutines.withContext
import org.depromeet.team3.common.util.CoroutineDispatchers
import com.querydsl.jpa.impl.JPAQueryFactory
import org.depromeet.team3.mapper.SurveyCategoryMapper
import org.springframework.stereotype.Repository
import org.depromeet.team3.surveycategory.QSurveyCategoryEntity

@Repository
class SurveyCategoryQuery (
    private val surveyCategoryMapper: SurveyCategoryMapper,
    private val surveyCategoryJpaRepository: SurveyCategoryJpaRepository,
    private val queryFactory: JPAQueryFactory,
) : SurveyCategoryRepository {

    override suspend fun save(surveyCategory: SurveyCategory): SurveyCategory {
        val entity = surveyCategoryMapper.toEntity(surveyCategory)
        return surveyCategoryMapper.toDomain(surveyCategoryJpaRepository.save(entity))
    }

    override suspend fun findById(id: Long): SurveyCategory? {
        return surveyCategoryJpaRepository.findById(id)
            .map { surveyCategoryMapper.toDomain(it) }
            .orElse(null)
    }
    
    override suspend fun findAllById(ids: List<Long>): List<SurveyCategory> {
        return surveyCategoryJpaRepository.findAllById(ids)
            .map { surveyCategoryMapper.toDomain(it) }
    }

    override suspend fun findActive(): List<SurveyCategory> {
        return surveyCategoryJpaRepository.findByIsDeletedFalse()
            .map { surveyCategoryMapper.toDomain(it) }
    }

    override suspend fun existsByParentIdAndIsDeletedFalse(parentId: Long): Boolean {
        return surveyCategoryJpaRepository.existsByParentIdAndIsDeletedFalse(parentId)
    }
    
    override suspend fun findByIdAndIsDeletedFalse(id: Long): SurveyCategory? {
        return surveyCategoryJpaRepository.findByIdAndIsDeletedFalse(id)
            ?.let { surveyCategoryMapper.toDomain(it) }
    }
    
    override suspend fun existsByNameAndParentIdAndIsDeletedFalse(name: String, parentId: Long?, excludeId: Long?): Boolean {
        val qSurveyCategory = QSurveyCategoryEntity.surveyCategoryEntity
        
        val firstMatch = queryFactory.selectFrom(qSurveyCategory)
            .where(
                qSurveyCategory.name.eq(name)
                    .and(qSurveyCategory.isDeleted.eq(false))
                    .and(
                        if (parentId == null) {
                            qSurveyCategory.parent.isNull
                        } else {
                            qSurveyCategory.parent.id.eq(parentId)
                        }
                    )
                    .and(
                        if (excludeId == null) {
                            qSurveyCategory.id.isNotNull
                        } else {
                            qSurveyCategory.id.ne(excludeId)
                        }
                    )
            )
            .fetchFirst()
        
        return firstMatch != null
    }
    
    override suspend fun existsBySortOrderAndParentIdAndIsDeletedFalseAndIdNot(sortOrder: Int, parentId: Long?, excludeId: Long?): Boolean {
        val qSurveyCategory = QSurveyCategoryEntity.surveyCategoryEntity
        
        val firstMatch = queryFactory.selectFrom(qSurveyCategory)
            .where(
                qSurveyCategory.sortOrder.eq(sortOrder)
                    .and(qSurveyCategory.isDeleted.eq(false))
                    .and(
                        if (parentId == null) {
                            qSurveyCategory.parent.isNull
                        } else {
                            qSurveyCategory.parent.id.eq(parentId)
                        }
                    )
                    .and(
                        if (excludeId == null) {
                            qSurveyCategory.id.isNotNull
                        } else {
                            qSurveyCategory.id.ne(excludeId)
                        }
                    )
            )
            .fetchFirst()
        
        return firstMatch != null
    }
    
    override suspend fun countChildrenByParentIdAndIsDeletedFalse(parentId: Long): Long {
        return surveyCategoryJpaRepository.countByParentIdAndIsDeletedFalse(parentId)
    }

    override suspend fun findByName(name: String): SurveyCategory? {
        return surveyCategoryJpaRepository.findByNameAndIsDeletedFalse(name)
            ?.let { surveyCategoryMapper.toDomain(it) }
    }
}