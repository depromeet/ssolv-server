package org.depromeet.team3.place.application.execution

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.depromeet.team3.common.util.CoroutineDispatchers
import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.stream.StreamListener
import org.springframework.stereotype.Component

/*
 * Redis Streams를 통해 비동기 식당 도출 요청을 수신하여 식당 검색 로직을 실행하는 Consumer
 */
@Component
class PlaceSearchConsumer(
    private val executePlaceSearchService: ExecutePlaceSearchService,
    private val stringRedisTemplate: StringRedisTemplate,
    private val coroutineDispatchers: CoroutineDispatchers
) : StreamListener<String, MapRecord<String, String, String>> {

    private val logger = LoggerFactory.getLogger(PlaceSearchConsumer::class.java)
    private val scope = CoroutineScope(Dispatchers.Default)

    override fun onMessage(message: MapRecord<String, String, String>) {
        val meetingIdStr = message.value["meetingId"]
        val meetingId = meetingIdStr?.toLongOrNull()

        if (meetingId != null) {
            logger.info("모임 {} 의 식당 검색 및 도출 요청 수신 (messageId: {})", meetingId, message.id)
            scope.launch(coroutineDispatchers.VT) {
                try {
                    // 식당 검색 및 결과 추천 실행
                    executePlaceSearchService.execute(meetingId)
                    
                    // 성공 완료 후 ACK
                    val ackCount = stringRedisTemplate.opsForStream<String, String>()
                        .acknowledge("meeting_calculation_group", message)
                        
                    if (ackCount != null && ackCount > 0) {
                        logger.info("모임 {} 의 식당 검색 추천 및 ACK 완료", meetingId)
                    }
                } catch (e: Exception) {
                    logger.error("모임 $meetingId 의 식당 검색 도출 중 오류 발생", e)
                    // 실패 시 ACK하지 않아 PEL에 머무르게 됨. 이후 WatchDog(Scheduler)가 재처리
                }
            }
        } else {
            logger.warn("Stream 메시지 파싱 실패: meetingId가 존재하지 않거나 유효하지 않음 (message: {})", message)
            stringRedisTemplate.opsForStream<String, String>().acknowledge("meeting_calculation_group", message)
        }
    }
}