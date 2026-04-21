package org.depromeet.team3.place.application.plan

import org.depromeet.team3.place.application.model.PlaceSurveySummary
import org.depromeet.team3.place.application.plan.CreateSurveyKeywordService.KeywordCandidate
import org.depromeet.team3.place.application.plan.CreateSurveyKeywordService.KeywordType
import org.springframework.stereotype.Service
import java.util.LinkedHashMap
import kotlin.math.roundToInt

/**
 *  역 이름과 카테고리를 조합하여 "검색 키워드 문구" 생성
 *  응답 비율을 기반으로 가중치(weight) 부여
 *  최대 5개 키워드 반환
 *
 *  핵심 원칙:
 *  - BRANCH별로 최소 1개씩 키워드 보장 (카테고리 다양성)
 *  - 득표율에 비례하여 추가 키워드 분배 (공정성)
 *  - 10% 미만 득표는 제외 (너무 소수 의견)
 *
 *  예시:
 *  - 한식 20%, 양식 20%, 일식 20%, 중식 20%, 동남아 20%
 *    → 각 카테고리 1개씩 (총 5개)
 *  - 한식 60%, 양식 20%, 일식 20%
 *    → 한식 3개, 양식 1개, 일식 1개 (총 5개)
 *  - 한식 80%, 양식 15%, 일식 5%
 *    → 한식 4개, 양식 1개 (일식은 10% 미만이라 제외)
 */
@Service
class SelectSurveyKeywordsService {

    private val maxKeywordCount = 5
    private val minimalVoteThreshold = 0.1 // 10% 미만 득표는 제외
    private val strongLeafSupportThreshold = 0.2 // 강한 지지 임계값
    private val branchSupportThreshold = 0.15 // 지지 임계값
    private val minimalKeywordWeight = 0.1 // 최소 키워드 가중치

