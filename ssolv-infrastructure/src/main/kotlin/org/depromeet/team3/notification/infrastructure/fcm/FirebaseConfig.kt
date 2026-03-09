package org.depromeet.team3.notification.infrastructure.fcm

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
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
        println("[FCM_DEBUG] Firebase 초기화 프로세스 시작 (Resilient Cleaning Mode)")

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
            // [핵심] JSON 내용 정제: private_key 내부에 섞인 실제 줄바꿈이나 공백을 강제로 제거
            // FCM 키의 private_key 값 안에는 \n(문자)은 있어야 하지만, 실제 Enter나 공백은 없어야 합니다.
            var cleanedJson = jsonContent
            
            // 1. private_key 필드를 찾아서 그 안의 실제 개행 문자(\r, \n)와 공백을 제거
            val privateKeyRegex = "\"private_key\"\\s*:\\s*\"([^\"]+)\"".toRegex()
            val match = privateKeyRegex.find(cleanedJson)
            
            if (match != null) {
                val rawKeyValue = match.groupValues[1]
                // 실제 줄바꿈과 공백을 싹 제거 (단, \\n 문자열은 유지해야 하므로 조심스럽게 처리)
                val cleanKeyValue = rawKeyValue
                    .replace("\r", "")
                    .replace("\n", "")
                    .replace(" ", "")
                
                cleanedJson = cleanedJson.replace(rawKeyValue, cleanKeyValue)
                println("[FCM_DEBUG] 🧹 private_key 필드의 지저분한 문자를 청소했습니다.")
            }

            val options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(cleanedJson.byteInputStream()))
                .build()
            
            FirebaseApp.initializeApp(options)
            println("[FCM_DEBUG] 🚀 [최종 성공] Firebase 초기화가 완료되었습니다!")
        } catch (e: Exception) {
            println("[FCM_DEBUG] ❌ 초기화 실패: ${e.message}")
            if (e.message?.contains("DecodingException") == true) {
                println("[FCM_DEBUG] 💡 팁: private_key 내부의 Base64 형식이 여전히 잘못되었습니다.")
            }
            e.printStackTrace()
        }
        println("----------------------------------------------")
    }
}