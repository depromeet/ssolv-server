plugins {
    id("org.springframework.boot") version "3.4.9" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("com.google.cloud.tools.jib") version "3.4.4" apply false
    kotlin("jvm") version "1.9.25" apply false
    kotlin("plugin.spring") version "1.9.25" apply false
    kotlin("plugin.jpa") version "1.9.25" apply false
    kotlin("kapt") version "1.9.25" apply false
    id("org.sonarqube") version "5.1.0.4882"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1" apply false
}

// 중복 의존성 관리 및 보안 버전 중앙화
extra["jjwtVersion"] = "0.13.0"
extra["sentryVersion"] = "7.14.0"

// harness 태스크로 호출된 빌드인지 감지 — harness 경로에서는 실패를 전파하여 차단,
// 그 외(로컬 빌드, 단일 테스트, IDE 등)에서는 리포팅만 하고 실패 무시 (Phase 1 정책)
// 정확 매칭: "harness" 또는 ":harness" 접미사만 허용 (오타/유사 이름 태스크에 오작동 방지).
val isHarness = gradle.startParameter.taskNames.any { it == "harness" || it.endsWith(":harness") }

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
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    // ktlint 설정: 기존 코드베이스에 대한 점진적 도입을 위해 baseline 전략 사용
    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set("1.3.1") // Kotlin 1.9.x 호환
        // ktlint는 항상 리포팅 전용 — CI(PR)에서 검증하므로 로컬 push 블로킹 불필요.
        ignoreFailures.set(true)
        outputToConsole.set(true)
        filter {
            exclude { element -> element.file.path.contains("/build/") }
            exclude { element -> element.file.path.contains("/generated/") }
            exclude("**/Q*.kt") // QueryDSL 생성 파일
        }
        reporters {
            reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
            reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
        }
    }

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

    val mockitoAgent = configurations.create("mockitoAgent")
    dependencies {
        mockitoAgent("net.bytebuddy:byte-buddy-agent") { isTransitive = false }
    }

    // 벤치마크용 디렉토리가 존재하면 소스셋에 포함 (깃 추적 제외된 벤치마크 코드 연동)
    configure<JavaPluginExtension> {
        val benchmarkInfra = file("${rootProject.projectDir}/benchmark/infrastructure")
        if (project.name == "ssolv-infrastructure" && benchmarkInfra.exists()) {
            sourceSets.getByName("main").java.srcDir(benchmarkInfra)
        }

        val benchmarkApi = file("${rootProject.projectDir}/benchmark/api")
        if (project.name == "ssolv-api-place" && benchmarkApi.exists()) {
            sourceSets.getByName("main").java.srcDir(benchmarkApi)
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        
        // Java 21+에서 Mockito 및 Java Agent 관련 경고 제거
        jvmArgs("-XX:+EnableDynamicAgentLoading", "-javaagent:${mockitoAgent.singleFile}")

        // 테스트 리포트 설정
        reports {
            junitXml.required.set(true)
            html.required.set(true)
        }
        
        // 일반 빌드에서는 전체 리포트 생성을 위해 실패 무시.
        // harness 경로에서는 실패 전파 — pre-push 시 차단.
        ignoreFailures = !isHarness
        
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
            add("implementation", "org.springframework.boot:spring-boot-starter-actuator")
            add("implementation", "io.micrometer:micrometer-registry-prometheus")
            add("implementation", "io.sentry:sentry-spring-boot-starter-jakarta:${rootProject.extra["sentryVersion"]}")
            add("implementation", "io.sentry:sentry-logback:${rootProject.extra["sentryVersion"]}")
            add("implementation", "io.opentelemetry:opentelemetry-extension-kotlin")
            add("implementation", "net.logstash.logback:logstash-logback-encoder:8.0")
        }
    }
}

// SonarQube 설정
sonar {
    properties {
        property("sonar.projectKey", "parkmineum_17th-team3-server")
        property("sonar.projectName", "17th-team3-Server")
        property("sonar.organization", "parkmineum")
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

        // dev, main 을 long-lived branch로 인식
        property("sonar.branch.longLivedBranchesRegex", "(main|dev)")
    }
}

// ============================================================
// Harness 통합 검증 태스크
// ktlint + 전체 테스트를 한 번에 실행 (pre-push에서 호출)
// ============================================================
tasks.register("harness") {
    group = "verification"
    description = "Runs all harness validations (ktlint + tests)"
    dependsOn(subprojects.map { "${it.path}:ktlintCheck" })
    dependsOn(subprojects.map { "${it.path}:test" })
}

// ============================================================
// Git Hooks 설치 태스크
// 로컬 개발 환경에서 한 번만 실행: ./gradlew installGitHooks
// ============================================================
tasks.register("installGitHooks") {
    group = "setup"
    description = "Installs local git hooks (pre-commit, pre-push) for this repository."
    inputs.dir("$rootDir/.githooks")

    onlyIf {
        file("$rootDir/.githooks").exists() && file("$rootDir/.git").exists()
    }

    doLast {
        val hooksDir = providers.exec {
            commandLine("git", "rev-parse", "--git-path", "hooks")
        }.standardOutput.asText.get().trim()

        if (hooksDir.isEmpty()) {
            throw GradleException("Could not resolve git hooks directory.")
        }

        // Remove any previously installed hooks that no longer exist in .githooks/.
        // Only delete files we installed — detected via the "# ssolv-managed" marker — so
        // user-authored hooks (e.g. a local pre-commit they wrote themselves) are never touched.
        val managedHookNames = setOf("pre-commit", "pre-push", "commit-msg")
        val shippedHookNames = file("$rootDir/.githooks").listFiles()?.map { it.name }?.toSet() ?: emptySet()
        val marker = "# ssolv-managed"
        managedHookNames.minus(shippedHookNames).forEach { name ->
            val stale = file("$hooksDir/$name")
            if (stale.exists() && stale.readText().contains(marker)) {
                stale.delete()
                println("🧹 Removed stale ssolv-managed git hook: $name")
            } else if (stale.exists()) {
                println("ℹ️  Skipped $name — not ssolv-managed (user-authored hook preserved)")
            }
        }

        copy {
            from("$rootDir/.githooks")
            into(hooksDir)
            filePermissions {
                unix("rwxr-xr-x")
            }
        }
        println("✅ Git hooks installed to $hooksDir")
    }
}

// 루트 프로젝트 빌드 비활성화 (sonar, jacoco, harness 관련 태스크 제외)
tasks.configureEach {
    val allowedNames = setOf("sonar", "help", "harness", "installGitHooks")
    if (name in allowedNames || name.contains("jacoco")) {
        onlyIf { true }
    } else {
        onlyIf { false }
    }
}
