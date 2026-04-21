package org.depromeet.team3.surveycategory

import com.querydsl.core.annotations.QueryEntity
import jakarta.persistence.*
import org.depromeet.team3.common.BaseTimeEntity

@Entity
@QueryEntity
@Table(
    name = "tb_survey_category_master",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_survey_category_name_parent",
            columnNames = ["name", "parent_id"],
        ),
        UniqueConstraint(
            name = "uk_survey_category_order_parent",
            columnNames = ["sort_order", "parent_id"],
        ),
    ],
)
class SurveyCategoryEntity(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    var parent: SurveyCategoryEntity? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "level", nullable = false)
    var level: SurveyCategoryLevel,

    @Column(name = "name", nullable = false)
    var name: String,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int,

    @Column(name = "is_deleted", nullable = false)
    var isDeleted: Boolean = false,
) : BaseTimeEntity()
