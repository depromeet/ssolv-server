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
            // JSON을 문자열로 읽고, private_key 내부의 비표준 문자(\f 폼피드 등)를 제거합니다.
            // 원인: Firebase 서비스 계정 JSON의 private_key 값에 \f(Form Feed, ASCII 12) 등
            // 비표준 문자가 섞이면 PEM 블록이 깨져 Base64DecodingException이 발생합니다.
            val rawJson = inputStream.use { it.readBytes().toString(Charsets.UTF_8) }

            val privateKeyRegex = Regex("(\"private_key\"\\s*:\\s*\")(.*?)(\")", RegexOption.DOT_MATCHES_ALL)
            val cleanedJson = privateKeyRegex.replace(rawJson) { match ->
                val prefix = match.groupValues[1]
                val keyValue = match.groupValues[2]
                val suffix = match.groupValues[3]

                // \f(폼피드), \r(캐리지리턴), \t(탭) 및 기타 제어문자 제거
                // 허용: Base64 표준문자(A-Z,a-z,0-9,+,/,=), 이스케이프 문자(\n, \\), 공백/대시(헤더용)
                val cleanedKey = keyValue
                    .replace("\u000C", "") // \f Form Feed 제거 (핵심 원인)
                    .replace("\r", "")     // \r 제거
                    .replace("\t", "")     // \t 제거
                    .filter { it == '\n' || it == '\\' || it.code in 32..126 }

                "$prefix$cleanedKey$suffix"
            }

            println("[FCM_DEBUG] 🧹 private_key 비표준 문자 정제 완료 (\\f 등 제거)")

            val options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(cleanedJson.byteInputStream(Charsets.UTF_8)))
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