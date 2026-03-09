package org.depromeet.team3.notification.infrastructure.fcm

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import java.io.File
import java.io.InputStream

@Configuration
class FirebaseConfig(
    @Value("\${firebase.service-account-path:classpath:firebase-service-account.json}")
    private val serviceAccountPath: String,
) {

    @PostConstruct
    fun init() {
        if (FirebaseApp.getApps().isNotEmpty()) return

        println("----------------------------------------------")
        println("[FCM_DEBUG] Firebase 초기화 프로세스 시작")

        // 1. 파일 찾기 (고정 경로 -> 설정 경로 -> Classpath 순)
        val fixedPath = "/home/ubuntu/17th-team3-Server/firebase-service-account.json"
        val fixedFile = File(fixedPath)
        
        val cleanPath = serviceAccountPath.replace("file:", "").replace("classpath:", "").trim()
        val envFile = File(cleanPath)

        val inputStream: InputStream? = when {
            fixedFile.exists() && fixedFile.isFile -> {
                println("[FCM_DEBUG] ✅ 고정 경로에서 파일을 찾았습니다: ${fixedFile.absolutePath}")
                fixedFile.inputStream()
            }
            envFile.exists() && envFile.isFile -> {
                println("[FCM_DEBUG] ✅ 설정 경로(File)에서 파일을 찾았습니다: ${envFile.absolutePath}")
                envFile.inputStream()
            }
            else -> {
                val resourcePath = serviceAccountPath.replace("classpath:", "")
                println("[FCM_DEBUG] 🔍 Classpath에서 리소스를 시도합니다: $resourcePath")
                javaClass.classLoader.getResourceAsStream(resourcePath)
            }
        }

        if (inputStream == null) {
            println("[FCM_DEBUG] ❌ Firebase 설정 파일을 어디에서도 찾을 수 없습니다.")
            println("----------------------------------------------")
            return
        }

        try {
            // [핵심] 수동 문자열 정제 로직을 모두 제거하고 스트림을 직접 전달합니다.
            // GoogleCredentials는 표준 JSON 및 PEM 형식을 직접 파싱하여 '+' 문자 포함 시에도 정상 작동합니다.
            val options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(inputStream))
                .build()
            
            FirebaseApp.initializeApp(options)
            println("[FCM_DEBUG] 🚀 [최종 성공] Firebase 초기화가 완료되었습니다!")
        } catch (e: Exception) {
            println("[FCM_DEBUG] ❌ 초기화 실패: ${e.message}")
            e.printStackTrace()
        } finally {
            inputStream.close()
        }
        println("----------------------------------------------")
    }
}