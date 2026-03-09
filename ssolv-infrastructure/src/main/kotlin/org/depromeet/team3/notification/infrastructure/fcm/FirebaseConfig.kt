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

        val fixedPath = "/home/ubuntu/17th-team3-Server/firebase-service-account.json"
        val fixedFile = File(fixedPath)
        
        val cleanPath = serviceAccountPath.replace("file:", "").replace("classpath:", "").trim()
        val envFile = File(cleanPath)

        val inputStream: InputStream? = when {
            fixedFile.exists() && fixedFile.isFile -> fixedFile.inputStream()
            envFile.exists() && envFile.isFile -> envFile.inputStream()
            else -> javaClass.classLoader.getResourceAsStream(serviceAccountPath.replace("classpath:", ""))
        }

        if (inputStream == null) {
            println("[Firebase] ❌ 초기화 실패: 설정 파일을 찾을 수 없습니다.")
            return
        }

        try {
            val rawJson = inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val privateKeyRegex = Regex("(\"private_key\"\\s*:\\s*\")(.*?)(\")", RegexOption.DOT_MATCHES_ALL)
            
            val cleanedJson = privateKeyRegex.replace(rawJson) { match ->
                val prefix = match.groupValues[1]
                val keyValue = match.groupValues[2]
                val suffix = match.groupValues[3]

                val cleanedKey = keyValue
                    .replace("\u000C", "")
                    .replace("\r", "")
                    .replace("\t", "")
                    .filter { it == '\n' || it == '\\' || it.code in 32..126 }

                "$prefix$cleanedKey$suffix"
            }

            val options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(cleanedJson.byteInputStream(Charsets.UTF_8)))
                .build()

            FirebaseApp.initializeApp(options)
            println("[Firebase] 🚀 초기화 완료")
        } catch (e: Exception) {
            println("[Firebase] ❌ 초기화 실패: ${e.message}")
        }
    }
}