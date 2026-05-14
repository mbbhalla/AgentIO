plugins {
    `java-library`
}

dependencies {
    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.2.0")

    // Jackson
    implementation("com.fasterxml.jackson.core:jackson-core:2.18.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2")

    // JSON Schema generation
    implementation("com.github.victools:jsonschema-generator:4.36.0")
    implementation("com.github.victools:jsonschema-module-javax-validation:4.36.0")
    implementation("javax.validation:validation-api:2.0.1.Final")

    // JSON Schema validation
    implementation("com.networknt:json-schema-validator:1.5.3")

    // Vavr (exposed in public API: Try<AgentOutput<O>>)
    api("io.vavr:vavr-kotlin:0.10.2")

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // AWS SDK for Bedrock Runtime
    implementation("aws.sdk.kotlin:bedrockruntime:1.6.68")

    // Model Context Protocol Kotlin SDK
    implementation("io.modelcontextprotocol:kotlin-sdk:0.12.0")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("ch.qos.logback:logback-classic:1.5.12")

    // Kotlin reflection
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.2.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}
