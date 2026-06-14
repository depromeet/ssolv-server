package org.depromeet.team3.batch.restaurant.support

import org.springframework.stereotype.Component
import java.security.MessageDigest
import kotlin.math.*

@Component
class RestaurantTextNormalizer {

    fun normalizeName(value: String): String = value
        .trim()
        .lowercase()
        .replace(Regex("\\s+"), "")
        .replace(Regex("[()\\[\\]{}]"), "")

    fun normalizeAddress(value: String): String = value
        .trim()
        .replace(Regex("\\s+"), " ")

    fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun similarity(left: String, right: String): Double {
        val a = normalizeName(left)
        val b = normalizeName(right)
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return 1.0

        val distance = levenshtein(a, b)
        return 1.0 - (distance.toDouble() / max(a.length, b.length).toDouble())
    }

    fun distanceMeter(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val a = sin(dLat / 2).pow(2.0) + cos(rLat1) * cos(rLat2) * sin(dLon / 2).pow(2.0)
        return earthRadius * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun levenshtein(left: String, right: String): Int {
        val costs = IntArray(right.length + 1) { it }
        for (i in left.indices) {
            var previous = i
            costs[0] = i + 1
            for (j in right.indices) {
                val current = costs[j + 1]
                costs[j + 1] = minOf(
                    costs[j + 1] + 1,
                    costs[j] + 1,
                    previous + if (left[i] == right[j]) 0 else 1,
                )
                previous = current
            }
        }
        return costs[right.length]
    }
}
