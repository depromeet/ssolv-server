package org.depromeet.team3

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@EnableScheduling
@SpringBootApplication(
    scanBasePackages = [
        "org.depromeet.team3",
    ],
)
class PlaceApiApplication

fun main(args: Array<String>) {
    runApplication<PlaceApiApplication>(*args)
}
