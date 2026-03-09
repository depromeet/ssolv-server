package org.depromeet.team3.notification.infrastructure.fcm

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ResourceLoader

@Configuration
class FirebaseConfig(
    @Value("\${firebase.service-account-path:classpath:firebase-service-account.json}")
    private val serviceAccountPath: String,
    private val resourceLoader: ResourceLoader
) {
    private val logger = KotlinLogging.logger {}

    @PostConstruct
    fun init() {
        if (FirebaseApp.getApps().isNotEmpty()) return

        // 시도해볼 후보 경로들 (절대 경로 및 상대 경로)
        val candidatePaths = listOf(
            "/home/ubuntu/17th-team3-Server/firebase-service-account.json",
            "firebase-service-account.json",
            serviceAccountPath.replace("file:", "").filter { it.code in 32..126 }.trim()
        )

        var finalFile: java.io.File? = null

        for (p in candidatePaths) {
            try {
                val file = java.io.File(p)
                if (file.exists() && file.isFile) {
                    finalFile = file
                    break
                }
            } catch (e: Exception) {
                continue
            }
        }

        try {
            if (finalFile != null) {
                logger.info { "Firebase 초기화 시도 중 (파일 시스템): ${finalFile.absolutePath}" }
                val credentials = GoogleCredentials.fromStream(finalFile.inputStream())
                val options = FirebaseOptions.builder()
                    .setCredentials(credentials)
                    .build()

                FirebaseApp.initializeApp(options)
                logger.info { "✅ Firebase 초기화 성공! (파일: ${finalFile.absolutePath})" }
                return
            }

            // 파일 시스템에서 못 찾은 경우에만 클래스패스(ResourceLoader) 시도
            val cleanPath = serviceAccountPath.trim()
            val resource = resourceLoader.getResource(cleanPath)
            
            if (resource.exists()) {
                logger.info { "Firebase 초기화 시도 중 (리소스): $cleanPath" }
                val credentials = GoogleCredentials.fromStream(resource.inputStream)
                val options = FirebaseOptions.builder()
                    .setCredentials(credentials)
                    .build()

                FirebaseApp.initializeApp(options)
                logger.info { "✅ Firebase 초기화 성공! (리소스: $cleanPath)" }
            } else {
                logger.warn { "❌ Firebase 초기화 실패: 파일을 찾을 수 없습니다. (후보 경로 확인 필요)" }
            }
        } catch (e: Exception) {
            logger.error(e) { "❌ Firebase 초기화 중 오류 발생: ${e.message}" }
        }
    }
}