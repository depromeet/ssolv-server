package org.depromeet.team3.place.model

import com.fasterxml.jackson.annotation.JsonProperty

/**
 *  구글 플레이스 Text Search API 응답
 */
data class PlacesSearchResponse(val results: List<PlaceResult>, val status: String) {
    data class PlaceResult(
        @JsonProperty("place_id")
        val placeId: String,
        val name: String,
        @JsonProperty("formatted_address")
        val formattedAddress: String,
        val rating: Double? = null,
        @JsonProperty("user_ratings_total")
        val userRatingsTotal: Int? = null,
        @JsonProperty("opening_hours")
        val openingHours: OpeningHours? = null,
        val url: String? = null,
        val photos: List<Photo>? = null,
    ) {
        data class OpeningHours(
            @JsonProperty("open_now")
            val openNow: Boolean? = null,
        )

        data class Photo(
            @JsonProperty("photo_reference")
            val photoReference: String,
            val height: Int,
            val width: Int,
        )
    }
}
