dependencies {
    implementation(project(":agentio-core"))

    // Vavr
    implementation("io.vavr:vavr-kotlin:0.10.2")

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // AWS SDK for Bedrock Runtime
    implementation("aws.sdk.kotlin:bedrockruntime:1.6.68")

    // Model Context Protocol Kotlin SDK
    implementation("io.modelcontextprotocol:kotlin-sdk:0.12.0")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("ch.qos.logback:logback-classic:1.5.12")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}

tasks.register<JavaExec>("RunHackerNewsAgenticFunction") {
    mainClass.set("io.github.mbbhalla.agentio.examples.hackernews.RunnerKt")
    classpath = sourceSets["main"].runtimeClasspath
    debug = false
    debugOptions {
        server = true
        suspend = true
        host = "localhost"
        port = 5010
    }
    args = listOf(
        "${project.projectDir}/src/main/kotlin/io/github/mbbhalla/agentio/examples/hackernews/server/server_hacker_news.sh",
    )
}