    fun selectKeywords(aggregate: PlaceSurveySummary): List<KeywordCandidate> {
        if (aggregate.totalRespondents == 0) {
            return emptyList()
        }

        val keywordMap = LinkedHashMap<String, KeywordCandidate>()
        val total = aggregate.totalRespondents.toDouble()

        fun addLeafCandidate(leaf: org.depromeet.team3.surveycategory.SurveyCategory, weight: Double) {
            val branch = leaf.parentId?.let { aggregate.branchCategories[it] }
            val branchKeyword = branch?.let { buildBranchKeyword(aggregate.stationName, it.name) }
            val branchMatchKeywords = branch?.let { buildMatchKeywords(it.name, KeywordType.BRANCH) } ?: emptySet()
            val keyword = buildLeafKeyword(aggregate.stationName, leaf.name)
            val normalized = CreateSurveyKeywordService.normalizeKeyword(keyword)
            if (normalized.isEmpty() || keywordMap.containsKey(normalized)) return
            keywordMap[normalized] = KeywordCandidate(
                keyword = normalized,
                weight = weight.coerceIn(0.0, 1.0),
                type = KeywordType.LEAF,
                categoryName = leaf.name,
                branchName = branch?.name,
                matchKeywords = buildMatchKeywords(leaf.name, KeywordType.LEAF),
                fallbackKeyword = branchKeyword,
                fallbackMatchKeywords = branchMatchKeywords,
            )
        }

        fun addBranchCandidate(branch: org.depromeet.team3.surveycategory.SurveyCategory, weight: Double) {
            val keyword = buildBranchKeyword(aggregate.stationName, branch.name)
            val normalized = CreateSurveyKeywordService.normalizeKeyword(keyword)
            if (normalized.isEmpty() || keywordMap.containsKey(normalized)) return
            keywordMap[normalized] = KeywordCandidate(
                keyword = normalized,
                weight = weight.coerceIn(0.0, 1.0),
                type = KeywordType.BRANCH,
                categoryName = branch.name,
                branchName = branch.name,
                matchKeywords = buildMatchKeywords(branch.name, KeywordType.BRANCH),
            )
        }

        aggregate.leafVotes.entries
            .filter { it.value == aggregate.totalRespondents }
            .sortedByDescending { aggregate.leafCategories[it.key]?.sortOrder ?: Int.MAX_VALUE }
            .forEach { entry ->
                val leaf = aggregate.leafCategories[entry.key] ?: return@forEach
                addLeafCandidate(leaf, 1.0)
            }

        aggregate.leafVotes.entries
            .filter { it.value < aggregate.totalRespondents }
            .sortedByDescending { it.value }
            .forEach { entry ->
                val leaf = aggregate.leafCategories[entry.key] ?: return@forEach
                val ratio = entry.value.toDouble() / total
                if (ratio >= strongLeafSupportThreshold && ratio >= minimalVoteThreshold) {
                    addLeafCandidate(leaf, ratio)
                }
            }

        aggregate.branchVotes.entries
            .sortedByDescending { it.value }
            .forEach { entry ->
                val branch = aggregate.branchCategories[entry.key] ?: return@forEach
                val ratio = entry.value.toDouble() / total
                if (ratio >= branchSupportThreshold && ratio >= minimalVoteThreshold) {
                    addBranchCandidate(branch, ratio)
                }
            }

        if (keywordMap.size < maxKeywordCount) {
            aggregate.leafVotes.entries
                .sortedByDescending { it.value }
                .forEach { entry ->
                    val leaf = aggregate.leafCategories[entry.key] ?: return@forEach
                    val ratio = entry.value.toDouble() / total
                    // 최소 득표율 이상인 경우만 추가
                    if (ratio >= minimalVoteThreshold) {
                        addLeafCandidate(leaf, ratio)
                    }
                    if (keywordMap.size >= maxKeywordCount) return@forEach
                }
        }

        var filteredKeywords = keywordMap.values
            .filter { it.weight >= minimalVoteThreshold || it.type == KeywordType.GENERAL }

        if (filteredKeywords.isEmpty()) {
            val keyword = buildGeneralKeyword(aggregate.stationName)
            val normalized = CreateSurveyKeywordService.normalizeKeyword(keyword)
            val candidate = KeywordCandidate(
                keyword = normalized,
                weight = minimalKeywordWeight,
                type = KeywordType.GENERAL,
                categoryName = null,
                branchName = null,
                matchKeywords = emptySet(),
            )
            filteredKeywords = listOf(candidate)
        }

        return filteredKeywords
            .sortedByDescending { it.weight }
            .take(maxKeywordCount)
    }

    /**
     * 득표율에 비례하여 키워드 슬롯을 분배
     *
     * 예시:
     * - ratios = [0.6, 0.2, 0.2], totalSlots = 5
     * - → [3, 1, 1]
     *
     * - ratios = [0.2, 0.2, 0.2, 0.2, 0.2], totalSlots = 5
     * - → [1, 1, 1, 1, 1]
     */
    private fun distributeSlotsProportionally(ratios: List<Double>, totalSlots: Int): List<Int> {
        if (ratios.isEmpty()) return emptyList()

        // 각 카테고리에 최소 1개씩 보장
        val minSlots = ratios.map { 1 }
        var remainingSlots = totalSlots - ratios.size

        if (remainingSlots <= 0) {
            return minSlots
        }

        // 남은 슬롯을 득표율에 비례하여 분배
        val slots = minSlots.toMutableList()
        val additionalSlots = ratios.map { (it * remainingSlots).roundToInt() }

        additionalSlots.forEachIndexed { index, additional ->
            slots[index] += additional
        }

        // 반올림 오차로 인한 슬롯 조정
        val totalAssigned = slots.sum()
        if (totalAssigned < totalSlots) {
            // 부족하면 득표율 높은 순서대로 추가
            val sortedIndices = ratios.indices.sortedByDescending { ratios[it] }
            for (i in 0 until (totalSlots - totalAssigned)) {
                slots[sortedIndices[i]]++
            }
        } else if (totalAssigned > totalSlots) {
            // 초과하면 득표율 낮은 순서대로 감소 (단, 최소 1개 유지)
            val sortedIndices = ratios.indices.sortedBy { ratios[it] }
            for (i in 0 until (totalAssigned - totalSlots)) {
                if (slots[sortedIndices[i]] > 1) {
                    slots[sortedIndices[i]]--
                }
            }
        }

        return slots
    }

