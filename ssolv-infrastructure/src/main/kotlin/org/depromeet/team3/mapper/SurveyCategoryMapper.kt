package org.depromeet.team3.mapper

import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.surveycategory.SurveyCategory
import org.depromeet.team3.surveycategory.SurveyCategoryEntity
import org.depromeet.team3.surveycategory.SurveyCategoryJpaRepository
import org.depromeet.team3.surveycategory.exception.SurveyCategoryException
import org.springframework.stereotype.Component

@Component
class SurveyCategoryMapper(private val surveyCategoryJpaRepository: SurveyCategoryJpaRepository) :
    DomainMapper<SurveyCategory, SurveyCategoryEntity> {

    override fun toDomain(entity: SurveyCategoryEntity): SurveyCategory = SurveyCategory(
        id = entity.id,
        parentId = entity.parent?.id,
        level = entity.level,
        name = entity.name,
        sortOrder = entity.sortOrder,
        isDeleted = entity.isDeleted,
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
    )

    override fun toEntity(domain: SurveyCategory): SurveyCategoryEntity {
        val parentEntity = domain.parentId?.let {
            surveyCategoryJpaRepository.findById(it)
                .orElseThrow {
                    SurveyCategoryException(
                        errorCode = ErrorCode.PARENT_CATEGORY_NOT_FOUND,
                        detail = mapOf("parentCategoryId" to domain.parentId),
                    )
                }
        }

        return SurveyCategoryEntity(
            id = domain.id,
            parent = parentEntity,
            level = domain.level,
            name = domain.name,
            sortOrder = domain.sortOrder,
            isDeleted = domain.isDeleted,
        )
    }
}
