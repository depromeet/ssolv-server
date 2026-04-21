package org.depromeet.team3.place.model

import com.fasterxml.jackson.annotation.JsonProperty

data class NearbySearchResponse(val places: List<Place>?) {
    data class Place(
        val id: String,
        @JsonProperty("displayName")
        val displayName: DisplayName,
        val location: Location,
    ) {
        data class DisplayName(val text: String, val languageCode: String? = null)

        data class Location(val latitude: Double, val longitude: Double)
    }
}
