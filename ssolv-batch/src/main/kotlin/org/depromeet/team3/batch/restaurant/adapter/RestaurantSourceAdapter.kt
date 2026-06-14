package org.depromeet.team3.batch.restaurant.adapter

import org.depromeet.team3.batch.restaurant.model.RestaurantCsvRecord
import org.depromeet.team3.batch.restaurant.model.StandardRestaurantRow
import org.depromeet.team3.restaurant.RestaurantSourceType

interface RestaurantSourceAdapter {
    fun supports(sourceType: RestaurantSourceType): Boolean
    fun convert(record: RestaurantCsvRecord): StandardRestaurantRow
}
