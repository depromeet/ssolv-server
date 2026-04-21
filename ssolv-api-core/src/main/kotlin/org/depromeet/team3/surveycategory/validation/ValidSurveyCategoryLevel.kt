package org.depromeet.team3.surveycategory.validation

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import org.depromeet.team3.surveycategory.SurveyCategoryLevel
import kotlin.reflect.KClass

@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [ValidSurveyCategoryLevelValidator::class])
annotation class ValidSurveyCategoryLevel(
    val message: String = "카테고리 레벨은 BRANCH 또는 LEAF만 허용됩니다",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

class ValidSurveyCategoryLevelValidator : ConstraintValidator<ValidSurveyCategoryLevel, SurveyCategoryLevel> {
    override fun isValid(value: SurveyCategoryLevel?, context: ConstraintValidatorContext?): Boolean =
        value == null || value == SurveyCategoryLevel.BRANCH || value == SurveyCategoryLevel.LEAF
}
