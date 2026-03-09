package org.depromeet.team3.notification.infrastructure.fcm

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ResourceLoader
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

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

        println("----------------------------------------------")
        println("[FCM_DEBUG] Firebase 초기화 프로세스 시작 (Direct File Access Mode)")

        // 1. 절대 경로 리터럴 사용 (모든 설정값 무시)
        val fixedPath = "/home/ubuntu/17th-team3-Server/firebase-service-account.json"
        val fixedFile = File(fixedPath)
        
        println("[FCM_DEBUG] 파일 확인 시도 1 (정적 경로): ${fixedFile.absolutePath} -> 존재: ${fixedFile.exists()}")

        var inputStream: InputStream? = null

        if (fixedFile.exists() && fixedFile.isFile) {
            println("[FCM_DEBUG] ✅ 정적 경로에서 파일을 발견했습니다.")
            inputStream = FileInputStream(fixedFile)
        } else {
            // 2. 설정값(serviceAccountPath)을 이용한 확인
            val envPath = serviceAccountPath.replace("file:", "").filter { it.code in 32..126 }.trim()
            if (envPath.isNotBlank()) {
                val envFile = File(envPath)
                println("[FCM_DEBUG] 파일 확인 시도 2 (설정 경로): ${envFile.absolutePath} -> 존재: ${envFile.exists()}")
                if (envFile.exists() && envFile.isFile) {
                    println("[FCM_DEBUG] ✅ 설정 경로에서 파일을 발견했습니다.")
                    inputStream = FileInputStream(envFile)
                }
            }
        }

        // 3. 최종 초기화
        try {
            if (inputStream != null) {
                val options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(inputStream))
                    .build()
                FirebaseApp.initializeApp(options)
                println("[FCM_DEBUG] 🚀 Firebase 초기화가 최종적으로 성공했습니다!")
            } else {
                println("[FCM_DEBUG] ❌ 모든 경로에서 파일을 찾지 못했습니다.")
                println("[FCM_DEBUG] 현재 실행 중인 OS 유저: ${System.getProperty("user.name")}")
                println("[FCM_DEBUG] 상위 디렉토리 존재 확인: ${File("/home/ubuntu/17th-team3-Server").exists()}")
            }
        } catch (e: Exception) {
            println("[FCM_DEBUG] ❌ Firebase initializeApp 실행 중 오류: ${e.message}")
            e.printStackTrace()
        }
        println("----------------------------------------------")
    }
}