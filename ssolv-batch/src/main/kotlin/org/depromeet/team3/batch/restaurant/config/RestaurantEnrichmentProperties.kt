package org.depromeet.team3.batch.restaurant.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "restaurant.enrichment")
data class RestaurantEnrichmentProperties(var enabled: Boolean = false, var maxResults: Int = 5, var radiusMeter: Double = 500.0)
