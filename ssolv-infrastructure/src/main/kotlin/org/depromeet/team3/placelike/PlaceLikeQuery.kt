package org.depromeet.team3.placelike

import org.depromeet.team3.mapper.PlaceLikeMapper
import org.springframework.stereotype.Component

@Component
class PlaceLikeQuery(
    private val placeLikeJpaRepository: PlaceLikeJpaRepository,
    private val placeLikeMapper: PlaceLikeMapper,
) : PlaceLikeRepository {

    override suspend fun save(placeLike: PlaceLike): PlaceLike {
        val entity = placeLikeMapper.toEntity(placeLike)
        val saved = placeLikeJpaRepository.save(entity)
        return placeLikeMapper.toDomain(saved)
    }

    override suspend fun findByMeetingPlaceIdAndUserId(
        meetingPlaceId: Long,
        userId: Long
    ): PlaceLike? {
        return placeLikeJpaRepository.findByMeetingPlaceIdAndUserId(meetingPlaceId, userId)
            ?.let { placeLikeMapper.toDomain(it) }
    }

    override suspend fun deleteByMeetingPlaceIdAndUserId(
        meetingPlaceId: Long,
        userId: Long
    ): Unit {
        placeLikeJpaRepository.findByMeetingPlaceIdAndUserId(meetingPlaceId, userId)
            ?.let { placeLikeJpaRepository.delete(it) }
    }

    override suspend fun countByMeetingPlaceId(meetingPlaceId: Long): Long {
        return placeLikeJpaRepository.countByMeetingPlaceId(meetingPlaceId)
    }

    override suspend fun findByMeetingPlaceIds(meetingPlaceIds: List<Long>): List<PlaceLike> {
        return if (meetingPlaceIds.isEmpty()) {
            emptyList()
        } else {
            placeLikeJpaRepository.findByMeetingPlaceIdIn(meetingPlaceIds)
                .map { placeLikeMapper.toDomain(it) }
        }
    }
}
