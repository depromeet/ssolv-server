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

        val fixedPath = "/home/ubuntu/17th-team3-Server/firebase-service-account.json"
        val fixedFile = File(fixedPath)
        
        var jsonContent: String? = null

        if (fixedFile.exists() && fixedFile.isFile) {
            println("[FCM_DEBUG] ✅ 파일을 찾았습니다: ${fixedFile.absolutePath}")
            jsonContent = fixedFile.readText(Charsets.UTF_8)
        } else {
            val envPath = serviceAccountPath.replace("file:", "").filter { it.code in 32..126 }.trim()
            val envFile = File(envPath)
            if (envFile.exists()) {
                println("[FCM_DEBUG] ✅ 설정 경로에서 파일을 찾았습니다: ${envFile.absolutePath}")
                jsonContent = envFile.readText(Charsets.UTF_8)
            }
        }

        if (jsonContent == null) {
            println("[FCM_DEBUG] ❌ 파일을 찾지 못해 초기화를 중단합니다.")
            println("----------------------------------------------")
            return
        }

        try {
            var cleanedJson = jsonContent
            
            // 1. private_key 필드를 찾아 내부 청소 (헤더 공백 보존)
            val privateKeyRegex = "\"private_key\"\\s*:\\s*\"([^\"]+)\"".toRegex()
            val match = privateKeyRegex.find(cleanedJson)
            
            if (match != null) {
                val rawKeyValue = match.groupValues[1]
                
                // 실제 줄바꿈(\n), 캐리지 리턴(\r), 일반 공백을 모두 제거
                var cleanValue = rawKeyValue
                    .replace("\r", "")
                    .replace("\n", "")
                    .replace(" ", "")
                
                // [핵심] 제거된 헤더/푸터의 필수 공백은 다시 복구 (안 하면 Invalid PKCS#8 발생)
                cleanValue = cleanValue.replace("BEGINPRIVATEKEY", "BEGIN PRIVATE KEY")
                cleanValue = cleanValue.replace("ENDPRIVATEKEY", "END PRIVATE KEY")
                
                cleanedJson = cleanedJson.replace(rawKeyValue, cleanValue)
                println("[FCM_DEBUG] 🧹 private_key 내부 정제 및 헤더 복구 완료")
            }

            val options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(cleanedJson.byteInputStream()))
                .build()
            
            FirebaseApp.initializeApp(options)
            println("[FCM_DEBUG] 🚀 [최종 성공] Firebase 초기화가 완료되었습니다!")
        } catch (e: Exception) {
            println("[FCM_DEBUG] ❌ 초기화 실패: ${e.message}")
            e.printStackTrace()
        }
        println("----------------------------------------------")
    }
}