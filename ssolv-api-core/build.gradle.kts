plugins {
    id("com.google.cloud.tools.jib")
}

dependencies {
    implementation(project(":ssolv-api-common"))
    implementation(project(":ssolv-api-place"))
    implementation(project(":ssolv-batch"))
    implementation(project(":ssolv-global-utils"))
    testImplementation(testFixtures(project(":ssolv-api-common")))

    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.springframework:spring-jdbc:6.2.3")
    implementation("org.springframework.boot:spring-boot-starter-webflux")

    // Caffeine Cache
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")
    
    // Redis
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    val jjwtVersion = rootProject.extra["jjwtVersion"]
    implementation("io.jsonwebtoken:jjwt-api:$jjwtVersion")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:$jjwtVersion")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:$jjwtVersion")

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
        enabled = true
    }
    bootJar {
        enabled = true
    }
}

jib {
    from {
        image = "eclipse-temurin:21-jre"
    }
    to {
        image = "registry.ssolv.site/api-server"
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
