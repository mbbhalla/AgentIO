plugins {
    `java-library`
}

dependencies {
    // Depends on agentio-core for ContextMemoryManager interface, model classes, etc.
    api(project(":agentio-core"))

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.2.0")

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Vavr
    implementation("io.vavr:vavr-kotlin:0.10.2")

    // AWS SDK for Bedrock Runtime
    implementation("aws.sdk.kotlin:bedrockruntime:1.6.68")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("ch.qos.logback:logback-classic:1.5.12")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}
