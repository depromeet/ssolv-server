package org.depromeet.team3.restaurant

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface RestaurantEnrichmentAttemptJpaRepository : JpaRepository<RestaurantEnrichmentAttemptEntity, Long>
