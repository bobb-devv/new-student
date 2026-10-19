plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

group = "com.studentcommandcenter"
version = "2.0.0"

application {
    mainClass.set("com.studentcommandcenter.server.ApplicationKt")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Ktor server
    implementation("io.ktor:ktor-server-core-jvm:2.3.12")
    implementation("io.ktor:ktor-server-netty-jvm:2.3.12")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:2.3.12")
    implementation("io.ktor:ktor-server-call-logging-jvm:2.3.12")
    implementation("io.ktor:ktor-server-html-builder-jvm:2.3.12")
    implementation("io.ktor:ktor-server-status-pages-jvm:2.3.12")
    implementation("ch.qos.logback:logback-classic:1.5.18")

    // Ktor client (IPFS Kubo HTTP API)
    implementation("io.ktor:ktor-client-core-jvm:2.3.12")
    implementation("io.ktor:ktor-client-cio-jvm:2.3.12")
    implementation("io.ktor:ktor-client-content-negotiation-jvm:2.3.12")

    // Persistence
    implementation("org.jetbrains.exposed:exposed-core:0.56.0")
    implementation("org.jetbrains.exposed:exposed-dao:0.56.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.56.0")
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")

    // Polygon / EVM
    implementation("org.web3j:core:4.12.3")

    implementation("org.jetbrains.kotlinx:kotlinx-html-jvm:0.9.1")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
}

tasks.test { useJUnitPlatform() }

// Keep Java and Kotlin bytecode targets aligned even when Gradle runs on JDK 21+.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
