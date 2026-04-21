package org.depromeet.team3.fixture

import org.depromeet.team3.station.StationEntity

object StationFixture {

    fun createEntity(id: Long? = 1L, name: String = "강남", locX: Double = 127.027, locY: Double = 37.497) =
        StationEntity(id = id, name = name, locX = locX, locY = locY)

    fun createEntityWithoutId(name: String = "강남", locX: Double = 127.027, locY: Double = 37.497) =
        createEntity(id = null, name = name, locX = locX, locY = locY)
}
