package org.depromeet.team3.batch.restaurant.model

import org.depromeet.team3.restaurant.RestaurantBusinessStatus
import org.depromeet.team3.restaurant.RestaurantSourceType

data class RestaurantCsvRecord(val rowNumber: Long, val values: Map<String, String>, val rawPayload: String)

data class RestaurantImportManifest(
    val sourceType: RestaurantSourceType,
    val importMonth: String,
    val charset: String? = null,
    val chunkSize: Int? = null,
    val files: List<RestaurantImportManifestFile> = emptyList(),
)

data class RestaurantImportManifestFile(
    val s3Key: String,
    val charset: String? = null,
    val chunkSize: Int? = null,
)

data class StandardRestaurantRow(
    val sourceType: RestaurantSourceType,
    val sourceKey: String,
    val name: String,
    val normalizedName: String,
    val roadAddress: String?,
    val lotAddress: String?,
    val address: String,
    val latitude: Double?,
    val longitude: Double?,
    val category: String?,
    val regionCode: String?,
    val phoneNumber: String?,
    val businessStatus: RestaurantBusinessStatus,
    val contentHash: String,
)
