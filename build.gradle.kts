import java.nio.file.Paths

val kotlin_version: String by project
val logback_version: String by project

plugins {
    kotlin("jvm") version "2.1.10"
    id("io.ktor.plugin") version "3.2.2"
    id("com.google.cloud.tools.jib") version "3.4.3"
    kotlin("plugin.serialization") version "1.9.10"
}

group = "com.codersergg"
val versionNumber = "0.4.13"
version = versionNumber

application {
    mainClass.set("io.ktor.server.netty.EngineMain")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core-jvm")
    implementation("io.ktor:ktor-server-netty")
    implementation("ch.qos.logback:logback-classic:1.5.13")
    implementation("ch.qos.logback:logback-classic:$logback_version")
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-config-yaml")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("io.ktor:ktor-client-core")
    implementation("io.ktor:ktor-client-cio")
    implementation("io.ktor:ktor-client-content-negotiation")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("io.ktor:ktor-server-call-logging:2.3.4")
    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")
}

jib {
    from {
        image = "codersergg/java21-ffmpeg:latest"
    }

    to {
        val dockerUser = System.getenv("DOCKER_HUB_USERNAME") ?: error("DOCKER_HUB_USERNAME not set")
        image = "$dockerUser/render-ffmpeg:v$versionNumber"

        auth {
            username = dockerUser
            password = System.getenv("DOCKER_HUB_PASSWORD") ?: error("DOCKER_HUB_PASSWORD not set")
        }
    }

    container {
        mainClass = "io.ktor.server.netty.EngineMain"
        jvmFlags = listOf("-Dio.ktor.development=true")
        ports = listOf("8096")
    }

    extraDirectories {
        setPaths(listOf(Paths.get("src/main/resources")))
    }
}
