package org.depromeet.team3.fixture

import org.depromeet.team3.surveycategory.SurveyCategoryEntity
import org.depromeet.team3.surveycategory.SurveyCategoryLevel

object SurveyCategoryFixture {

    fun createEntity(
        id: Long? = 1L,
        level: SurveyCategoryLevel = SurveyCategoryLevel.LEAF,
        name: String = "한식",
        sortOrder: Int = 1,
        parent: SurveyCategoryEntity? = null,
        isDeleted: Boolean = false
    ) = SurveyCategoryEntity(
        id = id,
        level = level,
        name = name,
        sortOrder = sortOrder,
        parent = parent,
        isDeleted = isDeleted
    )

    fun createEntityWithoutId(
        level: SurveyCategoryLevel = SurveyCategoryLevel.LEAF,
        name: String = "한식",
        sortOrder: Int = 1,
        parent: SurveyCategoryEntity? = null
    ) = createEntity(id = null, level = level, name = name, sortOrder = sortOrder, parent = parent)
}
