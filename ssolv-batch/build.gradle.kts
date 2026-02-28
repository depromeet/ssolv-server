plugins {
    id("com.google.cloud.tools.jib")
}

dependencies {
    implementation(project(":ssolv-infrastructure"))
    implementation(project(":ssolv-api-common"))

    runtimeOnly("com.mysql:mysql-connector-j")
}

tasks {
    jar {
        enabled = true
        archiveClassifier.set("")
    }
    bootJar {
        enabled = false
    }
}

jib {
    from {
        image = "eclipse-temurin:21-jre"
    }
    to {
        image = "registry.ssolv.site/batch-server"
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
