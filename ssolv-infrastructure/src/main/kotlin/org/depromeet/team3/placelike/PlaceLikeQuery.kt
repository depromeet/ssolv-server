package org.depromeet.team3.placelike

import kotlinx.coroutines.withContext
import org.depromeet.team3.common.util.CoroutineDispatchers
import org.depromeet.team3.mapper.PlaceLikeMapper
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class PlaceLikeQuery(
    private val placeLikeJpaRepository: PlaceLikeJpaRepository,
    private val placeLikeMapper: PlaceLikeMapper,
    private val coroutineDispatchers: CoroutineDispatchers
) : PlaceLikeRepository {

    @Transactional
    override suspend fun save(placeLike: PlaceLike): PlaceLike  = withContext(coroutineDispatchers.VT) {
        val entity = placeLikeMapper.toEntity(placeLike)
        val saved = placeLikeJpaRepository.save(entity)
        placeLikeMapper.toDomain(saved)
    }

    @Transactional(readOnly = true)
    override suspend fun findByMeetingPlaceIdAndUserId(
        meetingPlaceId: Long,
        userId: Long
    ): PlaceLike? = withContext(coroutineDispatchers.VT) {
        placeLikeJpaRepository.findByMeetingPlaceIdAndUserId(meetingPlaceId, userId)
            ?.let { placeLikeMapper.toDomain(it) }
    }


    @Transactional
    override suspend fun deleteByMeetingPlaceIdAndUserId(
        meetingPlaceId: Long,
        userId: Long
    ): Unit = withContext(coroutineDispatchers.VT) {
        placeLikeJpaRepository.findByMeetingPlaceIdAndUserId(meetingPlaceId, userId)
            ?.let { placeLikeJpaRepository.delete(it) }
        Unit
    }

    @Transactional(readOnly = true)
    override suspend fun countByMeetingPlaceId(meetingPlaceId: Long): Long =
        withContext(coroutineDispatchers.VT) {
            placeLikeJpaRepository.countByMeetingPlaceId(meetingPlaceId)
        }

    @Transactional(readOnly = true)
    override suspend fun findByMeetingPlaceIds(meetingPlaceIds: List<Long>): List<PlaceLike> =
        withContext(coroutineDispatchers.VT) {
            if (meetingPlaceIds.isEmpty()) {
                emptyList()
            } else {
                placeLikeJpaRepository.findByMeetingPlaceIdIn(meetingPlaceIds)
                    .map { placeLikeMapper.toDomain(it) }
            }
        }
}
