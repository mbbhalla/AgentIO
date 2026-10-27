plugins {
    `java-library`
}

dependencies {
    api(project(":agentio-core"))
    api(project(":agentio-module-data"))

    // SQL parsing / AST rewriting for SQL authorization (JSqlParser is Apache-2.0 / LGPL dual licensed)
    implementation("com.github.jsqlparser:jsqlparser:5.3")

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // AWS SDK for Bedrock Runtime
    implementation("aws.sdk.kotlin:bedrockruntime:1.6.68")

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
    // In-memory DuckDB used to verify that authorized SQL is semantically row-filtered
    testImplementation("org.duckdb:duckdb_jdbc:1.5.2.0")
}
