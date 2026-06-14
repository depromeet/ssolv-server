package org.depromeet.team3.batch.restaurant.adapter

import org.depromeet.team3.batch.restaurant.model.RestaurantCsvRecord
import org.depromeet.team3.batch.restaurant.model.StandardRestaurantRow
import org.depromeet.team3.batch.restaurant.support.RestaurantTextNormalizer
import org.depromeet.team3.restaurant.RestaurantBusinessStatus
import org.depromeet.team3.restaurant.RestaurantSourceType

abstract class AbstractRestaurantSourceAdapter(private val normalizer: RestaurantTextNormalizer) : RestaurantSourceAdapter {

    protected abstract val sourceType: RestaurantSourceType
    protected abstract val sourceKeyColumns: List<String>
    protected abstract val nameColumns: List<String>
    protected abstract val roadAddressColumns: List<String>
    protected abstract val lotAddressColumns: List<String>
    protected abstract val latitudeColumns: List<String>
    protected abstract val longitudeColumns: List<String>
    protected abstract val categoryColumns: List<String>
    protected abstract val regionCodeColumns: List<String>
    protected abstract val phoneColumns: List<String>
    protected abstract val statusColumns: List<String>

    override fun supports(sourceType: RestaurantSourceType): Boolean = this.sourceType == sourceType

    override fun convert(record: RestaurantCsvRecord): StandardRestaurantRow {
        val name = first(record, nameColumns)
            ?: throw IllegalArgumentException("name column is missing")
        val roadAddress = first(record, roadAddressColumns)
        val lotAddress = first(record, lotAddressColumns)
        val address = roadAddress ?: lotAddress ?: throw IllegalArgumentException("address column is missing")
        val sourceKey = first(record, sourceKeyColumns)
            ?: normalizer.sha256("${sourceType.name}|$name|$address")
        val normalizedName = normalizer.normalizeName(name)
        val normalizedAddress = normalizer.normalizeAddress(address)
        val latitude = first(record, latitudeColumns)?.toDoubleOrNull()
        val longitude = first(record, longitudeColumns)?.toDoubleOrNull()
        val category = first(record, categoryColumns)
        val regionCode = first(record, regionCodeColumns)
        val phoneNumber = first(record, phoneColumns)
        val status = parseStatus(first(record, statusColumns))
        val contentHash = normalizer.sha256(
            listOf(
                sourceType.name,
                normalizedName,
                normalizedAddress,
                latitude?.toString().orEmpty(),
                longitude?.toString().orEmpty(),
                category.orEmpty(),
                status.name,
            ).joinToString("|"),
        )

        return StandardRestaurantRow(
            sourceType = sourceType,
            sourceKey = sourceKey,
            name = name,
            normalizedName = normalizedName,
            roadAddress = roadAddress,
            lotAddress = lotAddress,
            address = normalizedAddress,
            latitude = latitude,
            longitude = longitude,
            category = category,
            regionCode = regionCode,
            phoneNumber = phoneNumber,
            businessStatus = status,
            contentHash = contentHash,
        )
    }

    protected fun first(record: RestaurantCsvRecord, candidates: List<String>): String? {
        for (candidate in candidates) {
            val matched = record.values.entries.firstOrNull { it.key.equals(candidate, ignoreCase = true) }
            val value = matched?.value?.trim()
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun parseStatus(value: String?): RestaurantBusinessStatus {
        if (value.isNullOrBlank()) return RestaurantBusinessStatus.UNKNOWN
        return when {
            value.contains("영업", ignoreCase = true) || value.equals("OPEN", ignoreCase = true) -> RestaurantBusinessStatus.ACTIVE
            value.contains("폐업", ignoreCase = true) || value.equals("CLOSED", ignoreCase = true) -> RestaurantBusinessStatus.CLOSED
            else -> RestaurantBusinessStatus.UNKNOWN
        }
    }
}
