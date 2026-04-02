package org.depromeet.team3.place.model

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Google Places API (New) Text Search 응답
 */
data class PlacesTextSearchResponse(
    val places: List<Place>?
) {
    data class Place(
        val id: String,
        @JsonProperty("displayName")
        val displayName: DisplayName,
        @JsonProperty("formattedAddress")
        val formattedAddress: String,
        val rating: Double? = null,
        val userRatingCount: Int? = null,
        @JsonProperty("currentOpeningHours")
        val currentOpeningHours: OpeningHours? = null,
        val location: Location?,
        val types: List<String>? = null,
        val photos: List<Photo>? = null,
        @JsonProperty("googleMapsUri")
        val googleMapsUri: String? = null
    ) {
        data class Location(
            val latitude: Double,
            val longitude: Double
        )

        data class Photo(
            val name: String,
            val widthPx: Int? = null,
            val heightPx: Int? = null,
            val authorAttributions: List<AuthorAttribution>? = null
        )

        data class AuthorAttribution(
            val displayName: String? = null,
            val uri: String? = null,
            val photoUri: String? = null
        )

        data class DisplayName(
            val text: String,
            val languageCode: String? = null
        )
        
        data class OpeningHours(
            @JsonProperty("openNow")
            val openNow: Boolean? = null
        )
    }
}
