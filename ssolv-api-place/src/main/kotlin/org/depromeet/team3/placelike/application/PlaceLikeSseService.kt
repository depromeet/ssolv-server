package org.depromeet.team3.placelike.application

import org.slf4j.LoggerFactory
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
    private val emitters = ConcurrentHashMap<Long, MutableList<SseEmitter>>()

    fun subscribe(meetingId: Long): SseEmitter {
        val emitter = SseEmitter(60 * 1000L * 5)    // 5 minutes timeout
        val meetingEmitters = emitters.computeIfAbsent(meetingId) { CopyOnWriteArrayList() }
        meetingEmitters.add(emitter)

        emitter.onCompletion { meetingEmitters.remove(emitter) }
        emitter.onTimeout { 
            emitter.complete()
            meetingEmitters.remove(emitter) 
        }
        emitter.onError { 
            emitter.complete()
            meetingEmitters.remove(emitter) 
        }

        // 초기 연결 시 더미 데이터 전송 (연결 끊김 방지)
        try {
            emitter.send(SseEmitter.event().name("connect").data("connected"))
        } catch (e: Exception) {
            meetingEmitters.remove(emitter)
        }

        // Redis 리스너 등록 (이미 등록되어 있다면 생략하게 디자인 하거나, 공통 리스너 사용)
        ensureRedisListener(meetingId)

        return emitter
    }

    private val activeTopics = ConcurrentHashMap.newKeySet<Long>()

    private fun ensureRedisListener(meetingId: Long) {
        if (activeTopics.add(meetingId)) {
            val topic = ChannelTopic("meeting:updates:$meetingId")
            redisMessageListenerContainer.addMessageListener(
                { message, _ ->
                    val placeId = String(message.body)
                    broadcast(meetingId, placeId)
                },
                topic
            )
            logger.debug("Subscribed to Redis topic for meeting: {}", meetingId)
        }
    }

    private fun broadcast(meetingId: Long, placeId: String) {
        val meetingEmitters = emitters[meetingId] ?: return
        val deadEmitters = mutableListOf<SseEmitter>()

        meetingEmitters.forEach { emitter ->
            try {
                emitter.send(SseEmitter.event()
                    .name("placeUpdate")
                    .data(placeId)
                    .id(System.currentTimeMillis().toString()))
            } catch (e: Exception) {
                deadEmitters.add(emitter)
            }
        }

        if (deadEmitters.isNotEmpty()) {
            meetingEmitters.removeAll(deadEmitters)
        }
    }
}