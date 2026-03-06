package org.depromeet.team3.notification.infrastructure.fcm

import com.google.firebase.messaging.ApnsConfig
import com.google.firebase.messaging.Aps
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.MulticastMessage
import com.google.firebase.messaging.Notification
import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.AndroidNotification
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.MessagingErrorCode
import io.github.oshai.kotlinlogging.KotlinLogging
import org.depromeet.team3.notification.domain.FcmClient
import org.springframework.stereotype.Component

@Component
class FcmClientImpl : FcmClient {
    private val logger = KotlinLogging.logger {}

    override fun sendMulticast(tokens: List<String>, title: String, body: String, data: Map<String, String>) {
        if (tokens.isEmpty()) return

        tokens.chunked(50).forEach { chunk ->
            try {
                val message = MulticastMessage.builder()
                    .addAllTokens(chunk)
                    .setNotification(
                        Notification.builder()
                            .setTitle(title)
                            .setBody(body)
                            .build()
                    )
                    .setApnsConfig(
                        ApnsConfig.builder()
                            .setAps(
                                Aps.builder()
                                    .setSound("default")
                                    .setBadge(1)
                                    .build()
                            ).build()
                    )
                    .setAndroidConfig(
                        AndroidConfig.builder()
                            .setPriority(AndroidConfig.Priority.HIGH)
                            .setNotification(
                                AndroidNotification.builder()
                                    .setSound("default")
                                    .build()
                            ).build()
                    )
                    .putAllData(data)
                    .build()

                val response = FirebaseMessaging.getInstance().sendEachForMulticast(message)
                
                if (response.failureCount > 0) {
                    val responses = response.responses
                    for (i in responses.indices) {
                        if (!responses[i].isSuccessful) {
                            val errorCode = responses[i].exception.messagingErrorCode
                            val errorMessage = responses[i].exception.message
                            
                            // 토큰이 유효하지 않은 경우 (삭제 대상)
                            if (errorCode == MessagingErrorCode.UNREGISTERED || errorCode == MessagingErrorCode.INVALID_ARGUMENT) {
                                logger.warn { "FCM 전송 실패 - 관리가 필요한 토큰입니다. (token: ${chunk[i]}, error: $errorCode, message: $errorMessage)" }
                            } else {
                                logger.error { "FCM 개별 전송 실패 (token: ${chunk[i]}, error: $errorCode, message: $errorMessage)" }
                            }
                        }
                    }
                }
                
                logger.info { "FCM 멀티캐스트 전송 완료: 성공 ${response.successCount}건, 실패 ${response.failureCount}건" }
            } catch (e: FirebaseMessagingException) {
                logger.error(e) { "Firebase Messaging API 오류 발생: ${e.messagingErrorCode} - ${e.message}" }
            } catch (e: Exception) {
                logger.error(e) { "FCM 전송 중 예상치 못한 오류 발생: ${e.message}" }
            }
        }
    }
}