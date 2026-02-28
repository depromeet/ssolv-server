dependencies {
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.5")
    // Spring Boot가 관리하는 버전 사용
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("jakarta.annotation:jakarta.annotation-api")
    implementation("org.springframework:spring-context")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
}

tasks {
    jar {
        enabled = true
    }
    bootJar {
        enabled = false
    }
}
