package org.depromeet.team3.surveycategory

import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SurveyCategoryTest {

    @Test
    fun `SurveyCategory 생성한다`() {
        // given
        val now = LocalDateTime.now()
        val level = SurveyCategoryLevel.BRANCH
        val name = "한식"
        val sortOrder = 1

        // when
        val surveyCategory = SurveyCategory(
            level = level,
            name = name,
            sortOrder = sortOrder,
            createdAt = now,
        )

        // then
        assertNotNull(surveyCategory)
        assertEquals(level, surveyCategory.level)
        assertEquals(name, surveyCategory.name)
        assertEquals(sortOrder, surveyCategory.sortOrder)
        assertEquals(false, surveyCategory.isDeleted)
        assertEquals(now, surveyCategory.createdAt)
        assertNull(surveyCategory.updatedAt)
    }

    @Test
    fun `SurveyCategory with parent 생성한다`() {
        // given
        val now = LocalDateTime.now()
        val parentId = 1L
        val level = SurveyCategoryLevel.LEAF
        val name = "비빔밥"
        val sortOrder = 1

        // when
        val surveyCategory = SurveyCategory(
            parentId = parentId,
            level = level,
            name = name,
            sortOrder = sortOrder,
            createdAt = now,
        )

        // then
        assertNotNull(surveyCategory)
        assertEquals(parentId, surveyCategory.parentId)
        assertEquals(level, surveyCategory.level)
        assertEquals(name, surveyCategory.name)
        assertEquals(sortOrder, surveyCategory.sortOrder)
        assertEquals(false, surveyCategory.isDeleted)
        assertEquals(now, surveyCategory.createdAt)
    }

    @Test
    fun `data class의 equals와 hashCode가 올바르게 작동한다`() {
        // given
        val now = LocalDateTime.now()
        val surveyCategory1 = SurveyCategory(
            id = 1L,
            level = SurveyCategoryLevel.BRANCH,
            name = "한식",
            sortOrder = 1,
            createdAt = now,
        )

        val surveyCategory2 = SurveyCategory(
            id = 1L,
            level = SurveyCategoryLevel.BRANCH,
            name = "한식",
            sortOrder = 1,
            createdAt = now,
        )

        // when & then
        assertEquals(surveyCategory1, surveyCategory2)
        assertEquals(surveyCategory1.hashCode(), surveyCategory2.hashCode())
    }

    @Test
    fun `다른 이름의 SurveyCategory는 같지 않다`() {
        // given
        val now = LocalDateTime.now()
        val cuisineCategory = SurveyCategory(
            level = SurveyCategoryLevel.BRANCH,
            name = "한식",
            sortOrder = 1,
            createdAt = now,
        )

        val japaneseCategory = SurveyCategory(
            level = SurveyCategoryLevel.BRANCH,
            name = "일식",
            sortOrder = 1,
            createdAt = now,
        )

        // when & then
        kotlin.test.assertFalse(cuisineCategory.equals(japaneseCategory))
        kotlin.test.assertNotEquals(cuisineCategory.hashCode(), japaneseCategory.hashCode())
    }

    @Test
    fun `SurveyCategory copy로 수정한다`() {
        // given
        val now = LocalDateTime.now()
        val originalCategory = SurveyCategory(
            id = 1L,
            parentId = null,
            level = SurveyCategoryLevel.BRANCH,
            name = "한식",
            sortOrder = 1,
            isDeleted = false,
            createdAt = now,
            updatedAt = null,
        )

        // when
        val updatedCategory = originalCategory.copy(
            name = "전통한식",
            sortOrder = 2,
            updatedAt = now,
        )

        // then
        assertEquals(originalCategory.id, updatedCategory.id)
        assertEquals(originalCategory.parentId, updatedCategory.parentId)
        assertEquals(originalCategory.level, updatedCategory.level)
        assertEquals("전통한식", updatedCategory.name)
        assertEquals(2, updatedCategory.sortOrder)
        assertEquals(originalCategory.isDeleted, updatedCategory.isDeleted)
        assertEquals(originalCategory.createdAt, updatedCategory.createdAt)
        assertEquals(now, updatedCategory.updatedAt)
    }

    @Test
    fun `SurveyCategory soft delete 처리한다`() {
        // given
        val now = LocalDateTime.now()
        val activeCategory = SurveyCategory(
            id = 1L,
            parentId = null,
            level = SurveyCategoryLevel.BRANCH,
            name = "한식",
            sortOrder = 1,
            isDeleted = false,
            createdAt = now,
            updatedAt = null,
        )

        // when
        val deletedCategory = activeCategory.copy(isDeleted = true)

        // then
        assertEquals(activeCategory.id, deletedCategory.id)
        assertEquals(activeCategory.name, deletedCategory.name)
        assertEquals(true, deletedCategory.isDeleted)
        assertEquals(activeCategory.createdAt, deletedCategory.createdAt)
    }
}
