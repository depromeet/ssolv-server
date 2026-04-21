package org.depromeet.team3.surveycategory

import com.linecorp.kotlinjdsl.dsl.jpql.*
import org.depromeet.team3.mapper.SurveyCategoryMapper
import org.springframework.stereotype.Repository

@Repository
class SurveyCategoryQuery(
    private val surveyCategoryMapper: SurveyCategoryMapper,
    private val surveyCategoryJpaRepository: SurveyCategoryJpaRepository,
) : SurveyCategoryRepository {

    override suspend fun save(surveyCategory: SurveyCategory): SurveyCategory {
        val entity = surveyCategoryMapper.toEntity(surveyCategory)
        return surveyCategoryMapper.toDomain(surveyCategoryJpaRepository.save(entity))
    }

    override suspend fun findById(id: Long): SurveyCategory? = surveyCategoryJpaRepository.findById(id)
        .map { surveyCategoryMapper.toDomain(it) }
        .orElse(null)

    override suspend fun findAllById(ids: List<Long>): List<SurveyCategory> = surveyCategoryJpaRepository.findAllById(ids)
        .map { surveyCategoryMapper.toDomain(it) }

    override suspend fun findActive(): List<SurveyCategory> = surveyCategoryJpaRepository.findByIsDeletedFalse()
        .map { surveyCategoryMapper.toDomain(it) }

    override suspend fun existsByParentIdAndIsDeletedFalse(parentId: Long): Boolean =
        surveyCategoryJpaRepository.existsByParentIdAndIsDeletedFalse(parentId)

    override suspend fun findByIdAndIsDeletedFalse(id: Long): SurveyCategory? = surveyCategoryJpaRepository.findByIdAndIsDeletedFalse(id)
        ?.let { surveyCategoryMapper.toDomain(it) }

    override suspend fun existsByNameAndParentIdAndIsDeletedFalse(name: String, parentId: Long?, excludeId: Long?): Boolean {
        val results = surveyCategoryJpaRepository.findAll {
            select(
                entity(SurveyCategoryEntity::class),
            ).from(
                entity(SurveyCategoryEntity::class),
            ).where(
                and(
                    path(SurveyCategoryEntity::name).eq(name),
                    path(SurveyCategoryEntity::isDeleted).eq(false),
                    if (parentId == null) {
                        path(SurveyCategoryEntity::parent).isNull()
                    } else {
                        path(SurveyCategoryEntity::parent).path(SurveyCategoryEntity::id).eq(parentId)
                    },
                    excludeId?.let { path(SurveyCategoryEntity::id).ne(it) },
                ),
            )
        }

        return results.filterNotNull().isNotEmpty()
    }

    override suspend fun existsBySortOrderAndParentIdAndIsDeletedFalseAndIdNot(sortOrder: Int, parentId: Long?, excludeId: Long?): Boolean {
        val results = surveyCategoryJpaRepository.findAll {
            select(
                entity(SurveyCategoryEntity::class),
            ).from(
                entity(SurveyCategoryEntity::class),
            ).where(
                and(
                    path(SurveyCategoryEntity::sortOrder).eq(sortOrder),
                    path(SurveyCategoryEntity::isDeleted).eq(false),
                    if (parentId == null) {
                        path(SurveyCategoryEntity::parent).isNull()
                    } else {
                        path(SurveyCategoryEntity::parent).path(SurveyCategoryEntity::id).eq(parentId)
                    },
                    excludeId?.let { path(SurveyCategoryEntity::id).ne(it) },
                ),
            )
        }

        return results.filterNotNull().isNotEmpty()
    }

    override suspend fun countChildrenByParentIdAndIsDeletedFalse(parentId: Long): Long =
        surveyCategoryJpaRepository.countByParentIdAndIsDeletedFalse(parentId)

    override suspend fun findByName(name: String): SurveyCategory? = surveyCategoryJpaRepository.findByNameAndIsDeletedFalse(name)
        ?.let { surveyCategoryMapper.toDomain(it) }
}
