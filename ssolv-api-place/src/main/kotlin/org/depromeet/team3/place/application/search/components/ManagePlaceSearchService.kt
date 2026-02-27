package org.depromeet.team3.place.application.search.components

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.meeting.MeetingRepository
import org.depromeet.team3.meeting.exception.MeetingException
import org.depromeet.team3.meetingplacesearch.MeetingPlaceSearchEntity
import org.depromeet.team3.meetingplacesearch.MeetingPlaceSearchRepository
import org.depromeet.team3.place.dto.response.PlacesSearchResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 모임별 장소 검색 결과를 DB에 저장/조회
 * 
 * - 검색 결과 전체를 JSON으로 저장
 * - 재요청 시 JSON 역직렬화 후 좋아요만 업데이트
 */
@Service
class ManagePlaceSearchService(
    private val repository: MeetingPlaceSearchRepository,
    private val meetingRepository: MeetingRepository,
    private val objectMapper: ObjectMapper
) {

    /**
     * 검색 결과 저장
     */
    @Transactional
    fun save(meetingId: Long, result: PlacesSearchResponse) {
        val meeting = meetingRepository.findById(meetingId)
            ?: throw MeetingException(
                ErrorCode.MEETING_NOT_FOUND,
                mapOf("meetingId" to meetingId),
                "Meeting not found: $meetingId"
            )
        
        val endAt = meeting.endAt
            ?: throw MeetingException(
                ErrorCode.INVALID_END_TIME,
                mapOf("meetingId" to meetingId),
                "Meeting endAt is null: $meetingId"
            )
        
        // expiresAt = endAt + 6시간
        val expiresAt = endAt.plusHours(6)
        
        // 기존 데이터 삭제
        repository.findByMeetingId(meetingId)?.let { existing ->
            repository.delete(existing)
        }
        
        // 새로 저장
        val entity = MeetingPlaceSearchEntity(
            meetingId = meetingId,
            searchResultJson = objectMapper.writeValueAsString(result),
            expiresAt = expiresAt
        )
        repository.save(entity)
    }

    /**
     * 검색 결과 조회
     */
    fun find(meetingId: Long): PlacesSearchResponse? {
        val entity = repository.findByMeetingId(meetingId) ?: return null
        
        // 만료 확인
        if (entity.expiresAt.isBefore(LocalDateTime.now())) {
            return null
        }
        
        return objectMapper.readValue<PlacesSearchResponse>(entity.searchResultJson)
    }
}