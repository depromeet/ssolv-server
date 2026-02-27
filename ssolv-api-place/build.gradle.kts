plugins {
    id("com.google.cloud.tools.jib")
}

dependencies {
    implementation(project(":ssolv-api-common"))
    implementation(project(":ssolv-batch"))
    testImplementation(testFixtures(project(":ssolv-api-common")))

    implementation("org.springframework:spring-jdbc:6.2.3")

    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.3")

    testImplementation("org.mockito:mockito-core:5.1.1")
    testImplementation("org.mockito:mockito-junit-jupiter:5.1.1")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:1.8.20")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    runtimeOnly("com.mysql:mysql-connector-j")
    
    // 테스트용 H2 데이터베이스
    testRuntimeOnly("com.h2database:h2")
    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
    }
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("io.mockk:mockk:1.13.9")
    testImplementation("com.ninja-squad:springmockk:4.0.2")
}

tasks {
    jar {
        enabled = false
    }
    bootJar {
        enabled = true
    }
}

jib {
    from {
        image = "eclipse-temurin:17-jre"
    }
    to {
        image = "registry.ssolv.site/place-server"
        tags = setOf("latest", "${project.version}")
    }
    container {
        jvmFlags = listOf(
            "-Duser.timezone=Asia/Seoul",
            "-XX:MaxRAMPercentage=75.0"
        )
        ports = listOf("8080")
        creationTime = "USE_CURRENT_TIMESTAMP"
    }
}