plugins {
    java
    id("org.springframework.boot") version "3.5.11"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.jake"
version = "0.0.1-SNAPSHOT"
description = "RealtimeAPI"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
    maven { url = uri("https://repo.spring.io/snapshot") }
}

extra["springCloudVersion"] = "2025.0.1"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.6")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.cloud:spring-cloud-starter-circuitbreaker-resilience4j")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()

    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
}

tasks.register<Exec>("infraUp") {
    group = "application"
    description = "Start postgres and redis using docker compose"
    commandLine("docker", "compose", "up", "-d", "postgres", "redis")
}

tasks.register<Exec>("infraFullUp") {
    group = "application"
    description = "Build and start the full local stack (postgres, redis, app, prometheus, grafana)"
    commandLine("docker", "compose", "up", "-d", "--build", "postgres", "redis", "app", "prometheus", "grafana")
}

tasks.register<Exec>("infraDown") {
    group = "application"
    description = "Stop postgres and redis using docker compose"
    commandLine("docker", "compose", "stop", "postgres", "redis")
}

tasks.register<Exec>("infraFullDown") {
    group = "application"
    description = "Stop the full local stack"
    commandLine("docker", "compose", "stop", "app", "prometheus", "grafana", "postgres", "redis")
}

tasks.named("bootRun") {
    dependsOn("infraUp")
}

tasks.register("dev") {
    group = "application"
    description = "Build and start the full local docker compose stack"
    dependsOn("infraFullUp")
}

tasks.register("devLocal") {
    group = "application"
    description = "Run bootRun with continuous classes compilation for DevTools restart"
    dependsOn("infraUp")
    doLast {
        exec {
            workingDir = projectDir
            commandLine("bash", "scripts/dev-watch.sh")
        }
    }
}

tasks.register("devDown") {
    group = "application"
    description = "Stop the full local docker compose stack"
    dependsOn("infraFullDown")
}
