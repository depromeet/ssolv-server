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

        // 1. 로그 라이브러리 문제를 배제하기 위해 표준 출력(println) 사용
        println("----------------------------------------------")
        println("[FCM_DEBUG] Firebase 초기화 프로세스 시작")
        
        // 2. 경로 시도 목록 (가장 확실한 절대 경로 우선)
        val candidatePaths = listOf(
            "/home/ubuntu/17th-team3-Server/firebase-service-account.json",
            serviceAccountPath.replace("file:", "").filter { it.code in 32..126 }.trim(),
            "firebase-service-account.json"
        )

        var selectedStream: InputStream? = null
        var foundBy: String? = null

        for (path in candidatePaths) {
            try {
                val file = File(path)
                println("[FCM_DEBUG] 경로 확인 중: ${file.absolutePath}")
                if (file.exists() && file.isFile) {
                    println("[FCM_DEBUG] ✅ 파일 발견: ${file.absolutePath}")
                    selectedStream = FileInputStream(file)
                    foundBy = "FileSystem: ${file.absolutePath}"
                    break
                }
            } catch (e: Exception) {
                println("[FCM_DEBUG] ⚠️ 경로 확인 중 오류 ($path): ${e.message}")
            }
        }

        // 3. 파일로 못 찾았을 경우 리소스 로더 fallback
        if (selectedStream == null) {
            try {
                val res = resourceLoader.getResource(serviceAccountPath.trim())
                if (res.exists()) {
                    println("[FCM_DEBUG] ✅ 리소스 로더에서 발견: $serviceAccountPath")
                    selectedStream = res.inputStream
                    foundBy = "ResourceLoader: $serviceAccountPath"
                }
            } catch (e: Exception) {
                println("[FCM_DEBUG] ⚠️ 리소스 로딩 중 오류: ${e.message}")
            }
        }

        // 4. 최종 초기화
        if (selectedStream != null) {
            try {
                val options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(selectedStream))
                    .build()
                FirebaseApp.initializeApp(options)
                println("[FCM_DEBUG] 🚀 Firebase 초기화 성공! (출처: $foundBy)")
                println("----------------------------------------------")
            } catch (e: Exception) {
                println("[FCM_DEBUG] ❌ Firebase initializeApp 실패: ${e.message}")
                e.printStackTrace()
                println("----------------------------------------------")
            }
        } else {
            println("[FCM_DEBUG] ❌ 모든 경로에서 키 파일을 찾지 못했습니다.")
            println("[FCM_DEBUG] 확인된 후보들: $candidatePaths")
            println("----------------------------------------------")
        }
    }
}