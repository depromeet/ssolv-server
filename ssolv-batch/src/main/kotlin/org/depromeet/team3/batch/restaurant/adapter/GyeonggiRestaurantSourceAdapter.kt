package org.depromeet.team3.batch.restaurant.adapter

import org.depromeet.team3.batch.restaurant.support.RestaurantTextNormalizer
import org.depromeet.team3.restaurant.RestaurantSourceType
import org.springframework.stereotype.Component

@Component
class GyeonggiRestaurantSourceAdapter(normalizer: RestaurantTextNormalizer) : AbstractRestaurantSourceAdapter(normalizer) {
    override val sourceType = RestaurantSourceType.GYEONGGI
    override val sourceKeyColumns = listOf("관리번호", "MGTNO", "인허가번호", "LICENSG_NO")
    override val nameColumns = listOf("사업장명", "BIZPLC_NM", "상호명")
    override val roadAddressColumns = listOf("소재지도로명주소", "REFINE_ROADNM_ADDR", "도로명주소")
    override val lotAddressColumns = listOf("소재지지번주소", "REFINE_LOTNO_ADDR", "지번주소", "소재지주소")
    override val latitudeColumns = listOf("위도", "REFINE_WGS84_LAT", "WGS84_LAT")
    override val longitudeColumns = listOf("경도", "REFINE_WGS84_LOGT", "WGS84_LOGT", "REFINE_WGS84_LNG")
    override val categoryColumns = listOf("업태구분명", "업태명", "SANTYPE_NM")
    override val regionCodeColumns = listOf("시군코드", "SIGUN_CD", "행정동코드")
    override val phoneColumns = listOf("소재지전화", "전화번호", "LOCPLC_FACLT_TELNO")
    override val statusColumns = listOf("영업상태명", "BSN_STATE_NM", "상세영업상태명")
}
