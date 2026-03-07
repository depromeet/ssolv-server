plugins {
    id("com.google.cloud.tools.jib")
}

dependencies {
    implementation(project(":ssolv-api-common"))
    implementation(project(":ssolv-api-core"))
    implementation(project(":ssolv-api-place"))
    implementation(project(":ssolv-domain"))
    implementation(project(":ssolv-infrastructure"))
    implementation(project(":ssolv-global-utils"))

    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    
    runtimeOnly("com.mysql:mysql-connector-j")
}

tasks {
    jar {
        enabled = true
        archiveClassifier.set("plain")
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
        image = "registry.ssolv.site/api-batch"
        tags = setOf("latest", "${project.version}")
    }
    container {
        jvmFlags = listOf(
            "-Duser.timezone=Asia/Seoul",
            "-XX:MaxRAMPercentage=75.0"
        )
        creationTime = "USE_CURRENT_TIMESTAMP"
    }
}
