package org.depromeet.team3
 
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * 전용 배치 및 백그라운드 스케줄링을 담당하는 어플리케이션
 */
@EnableScheduling
@SpringBootApplication(
    scanBasePackages = ["org.depromeet.team3"]
)
class BatchApplication

fun main(args: Array<String>) {
    runApplication<BatchApplication>(*args)
}