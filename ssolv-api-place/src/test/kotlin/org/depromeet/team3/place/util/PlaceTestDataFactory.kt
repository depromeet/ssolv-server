package org.depromeet.team3.place.util

import org.depromeet.team3.place.model.PlaceDetailsResponse
import org.depromeet.team3.place.model.PlacesTextSearchResponse

object PlaceTestDataFactory {

    fun createGooglePlacesSearchResponse(resultCount: Int = 5, placeId: String? = null): PlacesTextSearchResponse {
        val places = (1..resultCount).map { index ->
            PlacesTextSearchResponse.Place(
                id = placeId ?: "place_id_$index",
                displayName = PlacesTextSearchResponse.Place.DisplayName(
                    text = "맛집 $index",
                    languageCode = "ko",
                ),
                formattedAddress = "서울시 강남구 $index",
                rating = 4.5,
                userRatingCount = 100,
                currentOpeningHours = PlacesTextSearchResponse.Place.OpeningHours(
                    openNow = true,
                ),
                location = PlacesTextSearchResponse.Place.Location(
                    latitude = 37.5 + index * 0.01,
                    longitude = 127.0 + index * 0.01,
                ),
                photos = listOf(
                    PlacesTextSearchResponse.Place.Photo(
                        name = "places/${placeId ?: "place_id_$index"}/photos/ref",
                        widthPx = 400,
                        heightPx = 400,
                    ),
                ),
            )
        }

        return PlacesTextSearchResponse(
            places = places,
        )
    }

    fun createGooglePlaceDetailsResponse(placeId: String = "place_id_1"): PlaceDetailsResponse = PlaceDetailsResponse(
        id = placeId,
        displayName = PlaceDetailsResponse.DisplayName(
            text = "맛집",
            languageCode = "ko",
        ),
        formattedAddress = "서울시 강남구",
        rating = 4.5,
        userRatingCount = 100,
        regularOpeningHours = PlaceDetailsResponse.OpeningHours(
            weekdayDescriptions = listOf(
                "월요일: 오전 10:00~오후 10:00",
                "화요일: 오전 10:00~오후 10:00",
            ),
        ),
        reviews = listOf(
            PlaceDetailsResponse.Review(
                authorAttribution = PlaceDetailsResponse.Review.AuthorAttribution(
                    displayName = "리뷰어1",
                ),
                rating = 5.0,
                relativePublishTimeDescription = "1주 전",
                text = PlaceDetailsResponse.Review.TextContent(
                    text = "정말 맛있어요!",
                    languageCode = "ko",
                ),
            ),
            PlaceDetailsResponse.Review(
                authorAttribution = PlaceDetailsResponse.Review.AuthorAttribution(
                    displayName = "리뷰어2",
                ),
                rating = 4.0,
                relativePublishTimeDescription = "2주 전",
                text = PlaceDetailsResponse.Review.TextContent(
                    text = "괜찮습니다",
                    languageCode = "ko",
                ),
            ),
        ),
        photos = listOf(
            PlaceDetailsResponse.Photo(
                name = "places/$placeId/photos/detail_photo_ref_1",
                widthPx = 800,
                heightPx = 800,
            ),
        ),
        priceRange = null,
        addressDescriptor = null,
        location = PlaceDetailsResponse.Location(
            latitude = 37.5665,
            longitude = 126.9780,
        ),
    )
}
