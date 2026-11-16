plugins {
    `java-library`
}

dependencies {
    // AgentIO Core — re-exported because db tool classes extend AbstractMcpTool from core.
    // MCP SDK comes transitively via core's api dependency.
    api(project(":agentio-core"))

    // DuckDB JDBC
    implementation("org.duckdb:duckdb_jdbc:2.0.0.1")
    // RE2 for Java — validates ValueConstraint.Pattern with the regex engine DuckDB's regexp_matches uses
    implementation("com.google.re2j:re2j:1.7")

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // YAML parsing (kaml — kotlinx-serialization backend)
    implementation("com.charleskorn.kaml:kaml:0.104.0")

    // AWS SDK for S3
    implementation("aws.sdk.kotlin:s3:1.6.68")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // MVEL2 expression engine
    implementation("org.mvel:mvel2:2.5.2.Final")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.16")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.2.0")
    testImplementation("io.mockk:mockk:1.13.13")
    // Validates the ODCS test fixture against the standard's published JSON Schema
    testImplementation("com.networknt:json-schema-validator:1.5.3")
    testImplementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.22.1")
    testRuntimeOnly("ch.qos.logback:logback-classic:1.5.12")
}
