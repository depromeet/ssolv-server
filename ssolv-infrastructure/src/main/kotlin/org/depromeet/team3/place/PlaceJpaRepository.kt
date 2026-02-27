package org.depromeet.team3.place

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
interface PlaceJpaRepository : JpaRepository<PlaceEntity, Long> {
    fun findByGooglePlaceIdIn(googlePlaceIds: List<String>): List<PlaceEntity>
    fun deleteByUpdatedAtBefore(dateTime: LocalDateTime): Int
    fun findByGooglePlaceId(googlePlaceId: String): PlaceEntity?
}
