dependencies {
    implementation(project(":ssolv-api-common"))
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
        enabled = false
    }
}
