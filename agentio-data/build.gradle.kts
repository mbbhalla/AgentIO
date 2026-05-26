plugins {
    `java-library`
}

dependencies {
    // AgentIO Core (generateList utility)
    implementation(project(":agentio-core"))

    // DuckDB JDBC
    implementation("org.duckdb:duckdb_jdbc:1.5.2.0")

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

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
    testRuntimeOnly("ch.qos.logback:logback-classic:1.5.12")
}
