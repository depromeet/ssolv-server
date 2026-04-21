package org.depromeet.team3.surveyresult

import jakarta.persistence.*
import org.depromeet.team3.common.BaseTimeEntity
import org.depromeet.team3.survey.SurveyEntity
import org.depromeet.team3.surveycategory.SurveyCategoryEntity

@Entity
@Table(name = "tb_survey_result")
class SurveyResultEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "survey_id", nullable = false)
    val survey: SurveyEntity,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "survey_category_id", nullable = false)
    val surveyCategory: SurveyCategoryEntity,
) : BaseTimeEntity()
