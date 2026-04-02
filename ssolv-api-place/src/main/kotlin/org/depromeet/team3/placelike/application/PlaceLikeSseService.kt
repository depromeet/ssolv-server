package org.depromeet.team3.placelike.application

import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.MessageListener
import org.springframework.data.redis.listener.PatternTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

@Service
class PlaceLikeSseService(
    private val redisMessageListenerContainer: RedisMessageListenerContainer
) {
    private val logger = LoggerFactory.getLogger(PlaceLikeSseService::class.java)
    
    // meetingId -> active SseEmitters
    private val emitters = ConcurrentHashMap<Long, MutableList<SseEmitter>>()
    
    // 하트비트용 스케줄러 (30초마다 모든 에미터에 ping 전송)
    private val heartbeatExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()

    init {
        // 1. 하트비트 스케줄러 등록
        heartbeatExecutor.scheduleAtFixedRate({
            emitters.forEach { (meetingId, meetingEmitters) ->
                meetingEmitters.forEach { emitter ->
                    try {
                        emitter.send(SseEmitter.event().name("ping").data("heartbeat"))
                    } catch (e: Exception) {
                        cleanup(meetingId, emitter)
                    }
                }
            }
        }, 30, 30, java.util.concurrent.TimeUnit.SECONDS)

        // 2. 전역 Redis 리스너 등록
        val globalListener = MessageListener { message, _ ->
            try {
                val channel = String(message.channel)
                val meetingId = channel.substringAfterLast(":").toLongOrNull()
                if (meetingId != null) {
                    val payload = String(message.body)
                    broadcast(meetingId, payload)
                }
            } catch (e: Exception) {
                logger.error("Error in global Redis listener", e)
            }
        }
        redisMessageListenerContainer.addMessageListener(globalListener, PatternTopic("meeting:updates:*"))
        logger.info("Global Redis listener registered for 'meeting:updates:*'")
    }

    fun subscribe(meetingId: Long): SseEmitter {
        val emitter = SseEmitter(60 * 1000L * 30) // 30분 타임아웃
        val meetingEmitters = emitters.computeIfAbsent(meetingId) { CopyOnWriteArrayList() }
        meetingEmitters.add(emitter)

        emitter.onCompletion { cleanup(meetingId, emitter) }
        emitter.onTimeout { cleanup(meetingId, emitter) }
        emitter.onError { cleanup(meetingId, emitter) }

        // 초기 연결 시 더미 데이터 전송 (연결 끊김 방지)
        try {
            emitter.send(SseEmitter.event().name("connect").data("connected"))
        } catch (e: Exception) {
            cleanup(meetingId, emitter)
        }

        return emitter
    }

    private fun cleanup(meetingId: Long, emitter: SseEmitter) {
        val meetingEmitters = emitters[meetingId] ?: return
        meetingEmitters.remove(emitter)
        
        if (meetingEmitters.isEmpty()) {
            emitters.remove(meetingId)
        }
    }

    private fun broadcast(meetingId: Long, payload: String) {
        val meetingEmitters = emitters[meetingId] ?: return
        val deadEmitters = mutableListOf<SseEmitter>()

        meetingEmitters.forEach { emitter ->
            try {
                emitter.send(SseEmitter.event()
                    .name("placeUpdate")
                    .data(payload)
                    .id(System.currentTimeMillis().toString()))
            } catch (e: Exception) {
                deadEmitters.add(emitter)
            }
        }

        if (deadEmitters.isNotEmpty()) {
            deadEmitters.forEach { cleanup(meetingId, it) }
        }
    }
}