plugins {
    `java-library`
}

dependencies {
    api(project(":agentio-core"))
    api(project(":agentio-module-data"))

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // AWS SDK for Bedrock Runtime
    implementation("aws.sdk.kotlin:bedrockruntime:1.6.68")

    // Model Context Protocol Kotlin SDK
    implementation("io.modelcontextprotocol:kotlin-sdk:0.12.0")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.16")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.2.0")
    testImplementation("io.mockk:mockk:1.13.13")
    testRuntimeOnly("ch.qos.logback:logback-classic:1.5.12")
}
