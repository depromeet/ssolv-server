package org.depromeet.team3.meeting

import kotlinx.coroutines.withContext
import org.depromeet.team3.common.util.CoroutineDispatchers
import io.github.oshai.kotlinlogging.KotlinLogging
import org.depromeet.team3.mapper.MeetingMapper
import org.depromeet.team3.station.StationRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Repository

@Repository
class MeetingQuery(
    private val meetingMapper: MeetingMapper,
    private val meetingJpaRepository: MeetingJpaRepository,
    private val stationRepository: StationRepository,
    private val coroutineDispatchers: CoroutineDispatchers
) : MeetingRepository {

    private val logger = KotlinLogging.logger { MeetingQuery::class.java.name }

    override suspend fun save(meeting: Meeting): Meeting = withContext(coroutineDispatchers.VT) {
        val entity = meetingMapper.toEntity(meeting)
        meetingMapper.toDomain(meetingJpaRepository.save(entity))
    }

    override suspend fun findMeetingsByUserId(userId: Long): List<Meeting> = withContext(coroutineDispatchers.VT) {
        meetingJpaRepository.findByHostUserId(userId).map { meetingMapper.toDomain(it) }
    }

    override suspend fun findById(id: Long): Meeting? = withContext(coroutineDispatchers.VT) {
        meetingJpaRepository.findByIdOrNull(id)?.let { meetingMapper.toDomain(it) }
    }
    
    /**
     * Meeting의 Station 좌표 조회
     * Meeting과 Station을 함께 조회하여 좌표를 반환
     */
    suspend fun getStationCoordinates(meetingId: Long): StationCoordinates? = withContext(coroutineDispatchers.VT) {
        val meeting = findById(meetingId)
        if (meeting == null) {
            logger.warn { "Meeting not found: meetingId=$meetingId" }
            return@withContext null
        }

        val station = stationRepository.findById(meeting.stationId)
        if (station == null) {
            logger.warn { "Station not found: stationId=${meeting.stationId}" }
            return@withContext null
        }

        // 좌표 유효성 검증 (한국 좌표 범위: 위도 33~43, 경도 124~132)
        if (station.locY < 33.0 || station.locY > 43.0 || station.locX < 124.0 || station.locX > 132.0) {
            logger.warn { "Invalid station coordinates: stationId=${station.id}, name=${station.name}, locX=${station.locX}, locY=${station.locY}" }
            return@withContext null
        }
        
        // DB: loc_x는 경도(longitude), loc_y는 위도(latitude)
        StationCoordinates(
            latitude = station.locY,   // loc_y가 위도
            longitude = station.locX   // loc_x가 경도
        )
    }
    
    data class StationCoordinates(
        val latitude: Double,
        val longitude: Double
    )
}