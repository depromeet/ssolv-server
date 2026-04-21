package org.depromeet.team3.station

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface StationJpaRepository : JpaRepository<StationEntity, Long> {

    @Query("SELECT s.name FROM StationEntity s")
    fun findAllNames(): List<String>
}
