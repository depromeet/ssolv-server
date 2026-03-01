plugins {
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
