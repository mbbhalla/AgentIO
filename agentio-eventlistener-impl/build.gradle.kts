plugins {
    `java-library`
}

dependencies {
    // Depends on agentio-core for EventListener interface, model classes, etc.
    api(project(":agentio-core"))

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.2.0")

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // AWS SDK for Bedrock Runtime (for ContentBlock types in serialization)
    implementation("aws.sdk.kotlin:bedrockruntime:1.6.68")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Logging — only the SLF4J API facade; consumers provide their own backend
    implementation("org.slf4j:slf4j-api:2.0.16")
    testRuntimeOnly("ch.qos.logback:logback-classic:1.5.12")
}
