package com.codersergg

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import org.slf4j.LoggerFactory

fun main() {
    embeddedServer(Netty, port = 8096, host = "0.0.0.0", module = Application::module).start(wait = true)
}

fun Application.module() {
    val log = LoggerFactory.getLogger("StartupTest")
    log.info(">>> Test log from module")
    install(ContentNegotiation) {
        json()
    }

    configureRouting()
    configureProbeRouting()
    configureVideoJobsRouting()
}
