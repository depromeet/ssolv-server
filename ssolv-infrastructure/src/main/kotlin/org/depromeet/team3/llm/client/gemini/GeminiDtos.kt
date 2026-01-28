package org.depromeet.team3.llm.client.gemini


/**
 * Gemini API Request DTO
 */
data class GeminiRequest(
    val contents: List<Content>,
    val generationConfig: GenerationConfig? = null
) {
    data class Content(
        val parts: List<Part>
    )
    
    data class Part(
        val text: String
    )
    
    data class GenerationConfig(
        val responseMimeType: String? = null,    // "application/json" for JSON mode
        val temperature: Double? = null,
        val maxOutputTokens: Int? = null
    )
}

/**
 * Gemini API Response DTO
 */
data class GeminiResponse(
    val candidates: List<Candidate>
) {
    data class Candidate(
        val content: Content
    )
    
    data class Content(
        val parts: List<Part>
    )
    
    data class Part(
        val text: String
    )
}