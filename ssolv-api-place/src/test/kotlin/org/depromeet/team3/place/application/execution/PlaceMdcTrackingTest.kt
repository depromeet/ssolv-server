package org.depromeet.team3.place.application.execution

import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.slf4j.MDCContext
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.UUID

class PlaceMdcTrackingTest {

    private val logger = LoggerFactory.getLogger(PlaceMdcTrackingTest::class.java)
    private val requestIdKey = "request_id"
    private val userIdKey = "user_id"

    companion object {
        @BeforeAll
        @JvmStatic
        fun disableCoroutineDebug() {
            // 운영 환경처럼 @coroutine#1 접미사가 붙지 않게 설정
            System.setProperty("kotlinx.coroutines.debug", "off")
        }
    }

    @Test
    fun `장소_검색_로직_MDCContext_누락_시_추적_실패_로그_검증`() = runBlocking {
        // 운영 스레드 유지를 위해 로컬 변수로 스레드명 관리
        val mainThreadName = "http-nio-8080-exec-1"
        Thread.currentThread().name = mainThreadName

        val requestId = UUID.randomUUID().toString().substring(0, 8)
        val userId = (1..500).random().toString()
        MDC.put(requestIdKey, requestId)
        MDC.put(userIdKey, userId)

        logger.info(">>> [Controller] GET /api/v1/places/search?meetingId=12345")

        // ❌ MDCContext() 누락 상황
        withContext(Dispatchers.IO) {
            // 비동기 스레드에서 유실됨
            logger.info("   [Service] ExecutePlaceSearchService.search() 실행")

            val keywords = listOf("강남 맛집", "강남 카페")
            val deferreds = keywords.map { kw ->
                async {
                    logger.info("      [GoogleAPI] '$kw' 검색 API 호출 중...")
                    delay(10)
                    "Result"
                }
            }
            deferreds.awaitAll()

            logger.info("   [Service] 검색 결과 가공 및 랭킹 산정 완료")
        }

        // 재개된 스레드 이름을 복원하여 운영 로그의 연속성 유지
        Thread.currentThread().name = mainThreadName
        logger.info("<<< [Controller] 200 OK - 장소 검색 응답 완료")
        MDC.clear()
    }

    @Test
    fun `장소_검색_로직_MDCContext_정상_적용_시_추적_성공_로그_검증`() = runBlocking {
        val mainThreadName = "http-nio-8080-exec-1"
        Thread.currentThread().name = mainThreadName

        val requestId = UUID.randomUUID().toString().substring(0, 8)
        val userId = (1..500).random().toString()
        MDC.put(requestIdKey, requestId)
        MDC.put(userIdKey, userId)

        logger.info(">>> [Controller] GET /api/v1/places/search?meetingId=55555")

        // ✅ MDCContext() 정상 적용 상황
        withContext(MDCContext() + Dispatchers.IO) {
            logger.info("   [Service] ExecutePlaceSearchService.search() 실행")

            val keywords = listOf("홍대 맛집", "홍대 술집")
            val parentContext = currentCoroutineContext()
            val deferreds = keywords.map { kw ->
                async(parentContext) {
                    logger.info("      [GoogleAPI] '$kw' 검색 API 호출 중...")
                    delay(10)
                    "Result"
                }
            }
            deferreds.awaitAll()

            logger.info("   [Service] 검색 결과 가공 및 랭킹 산정 완료")
        }

        // 재개된 스레드 이들을 복원하여 운영 로그의 연속성 유지
        Thread.currentThread().name = mainThreadName
        logger.info("<<< [Controller] 200 OK - 장소 검색 응답 완료")
        MDC.clear()
    }
}
