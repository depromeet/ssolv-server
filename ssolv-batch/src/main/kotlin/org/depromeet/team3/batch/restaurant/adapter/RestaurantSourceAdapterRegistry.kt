package org.depromeet.team3.batch.restaurant.adapter

import org.depromeet.team3.restaurant.RestaurantSourceType
import org.springframework.stereotype.Component

@Component
class RestaurantSourceAdapterRegistry(private val adapters: List<RestaurantSourceAdapter>) {
    fun get(sourceType: RestaurantSourceType): RestaurantSourceAdapter = adapters
        .firstOrNull { it.supports(sourceType) }
        ?: throw IllegalArgumentException("Unsupported restaurant source type: $sourceType")
}
