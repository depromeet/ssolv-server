package org.depromeet.team3.place.application.llm

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import org.depromeet.team3.llm.properties.GeminiProperties
import org.depromeet.team3.place.PlaceEntity
import org.depromeet.team3.place.PlaceQuery
import org.depromeet.team3.place.application.llm.model.PlaceLlmFilterResult
import org.depromeet.team3.place.application.llm.model.PlaceLlmAnalysisResult
import org.depromeet.team3.place.dto.response.PlacesSearchResponse
import org.depromeet.team3.place.model.PlacesTextSearchResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 전처리된 장소 후보들에 대해 LLM(Gemini) 분석을 수행하여 결과를 가공하는 서비스
 */
@Service
class ProcessPlaceLlmService(
    private val searchPlaceLlmService: SearchPlaceLlmService,
    private val placeQuery: PlaceQuery,
    private val geminiProperties: GeminiProperties
) {
    private val logger = LoggerFactory.getLogger(ProcessPlaceLlmService::class.java)

    /**
     * 모임 성격(모임명 등)에 가장 잘 부합하는 장소들을 선별하고 추천 사유를 생성한다.
     */
     suspend fun filterByCriteria(
        places: List<PlacesTextSearchResponse.Place>,
        criteria: String
    ): Map<String, PlaceLlmFilterResult> {
        if (geminiProperties.apiKey.isBlank()) return emptyMap()

        return try {
            val results = searchPlaceLlmService.filterCandidateByBasicInfo(places, criteria)
            
            // DB에 추천 사유 일괄 업데이트
            val updateDataMap = results.associate { it.id to PlaceQuery.LlmUpdateData(reason = it.reason) }
            placeQuery.bulkUpdateLlmData(updateDataMap)
            
            results.associateBy { it.id }
        } catch (e: Exception) {
            logger.warn("LLM 필터링 실패: criteria={}, error={}", criteria, e.message)
            emptyMap()
        }
    }

    /**
     * 최종 선정된 아이템들에 대해 주변 랜드마크 분석 및 분석 결과를 응답 모델에 입힌다.
     * 20개 후보 중 최종 선정된 7개에 대해 한 번의 LLM 호출로 상세 분석을 수행한다.
     */
    suspend fun applyLlmDetails(
        items: List<PlacesSearchResponse.PlaceItem>,
        entities: List<PlaceEntity>,
        filteringResults: Map<String, PlaceLlmFilterResult>
    ): List<PlacesSearchResponse.PlaceItem> = supervisorScope {
        if (geminiProperties.apiKey.isBlank()) return@supervisorScope items

        val entityMap = entities.associateBy { it.id }
        
        // 1. 캐시 조회 및 LLM 호출 대상 선별
        val enrichedItemsMap = mutableMapOf<Long, PlacesSearchResponse.PlaceItem>()
        val placesToCallLlm = mutableListOf<PlaceEntity>()

        items.forEach { item ->
            val entity = entityMap[item.placeId]
            if (entity != null && !entity.llmSummary.isNullOrBlank() && !entity.addressDescriptor.isNullOrBlank()) {
                // 이미 LLM 데이터가 있는 경우 캐시 활용
                enrichedItemsMap[item.placeId] = item.copy(
                    addressDescriptor = entity.addressDescriptor?.let {
                        PlacesSearchResponse.PlaceItem.AddressDescriptor(description = it)
                    },
                    topReview = (filteringResults[entity.googlePlaceId]?.reason ?: entity.llmReason)?.let { reason ->
                        PlacesSearchResponse.PlaceItem.Review(rating = (item.rating ?: 0.0).toInt(), text = reason)
                    } ?: item.topReview
                )
            } else if (entity?.googlePlaceId != null) {
                // LLM 호출이 필요한 대상
                placesToCallLlm.add(entity)
            } else {
                enrichedItemsMap[item.placeId] = item
            }
        }

        // 2. 캐시 없는 것들에 대해 일괄 LLM 호출
        if (placesToCallLlm.isNotEmpty()) {
            try {
                // 상세 정보 병렬 조회 (랜드마크 분석에 필요한 상세 데이터 확보)
                val detailsList = placesToCallLlm.map { entity ->
                    async { placeQuery.getPlaceDetails(entity.googlePlaceId!!) }
                }.awaitAll().filterNotNull()

                // LLM 일괄 분석 요청 (최대 7개)
                val llmResults = searchPlaceLlmService.getBulkPlaceLlmInfo(detailsList)
                val resultMap = llmResults.associateBy { it.id }

                // DB 일괄 업데이트
                val updateDataMap = llmResults.filter { it.id != null }.associate { 
                    it.id!! to PlaceQuery.LlmUpdateData(summary = it.summary, landmarks = it.landmarks?.joinToString(", "))
                }
                placeQuery.bulkUpdateLlmData(updateDataMap)

                // 분석 결과를 아이템에 반영
                items.forEach { item ->
                    if (enrichedItemsMap.containsKey(item.placeId)) return@forEach
                    val entity = entityMap[item.placeId] ?: return@forEach
                    val llmResult = resultMap[entity.googlePlaceId]

                    enrichedItemsMap[item.placeId] = item.copy(
                        addressDescriptor = llmResult?.landmarks?.joinToString(", ")?.let {
                            PlacesSearchResponse.PlaceItem.AddressDescriptor(description = it)
                        } ?: item.addressDescriptor,
                        topReview = filteringResults[entity.googlePlaceId]?.reason?.let { reason ->
                            PlacesSearchResponse.PlaceItem.Review(rating = (item.rating ?: 0.0).toInt(), text = reason)
                        } ?: item.topReview
                    )
                }
            } catch (e: Exception) {
                logger.warn("장소 LLM 일괄 상세 정보 적용 실패: {}", e.message)
                // 실패 시 원본 아이템 유지
                items.forEach { item ->
                    if (!enrichedItemsMap.containsKey(item.placeId)) enrichedItemsMap[item.placeId] = item
                }
            }
        }

        // 원래 순서대로 반환
        items.map { enrichedItemsMap[it.placeId] ?: it }
    }
}