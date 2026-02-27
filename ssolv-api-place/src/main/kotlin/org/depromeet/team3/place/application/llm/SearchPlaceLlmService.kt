package org.depromeet.team3.place.application.llm

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.depromeet.team3.llm.client.LlmClient
import org.springframework.beans.factory.annotation.Autowired
import org.depromeet.team3.place.application.llm.model.PlaceLlmFilterResult
import org.depromeet.team3.place.application.llm.model.PlaceLlmAnalysisResult
import org.depromeet.team3.place.application.llm.prompt.SearchPlaceLlmPromptService
import org.depromeet.team3.place.model.PlaceDetailsResponse
import org.depromeet.team3.place.model.PlacesTextSearchResponse
import org.springframework.stereotype.Service

/**
 * 장소 도메인과 관련된 LLM 분석 비즈니스 로직을 수행하는 서비스
 */
@Service
class SearchPlaceLlmService @Autowired constructor(
    @Autowired(required = false) private val llmClient: LlmClient?,
    private val promptService: SearchPlaceLlmPromptService,
    private val objectMapper: ObjectMapper
) {
    private val logger = KotlinLogging.logger {}

    /**
     * 특정 장소의 Google 상세 정보를 바탕으로 LLM에게 특징 요약 및 주변 랜드마크 분석을 요청한다.
     */
    suspend fun getPlaceLlmInfo(place: PlaceDetailsResponse): PlaceLlmAnalysisResult {
        val prompt = promptService.createPlaceInsightPrompt(place)
        val response = llmClient?.chatWithJsonResponse(prompt)

        return try {
            response?.let { 
                objectMapper.readValue(it, PlaceLlmAnalysisResult::class.java)
            } ?: PlaceLlmAnalysisResult()
        } catch (e: Exception) {
            logger.warn(e) { "LLM 응답 파싱 실패 (getPlaceLlmInfo): ${e.message}" }
            PlaceLlmAnalysisResult()
        }
    }

    /**
     * 대량의 후보 장소군 내에서 사용자 모임 기준에 부합하는 장소들을 선별하고 선정 사유를 생성한다.
     */
    suspend fun filterCandidateByBasicInfo(
        places: List<PlacesTextSearchResponse.Place>, 
        criteria: String
    ): List<PlaceLlmFilterResult> {
        if (places.isEmpty()) return emptyList()

        val candidatesString = places.joinToString("\n") { 
            "id: ${it.id}, name: ${it.displayName?.text}, types: ${it.types?.joinToString(", ")}, rating: ${it.rating}" 
        }
        val prompt = promptService.createFilterPrompt(candidatesString, criteria)
        val response = llmClient?.chatWithJsonResponse(prompt)

        return try {
            response?.let {
                objectMapper.readValue(it, objectMapper.typeFactory.constructCollectionType(List::class.java, PlaceLlmFilterResult::class.java))
            } ?: emptyList()
        } catch (e: Exception) {
            logger.warn(e) { "LLM 응답 파싱 실패 (FilterByBasic): ${e.message}" }
            emptyList()
        }
    }

    /**
     * 여러 장소의 정보를 바탕으로 특징 요약 및 주변 랜드마크 분석을 일괄 요청한다.
     */
    suspend fun getBulkPlaceLlmInfo(places: List<PlaceDetailsResponse>): List<PlaceLlmAnalysisResult> {
        if (places.isEmpty()) return emptyList()

        val prompt = promptService.createBulkPlaceInsightPrompt(places)
        val response = llmClient?.chatWithJsonResponse(prompt)

        return try {
            response?.let {
                objectMapper.readValue(it, objectMapper.typeFactory.constructCollectionType(List::class.java, PlaceLlmAnalysisResult::class.java))
            } ?: emptyList()
        } catch (e: Exception) {
            logger.warn(e) { "LLM 응답 파싱 실패 (getBulkPlaceLlmInfo): ${e.message}" }
            emptyList()
        }
    }
}