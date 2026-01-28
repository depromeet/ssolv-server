package org.depromeet.team3.llm.client

/**
 * 기반 모델이 바뀌더라도 상위 로직이 변하지 않도록 추상화된 LLM 클라이언트 인터페이스
 */
interface LlmClient {
    /**
     * 프롬프트를 전달하여 텍스트 응답을 받음
     */
    suspend fun chat(prompt: String): String?

    /**
     * 구조화된 JSON 응답이 필요할 때 사용
     */
    suspend fun chatWithJsonResponse(prompt: String): String?
}