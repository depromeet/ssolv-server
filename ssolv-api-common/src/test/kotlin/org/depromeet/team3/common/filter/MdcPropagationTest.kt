package org.depromeet.team3.common.filter

import kotlinx.coroutines.*
import kotlinx.coroutines.slf4j.MDCContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.concurrent.Executors

class MdcPropagationTest {

    private val logger = LoggerFactory.getLogger(MdcPropagationTest::class.java)
    private val requestIdKey = "request_id"

    @Test
    fun `Java_Thread_Tracking_Failure_Scenario`() {
        val requestId = "TRK-999-JAVA"
        MDC.put(requestIdKey, requestId)
        
        logger.info(">>> API ENTRY - Service started")
        logger.info("Processing business logic step 1")

        val thread = Thread {
            logger.info("   [External Thread] Processing sub-task...") // This should show [request_id=]
            logger.info("   [External Thread] Fetching additional data...")
        }
        thread.start()
        thread.join()

        logger.info("Processing business logic step 2")
        logger.info("<<< API EXIT - Service finished")

        MDC.clear()
    }

    @Test
    fun `Coroutine_Tracking_Failure_Scenario`() = runBlocking {
        val requestId = "TRK-111-CORO-FAIL"
        MDC.put(requestIdKey, requestId)
        
        logger.info(">>> API ENTRY - Coroutine service started")
        logger.info("Parent coroutine - Main task running")

        // MDCContext() 가 빠진 비동기 호출
        withContext(Dispatchers.Default) {
            logger.info("   [Child Coroutine] Processing async task...") // Failure!
            logger.info("   [Child Coroutine] Database interaction...")
        }

        logger.info("Parent coroutine - Main task resumes")
        logger.info("<<< API EXIT - Coroutine service finished")

        MDC.clear()
    }

    @Test
    fun `Coroutine_Tracking_Success_Scenario`() = runBlocking {
        val requestId = "TRK-222-CORO-SUCCESS"
        MDC.put(requestIdKey, requestId)
        
        logger.info(">>> API ENTRY - Coroutine service started")
        logger.info("Parent coroutine - Main task running")

        // MDCContext() 를 명시적으로 전달하여 전파
        withContext(Dispatchers.Default + MDCContext()) {
            logger.info("   [Child Coroutine] Processing async task...") // success!
            logger.info("   [Child Coroutine] Database interaction...")
        }

        logger.info("Parent coroutine - Main task resumes")
        logger.info("<<< API EXIT - Coroutine service finished")

        MDC.clear()
    }

    @Test
    fun `Thread_Pool_Pollution_Scenario`() {
        val executor = Executors.newFixedThreadPool(1)
        
        // 1번 요청 (정상)
        val requestId1 = "REQ-101-CLEAN"
        executor.submit {
            MDC.put(requestIdKey, requestId1)
            logger.info(">>> API ENTRY - Request 1 starts")
            logger.info("   Processing Request 1...")
            // MDC.clear() 호출 누락 상황 가정
            // logger.info("<<< API EXIT - Request 1 finished (MDC NOT CLEARED!)")
        }.get()

        // 2번 요청 (MDC를 주입하지 않았음에도 1번의 ID가 남은 경우)
        executor.submit {
            // Filter에서 주입을 실패했거나, 아예 주입 로직이 빠진 경우
            logger.info("   [New Task] Request 2 starts by reusing thread...")
            logger.info("   [New Task] Checking trace ID... (Expect pollution?)") 
            logger.info("<<< API EXIT - Request 2 finishes")
        }.get()

        executor.shutdown()
        MDC.clear()
    }
}
