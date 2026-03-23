package org.depromeet.team3.place.config

import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.sync.Semaphore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.depromeet.team3.benchmark.BenchmarkApiOverride


/**
 * 장소 서비스에서 사용하는 공통 자원 및 모니터링 메트릭 설정
 */

@Configuration
class PlaceMetricsConfig(private val meterRegistry: MeterRegistry) {

    /**
     * [전역 Semaphore] 서버 전체의 Google Places API 동시 호출 수 상한 제어
     *
     * Google Places API Rate Limit: 20~50 QPS (초당 최대 호출 수)
     * 적정 동시 호출 수 = QPS 하한(20) × 평균 응답시간(0.6s) ≈ 12
     * → 여유분 포함하여 15로 설정
     *
     * 주의: 이 Semaphore는 "동시 호출 수"를 제한하는 것이며, 실제 QPS는 15 ÷ 평균응답시간(초)으로 결정됩니다.
     *       평균 응답시간이 0.6s로 유지될 때 약 25 QPS이며, 응답시간이 단축될 경우(예: 100ms → 150 QPS) 
     *       구글 Rate Limit을 초과할 수 있습니다. 성능 개선 시 이 값의 재검토가 필요합니다.
     *
     * 역할: 모임방이 다수 동시에 처리되는 상황에서도 서버 전체에서 동시에
     *       실행되는 Google API 호출 수가 15개를 넘지 않도록 서버 레벨에서 보호
     */
    @Bean
    fun globalApiSemaphore(): Semaphore {
        // [벤치마크 테스트용] 원본(15)에서 500으로 임시 상향
        val semaphore = Semaphore(BenchmarkApiOverride.globalApiSemaphoreSize)
        
        // 전역 세마포어 가용량 모니터링 메트릭 등록 (Prometheus/Grafana용)
        meterRegistry.gauge("google.api.semaphore.available", semaphore) {
            it.availablePermits.toDouble()
        }
        
        return semaphore
    }
}
