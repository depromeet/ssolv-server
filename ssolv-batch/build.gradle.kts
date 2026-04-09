dependencies {
    implementation(project(":ssolv-api-common"))
    implementation(project(":ssolv-domain"))
    implementation(project(":ssolv-infrastructure"))
    implementation(project(":ssolv-global-utils"))

    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-test-autoconfigure")
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation("io.micrometer:micrometer-registry-prometheus")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation(project(":ssolv-api-core"))
    testImplementation(testFixtures(project(":ssolv-api-common")))
    runtimeOnly("com.mysql:mysql-connector-j")
}

tasks {
    jar {
        enabled = true
        archiveClassifier.set("plain")
    }
    test {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed", "standardOut", "standardError")
            showStackTraces = true
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
    bootJar {
        enabled = false
    }
}
