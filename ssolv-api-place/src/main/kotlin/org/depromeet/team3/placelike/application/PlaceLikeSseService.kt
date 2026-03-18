package org.depromeet.team3.placelike.application

import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.MessageListener
import org.springframework.data.redis.listener.ChannelTopic
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
    
    // meetingId -> MessageListener (to enable removal later)
    private val activeListeners = ConcurrentHashMap<Long, MessageListener>()

    fun subscribe(meetingId: Long): SseEmitter {
        val emitter = SseEmitter(60 * 1000L * 5) // 5 minutes timeout
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

        ensureRedisListener(meetingId)

        return emitter
    }

    private fun ensureRedisListener(meetingId: Long) {
        activeListeners.computeIfAbsent(meetingId) {
            val listener = MessageListener { message, _ ->
                val payload = String(message.body)
                broadcast(meetingId, payload)
            }
            val topic = ChannelTopic("meeting:updates:$meetingId")
            redisMessageListenerContainer.addMessageListener(listener, topic)
            logger.debug("Subscribed to Redis topic for meeting: {}", meetingId)
            listener
        }
    }

    private fun cleanup(meetingId: Long, emitter: SseEmitter) {
        val meetingEmitters = emitters[meetingId] ?: return
        meetingEmitters.remove(emitter)
        
        if (meetingEmitters.isEmpty()) {
            emitters.remove(meetingId)
            
            // 더 이상 구독자가 없으면 Redis 리스너 해제
            activeListeners.remove(meetingId)?.let { listener ->
                val topic = ChannelTopic("meeting:updates:$meetingId")
                redisMessageListenerContainer.removeMessageListener(listener, topic)
                logger.debug("Unsubscribed from Redis topic for meeting: {}", meetingId)
            }
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