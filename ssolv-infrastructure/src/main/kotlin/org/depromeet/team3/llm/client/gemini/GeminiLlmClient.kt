package org.depromeet.team3.llm.client.gemini

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.depromeet.team3.llm.client.LlmClient
import org.depromeet.team3.llm.properties.GeminiProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.body

import kotlinx.coroutines.withTimeout
import org.depromeet.team3.common.util.RetryUtil

@Component
@ConditionalOnProperty(prefix = "api.gemini", name = ["api-key"])
class GeminiLlmClient(
    private val geminiProperties: GeminiProperties,
    private val geminiRestClient: RestClient
) : LlmClient {

    private val logger = KotlinLogging.logger {}
    private val apiTimeoutMillis = 15_000L // LLM은 응답이 길 수 있으므로 15초 설정

    override suspend fun chat(prompt: String): String? = withContext(Dispatchers.IO) {
        if (geminiProperties.apiKey.isBlank()) {
            logger.warn { "Gemini API Key가 설정되지 않았습니다." }
            return@withContext null
        }

        try {
            RetryUtil.retryWithExponentialBackoff(
                operation = "Gemini API 호출",
                logger = logger,
                operationDetail = "prompt length: ${prompt.length}"
            ) {
                withTimeout(apiTimeoutMillis) {
                    val request = GeminiRequest(
                        contents = listOf(
                            GeminiRequest.Content(
                                parts = listOf(GeminiRequest.Part(text = prompt))
                            )
                        )
                    )

                    val response = callGeminiApi(request)
                    response?.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Gemini API 최종 호출 실패: ${e.message}" }
            null
        }
    }

    override suspend fun chatWithJsonResponse(prompt: String): String? = withContext(Dispatchers.IO) {
        if (geminiProperties.apiKey.isBlank()) {
            logger.warn { "Gemini API Key가 설정되지 않았습니다." }
            return@withContext null
        }

        try {
            RetryUtil.retryWithExponentialBackoff(
                operation = "Gemini JSON 호출",
                logger = logger,
                operationDetail = "prompt length: ${prompt.length}"
            ) {
                withTimeout(apiTimeoutMillis) {
                    val request = GeminiRequest(
                        contents = listOf(
                            GeminiRequest.Content(
                                parts = listOf(GeminiRequest.Part(text = prompt))
                            )
                        ),
                        generationConfig = GeminiRequest.GenerationConfig(
                            responseMimeType = "application/json",
                            temperature = 0.1
                        )
                    )

                    val response = callGeminiApi(request)
                    response?.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Gemini JSON 최종 호출 실패: ${e.message}" }
            null
        }
    }

    private fun callGeminiApi(request: GeminiRequest): GeminiResponse? {
        return geminiRestClient.post()
            .uri { it.path("/v1beta/models/${geminiProperties.model}:generateContent")
                .queryParam("key", geminiProperties.apiKey)
                .build()
            }
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .body<GeminiResponse>()
    }
}