    private fun buildLeafKeyword(stationName: String, leafName: String): String = "$stationName $leafName 맛집"

    private fun buildBranchKeyword(stationName: String, branchName: String): String = "$stationName $branchName 맛집"

    private fun buildGeneralKeyword(stationName: String): String = "$stationName 맛집"

    private fun buildMatchKeywords(categoryName: String, type: KeywordType): Set<String> {
        val baseTokens = categoryName
            .replace("·", " ")
            .replace("/", " ")
            .replace("·", " ")
            .split(" ")
            .map { it.trim() }
            .filter { it.length >= 2 }

        val synonyms = when (type) {
            KeywordType.LEAF -> leafSynonyms(categoryName)
            KeywordType.BRANCH -> branchSynonyms(categoryName)
            else -> emptyList()
        }

        return (baseTokens + synonyms)
            .flatMap { listOf(normalizeToken(it), normalizeToken(it).replace(" ", "")) }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun leafSynonyms(name: String): List<String> = when (name) {
        "초밥·사시미" -> listOf("초밥", "스시", "사시미", "sushi", "izakaya", "회")
        "베트남 음식" -> listOf("베트남", "Vietnam", "포", "pho", "쌀국수", "분짜", "banh", "banh mi")
        "태국 음식" -> listOf("태국", "타이", "Thai", "팟타이", "pad thai", "푸팟퐁커리")
        "파스타" -> listOf("이탈리안", "Italian", "파스타", "pasta", "spaghetti", "스테이크")
        "피자" -> listOf("피자", "pizza", "이탈리안")
        "면류" -> listOf(
            "중식", "중국", "중화", "짜장", "짜장면", "짬뽕", "마라", "마라탕", "마라샹궈",
            "noodle", "chinese", "china", "zhong",
        )
        "튀김·볶음류" -> listOf(
            "중식", "중국", "중화", "탕수육", "깐풍", "볶음", "튀김", "마라", "꿔바로우",
            "chinese", "china",
        )
        "카페" -> listOf("카페", "커피", "cafe", "coffee", "디저트", "베이커리", "bakery")
        "디저트" -> listOf("디저트", "dessert", "케이크", "빵", "베이커리", "bakery", "cafe", "카페")
        "술집" -> listOf("술집", "호프", "pub", "bar", "맥주", "와인", "펍", "포차", "이자카야", "beer")
        "이자카야" -> listOf("이자카야", "izakaya", "선술집", "술집")
        "삼겹살·고기구이" -> listOf("삼겹살", "고기", "갈비", "한우", "bbq", "korean bbq", "구이")
        "패스트푸드" -> listOf("버거", "햄버거", "burger", "샌드위치", "sandwich", "fast food")
        "스테이크" -> listOf("스테이크", "steak", "그릴", "grill", "레스토랑")
        "전통주·막걸리" -> listOf("막걸리", "전통주", "민속주", "포차", "주막", "파전")
        else -> emptyList()
    }

    private fun branchSynonyms(name: String): List<String> = when (name) {
        "양식" -> listOf("양식", "서양", "이탈리안", "파스타", "스테이크", "italian", "western", "pasta", "steak")
        "일식" -> listOf("일식", "스시", "초밥", "사시미", "라멘", "카츠", "japanese", "sushi", "izakaya")
        "중식" -> listOf(
            "중식", "중국", "중화", "짜장", "짬뽕", "탕수육", "깐풍", "마라",
            "마라탕", "chinese", "china", "sichuan",
        )
        "동남아 음식" -> listOf("동남아", "베트남", "태국", "타이", "아시안", "asian", "Vietnam", "Thai", "south east asian")
        "한식" -> listOf("한식", "한국", "korean", "k-food")
        "카페·디저트" -> listOf("카페", "디저트", "커피", "cafe", "dessert", "coffee", "bakery")
        "술집" -> listOf("술집", "호프", "포차", "맥주", "와인", "펍", "pub", "bar", "beer", "izakaya")
        else -> emptyList()
    }

    private fun normalizeToken(token: String): String = token.lowercase().trim()
}
