package org.depromeet.team3.place.model

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Google Places API Place Details 응답
 */
data class PlaceDetailsResponse(
    val id: String,
    @JsonProperty("displayName")
    val displayName: DisplayName? = null,
    @JsonProperty("formattedAddress")
    val formattedAddress: String? = null,
    val rating: Double? = null,
    @JsonProperty("userRatingCount")
    val userRatingCount: Int? = null,
    @JsonProperty("currentOpeningHours")
    val currentOpeningHours: CurrentOpeningHours? = null,
    @JsonProperty("regularOpeningHours")
    val regularOpeningHours: OpeningHours? = null,
    val reviews: List<Review>? = null,
    val photos: List<Photo>? = null,
    @JsonProperty("priceRange")
    val priceRange: PriceRange? = null,
    @JsonProperty("addressDescriptor")
    val addressDescriptor: AddressDescriptor? = null,
    val location: Location? = null,
    val types: List<String>? = null
) {
    data class DisplayName(
        val text: String,
        val languageCode: String? = null
    )
    
    data class CurrentOpeningHours(
        @JsonProperty("openNow")
        val openNow: Boolean? = null,
        @JsonProperty("weekdayDescriptions")
        val weekdayDescriptions: List<String>? = null
    )
    
    data class OpeningHours(
        @JsonProperty("weekdayDescriptions")
        val weekdayDescriptions: List<String>? = null
    )
    
    data class Review(
        @JsonProperty("authorAttribution")
        val authorAttribution: AuthorAttribution? = null,
        val rating: Double = 0.0,
        @JsonProperty("relativePublishTimeDescription")
        val relativePublishTimeDescription: String? = null,
        @JsonProperty("text")
        val text: TextContent? = null
    ) {
        data class AuthorAttribution(
            @JsonProperty("displayName")
            val displayName: String? = null
        )
        
        data class TextContent(
            val text: String = "",
            val languageCode: String? = null
        )
    }
    
    data class Photo(
        val name: String,
        @JsonProperty("widthPx")
        val widthPx: Int,
        @JsonProperty("heightPx")
        val heightPx: Int
    )
    
    data class PriceRange(
        @JsonProperty("startPrice")
        val startPrice: Money? = null,
        @JsonProperty("endPrice")
        val endPrice: Money? = null
    ) {
        data class Money(
            @JsonProperty("currencyCode")
            val currencyCode: String,
            val units: String? = null,
            val nanos: Int? = null
        )
    }
    
    data class AddressDescriptor(
        val areas: List<Area>? = null,
        val landmarks: List<Landmark>? = null
    ) {
        data class Area(
            val name: String,
            @JsonProperty("displayName")
            val displayName: TextContent? = null
        ) {
            data class TextContent(
                val text: String,
                val languageCode: String? = null
            )
        }
        
        data class Landmark(
            val name: String,
            @JsonProperty("displayName")
            val displayName: TextContent? = null,
            val types: List<String>? = null,
            @JsonProperty("straightLineDistanceMeters")
            val straightLineDistanceMeters: Double? = null
        ) {
            data class TextContent(
                val text: String,
                val languageCode: String? = null
            )
        }
    }
    
    data class Location(
        val latitude: Double,
        val longitude: Double
    )
}
