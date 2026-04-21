package org.depromeet.team3.place.model

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Google Places API (New) Text Search 요청
 */
data class PlacesTextSearchRequest(
    @JsonProperty("textQuery")
    val textQuery: String,
    @JsonProperty("languageCode")
    val languageCode: String = "ko",
    @JsonProperty("pageSize")
    val maxResultCount: Int = 10,
    @JsonProperty("locationBias")
    val locationBias: LocationBias? = null,
) {
    data class LocationBias(
        @JsonProperty("circle")
        val circle: Circle,
    )

    data class Circle(
        @JsonProperty("center")
        val center: Center,
        @JsonProperty("radius")
        val radius: Double,
    ) {
        data class Center(
            @JsonProperty("latitude")
            val latitude: Double,
            @JsonProperty("longitude")
            val longitude: Double,
        )
    }
}
