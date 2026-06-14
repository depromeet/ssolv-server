package org.depromeet.team3.restaurant

enum class RestaurantSourceType {
    GYEONGGI,
    SEOUL,
    SMALL_BUSINESS,
}

enum class RestaurantImportStatus {
    REGISTERED,
    RUNNING,
    COMPLETED,
    FAILED,
}

enum class RestaurantBusinessStatus {
    ACTIVE,
    INACTIVE_CANDIDATE,
    CLOSED,
    UNKNOWN,
}

enum class RestaurantDiffType {
    ADDED,
    UPDATED,
    UNCHANGED,
    MISSING,
    INVALID,
}

enum class RestaurantGoogleMatchStatus {
    MATCHED,
    UNMATCHED,
    FAILED,
}
