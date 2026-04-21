package org.depromeet.team3.place.model

import com.fasterxml.jackson.annotation.JsonProperty

data class NearbySearchRequest(
    @JsonProperty("includedTypes")
    val includedTypes: List<String>,
    @JsonProperty("maxResultCount")
    val maxResultCount: Int,
    @JsonProperty("locationRestriction")
    val locationRestriction: LocationRestriction,
    @JsonProperty("rankPreference")
    val rankPreference: String = "DISTANCE",
) {
    data class LocationRestriction(val circle: Circle) {
        data class Circle(val center: Center, val radius: Double) {
            data class Center(val latitude: Double, val longitude: Double)
        }
    }
}
