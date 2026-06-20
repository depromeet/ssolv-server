plugins {
    id("com.google.cloud.tools.jib")
}

dependencies {
    implementation(project(":ssolv-api-common"))
    implementation(project(":ssolv-domain"))
    implementation(project(":ssolv-infrastructure"))
    implementation(project(":ssolv-global-utils"))

    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-batch")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.apache.commons:commons-csv:1.11.0")
    implementation("software.amazon.awssdk:s3:2.25.70")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-test-autoconfigure")
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation("io.micrometer:micrometer-registry-prometheus")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation(testFixtures(project(":ssolv-api-common")))
    runtimeOnly("com.mysql:mysql-connector-j")
}

jib {
    from {
        image = "eclipse-temurin:21-jre"
    }
    to {
        image = "registry.ssolv.site/batch-worker"
        tags = setOf("latest", "${project.version}")
    }
    container {
        mainClass = "org.depromeet.team3.BatchApplicationKt"
        jvmFlags = listOf(
            "-Duser.timezone=Asia/Seoul",
            "-XX:MaxRAMPercentage=75.0",
        )
        creationTime = "USE_CURRENT_TIMESTAMP"
    }
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
        enabled = true
    }
}
