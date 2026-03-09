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
        if (FirebaseApp.getApps().isNotEmpty()) {
            return
        }

        val ubuntuPath = "file:/home/ubuntu/17th-team3-Server/firebase-service-account.json"
        val ubuntuFile = resourceLoader.getResource(ubuntuPath)
        
        val path = if (ubuntuFile.exists()) {
            ubuntuPath
        } else {
             serviceAccountPath.filter { it.code in 32..126 }.trim()
        }

        try {
            logger.info { "Firebase 초기화 시도 중... 설정 경로: $path" }
            val resource = resourceLoader.getResource(path)
            
            if (!resource.exists()) {
                logger.warn { "Firebase 서비스 계정 파일이 존재하지 않습니다. 경로: $path" }
                return
            }

            val credentials = GoogleCredentials.fromStream(resource.inputStream)
            val options = FirebaseOptions.builder()
                .setCredentials(credentials)
                .build()

            FirebaseApp.initializeApp(options)
            logger.info { "Firebase 애플리케이션이 파일($serviceAccountPath)을 통해 성공적으로 초기화되었습니다." }
        } catch (e: Exception) {
            logger.error(e) { "Firebase 초기화 중 오류 발생: ${e.message}" }
        }
    }
}