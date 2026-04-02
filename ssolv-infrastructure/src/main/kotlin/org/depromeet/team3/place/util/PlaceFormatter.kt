package org.depromeet.team3.place.util

/**
 * 장소 관련 포맷팅 및 단순 변환 담당
 */
object PlaceFormatter {
    
    /**
     * 네이버 플레이스 링크 생성
     */
    fun generateNaverPlaceLink(placeName: String): String {
        val encodedName = java.net.URLEncoder.encode(placeName, "UTF-8").replace("+", "%20")
        return "https://map.naver.com/p/search/$encodedName"
    }

    /**
     * 장소 이름에서 한국어 부분만 추출하고 특수문자 정리
     */
    fun extractKoreanName(fullName: String): String {
        val koreanPattern = Regex("[가-힣0-9\\s\\-]+")
        val matches = koreanPattern.findAll(fullName)
        
        val extracted = matches
            .map { it.value.trim() }
            .filter { it.isNotEmpty() }
            .firstOrNull()
            ?.trim()
            ?: fullName
        
        return extracted
            .replace(Regex("[()\\[\\]{},.;:!?\"'`~@#$%^&*+=/<>|\\\\]+$"), "")
            .replace(Regex("^[()\\[\\]{},.;:!?\"'`~@#$%^&*+=/<>|\\\\]+"), "")
            .trim()
    }
}
