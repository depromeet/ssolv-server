package org.depromeet.team3.surveycategory

import com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutor
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface SurveyCategoryJpaRepository :
    JpaRepository<SurveyCategoryEntity, Long>,
    KotlinJdslJpqlExecutor {

    fun findByIsDeletedFalse(): List<SurveyCategoryEntity>

    fun existsByParentIdAndIsDeletedFalse(parentId: Long): Boolean

    fun findByIdAndIsDeletedFalse(id: Long): SurveyCategoryEntity?

    fun countByParentIdAndIsDeletedFalse(parentId: Long): Long

    fun findByNameAndIsDeletedFalse(name: String): SurveyCategoryEntity?

    // 이름 중복 검증 (excludeId 포함, parentId null일 때)
    @Query(
        "SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END FROM SurveyCategoryEntity c WHERE c.name = :name AND c.isDeleted = false AND c.parent IS NULL AND c.id <> :excludeId",
    )
    fun existsByNameAndParentIsNullAndIsDeletedFalseAndIdNot(@Param("name") name: String, @Param("excludeId") excludeId: Long): Boolean

    // 이름 중복 검증 (excludeId 포함, parentId not null일 때)
    @Query(
        "SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END FROM SurveyCategoryEntity c WHERE c.name = :name AND c.isDeleted = false AND c.parent.id = :parentId AND c.id <> :excludeId",
    )
    fun existsByNameAndParentIdAndIsDeletedFalseAndIdNot(
        @Param("name") name: String,
        @Param("parentId") parentId: Long,
        @Param("excludeId") excludeId: Long,
    ): Boolean

    // sortOrder 중복 검증 (excludeId 포함, parentId null일 때)
    @Query(
        "SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END FROM SurveyCategoryEntity c WHERE c.sortOrder = :sortOrder AND c.isDeleted = false AND c.parent IS NULL AND c.id <> :excludeId",
    )
    fun existsBySortOrderAndParentIsNullAndIsDeletedFalseAndIdNot(
        @Param("sortOrder") sortOrder: Int,
        @Param("excludeId") excludeId: Long,
    ): Boolean

    // sortOrder 중복 검증 (excludeId 포함, parentId not null일 때)
    @Query(
        "SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END FROM SurveyCategoryEntity c WHERE c.sortOrder = :sortOrder AND c.isDeleted = false AND c.parent.id = :parentId AND c.id <> :excludeId",
    )
    fun existsBySortOrderAndParentIdAndIsDeletedFalseAndIdNot(
        @Param("sortOrder") sortOrder: Int,
        @Param("parentId") parentId: Long,
        @Param("excludeId") excludeId: Long,
    ): Boolean

    // sortOrder 중복 검증 (parentId null, excludeId 없음 — 생성 시)
    @Query(
        "SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END FROM SurveyCategoryEntity c WHERE c.sortOrder = :sortOrder AND c.isDeleted = false AND c.parent IS NULL",
    )
    fun existsBySortOrderAndParentIsNullAndIsDeletedFalse(@Param("sortOrder") sortOrder: Int): Boolean

    // sortOrder 중복 검증 (parentId not null, excludeId 없음 — 생성 시)
    @Query(
        "SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END FROM SurveyCategoryEntity c WHERE c.sortOrder = :sortOrder AND c.isDeleted = false AND c.parent.id = :parentId",
    )
    fun existsBySortOrderAndParentIdIsAndIsDeletedFalse(@Param("sortOrder") sortOrder: Int, @Param("parentId") parentId: Long): Boolean
}
