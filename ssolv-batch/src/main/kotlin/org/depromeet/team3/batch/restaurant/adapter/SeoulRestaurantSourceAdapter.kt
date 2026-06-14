package org.depromeet.team3.batch.restaurant.adapter

import org.depromeet.team3.batch.restaurant.support.RestaurantTextNormalizer
import org.depromeet.team3.restaurant.RestaurantSourceType
import org.springframework.stereotype.Component

@Component
class SeoulRestaurantSourceAdapter(normalizer: RestaurantTextNormalizer) : AbstractRestaurantSourceAdapter(normalizer) {
    override val sourceType = RestaurantSourceType.SEOUL
    override val sourceKeyColumns = listOf("관리번호", "MGTNO", "인허가번호")
    override val nameColumns = listOf("사업장명", "상호명", "BPLCNM", "BIZPLC_NM")
    override val roadAddressColumns = listOf("도로명주소", "RDNWHLADDR", "소재지도로명주소")
    override val lotAddressColumns = listOf("지번주소", "SITEWHLADDR", "소재지주소", "소재지지번주소")
    override val latitudeColumns = listOf("위도", "Y", "LAT", "WGS84_LAT")
    override val longitudeColumns = listOf("경도", "X", "LNG", "WGS84_LOGT")
    override val categoryColumns = listOf("업태구분명", "UPTAENM", "업태명")
    override val regionCodeColumns = listOf("자치구코드", "CGG_CODE", "행정동코드")
    override val phoneColumns = listOf("전화번호", "SITETEL", "소재지전화")
    override val statusColumns = listOf("영업상태명", "DTLSTATENM", "상세영업상태명")
}
