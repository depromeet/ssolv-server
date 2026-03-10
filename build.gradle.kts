plugins {
    id("org.springframework.boot") version "3.4.9" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("com.google.cloud.tools.jib") version "3.4.4" apply false
    kotlin("jvm") version "1.9.25" apply false
    kotlin("plugin.spring") version "1.9.25" apply false
    kotlin("plugin.jpa") version "1.9.25" apply false
    kotlin("kapt") version "1.9.25" apply false
    id("org.sonarqube") version "5.1.0.4882"
}

// 중복 의존성 관리 및 보안 버전 중앙화
extra["jjwtVersion"] = "0.13.0"
extra["sentryVersion"] = "7.14.0"

// 모든 프로젝트 공통 설정
allprojects {
    group = "org.depromeet"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

// 하위 프로젝트에만 적용
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.springframework.boot")
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "org.jetbrains.kotlin.plugin.spring")
    apply(plugin = "org.jetbrains.kotlin.plugin.jpa")
    apply(plugin = "jacoco")

    configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
        imports {
            mavenBom("org.springframework.cloud:spring-cloud-dependencies:2024.0.1")
        }
    }

    // Jacoco 설정
    configure<JacocoPluginExtension> {
        toolVersion = "0.8.11"
    }

    // tasks 설정
    tasks.withType<JavaCompile> {
        targetCompatibility = "21"
        sourceCompatibility = "21"
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            freeCompilerArgs = listOf("-Xjsr305=strict")
            jvmTarget = "21"
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        
        // 테스트 리포트 설정
        reports {
            junitXml.required.set(true)
            html.required.set(true)
        }
        
        // 테스트 실패해도 계속 진행 (전체 리포트 생성을 위해)
        ignoreFailures = true
        
        // CI 환경에서만 테스트 캐시 비활성화 (로컬 개발 성능 저하 방지)
        val isCI = System.getenv("CI")?.toBoolean() ?: false
        if (isCI) {
            outputs.upToDateWhen { false }
        }
        
        // Jacoco 리포트 생성을 위해 테스트 후 자동 실행
        finalizedBy(tasks.named("jacocoTestReport"))
    }

    // Jacoco 테스트 리포트 설정
    tasks.named<JacocoReport>("jacocoTestReport") {
        
        reports {
            xml.required.set(true)
            html.required.set(true)
            csv.required.set(false)
        }
        
        classDirectories.setFrom(
            files(classDirectories.files.map {
                fileTree(it) {
                    exclude(
                        "**/Q*.*",           // QueryDSL 생성 파일
                        "**/*Application*",  // Application 클래스
                        "**/*Config*",       // Config 클래스
                        "**/*Dto*",          // DTO 클래스
                        "**/*Request*",      // Request 클래스
                        "**/*Response*",     // Response 클래스
                        "**/*Entity*",       // Entity 클래스
                        "**/*Exception*"     // Exception 클래스
                    )
                }
            })
        )
    }

    afterEvaluate {
        dependencies {
            add("testImplementation", "org.junit.jupiter:junit-jupiter:5.9.2")
            add("testImplementation", "org.jetbrains.kotlin:kotlin-test-junit5:1.8.20")
            add("implementation", "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
            add("implementation", "org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.7.3")
            add("implementation", "org.jetbrains.kotlinx:kotlinx-coroutines-slf4j:1.7.3")
            add("implementation", "io.sentry:sentry-spring-boot-starter-jakarta:${rootProject.extra["sentryVersion"]}")
            add("implementation", "io.sentry:sentry-logback:${rootProject.extra["sentryVersion"]}")
        }
    }
}

// SonarQube 설정
sonar {
    properties {
        property("sonar.projectKey", "depromeet-1_17th-team3-server")
        property("sonar.projectName", "17th-team3-Server")
        property("sonar.organization", "depromeet-1")
        property("sonar.host.url", "https://sonarcloud.io")
        property("sonar.gradle.skipCompile", "true")
        
        // 코드 커버리지 리포트 경로 (모든 서브모듈의 Jacoco 리포트)
        property("sonar.coverage.jacoco.xmlReportPaths", 
            subprojects.map { "${it.layout.buildDirectory.get().asFile}/reports/jacoco/test/jacocoTestReport.xml" }.joinToString(",")
        )
        
        // 분석 제외 파일
        property("sonar.exclusions", 
            "**/Q*.java," +
            "**/*Application.kt," +
            "**/*Config.kt," +
            "**/*Dto.kt," +
            "**/*Request.kt," +
            "**/*Response.kt," +
            "**/*Entity.kt," +
            "**/*Exception.kt"
        )
        
        // 테스트 커버리지 제외 파일
        property("sonar.coverage.exclusions", 
            "**/Q*.java," +
            "**/*Application.kt," +
            "**/*Config.kt," +
            "**/*Dto.kt," +
            "**/*Request.kt," +
            "**/*Response.kt," +
            "**/*Entity.kt," +
            "**/*Exception.kt"
        )
        
        // Kotlin 소스 경로
        property("sonar.sources", "src/main/kotlin")
        property("sonar.tests", "src/test/kotlin")
        
        // Java 버전
        property("sonar.java.source", "21")
    }
}

// 루트 프로젝트 빌드 비활성화 (sonar, jacoco 관련 태스크 제외)
tasks.configureEach {
    if (name == "sonar" || name.contains("jacoco") || name == "help") {
        onlyIf { true }
    } else {
        onlyIf { false }
    }
}
