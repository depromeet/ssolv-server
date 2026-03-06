package org.depromeet.team3.notification.infrastructure.fcm

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import java.io.ByteArrayInputStream

@Configuration
class FirebaseConfig(
    @Value("\${firebase.service-account-key:}")
    private val serviceAccountKey: String
) {
    private val logger = KotlinLogging.logger {}

    @PostConstruct
    fun init() {
        if (FirebaseApp.getApps().isNotEmpty()) {
            return
        }

        if (serviceAccountKey.isBlank()) {
            logger.warn { "Firebase 서비스 계정 키(FIREBASE_SERVICE_ACCOUNT_KEY)가 비어 있습니다. 푸시 알림이 작동하지 않습니다." }
            return
        }

        try {
            val credentials = GoogleCredentials.fromStream(ByteArrayInputStream(serviceAccountKey.toByteArray()))
            val options = FirebaseOptions.builder()
                .setCredentials(credentials)
                .build()

            FirebaseApp.initializeApp(options)
            logger.info { "Firebase 애플리케이션이 성공적으로 초기화되었습니다." }
        } catch (e: java.io.IOException) {
            logger.error(e) { "Firebase 서비스 계정 키를 읽는 중 오류가 발생했습니다. 키 형식이 유효한지 확인하세요." }
        } catch (e: Exception) {
            logger.error(e) { "인증 서버와의 통신 또는 기타 문제로 Firebase 초기화에 실패했습니다: ${e.message}" }
        }
    }
}