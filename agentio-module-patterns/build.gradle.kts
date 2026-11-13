/*
    agentio-module-patterns collects agent design patterns (adversarial, orchestration, ...)
    built on agentio-core. Each pattern is self-contained under its own package with a Runner
    and a matching Run* Gradle task, mirroring agentio-examples. Domain-specific examples that
    are not primarily about a coordination pattern stay in agentio-examples.
 */
dependencies {
    implementation(project(":agentio-core"))
    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // AWS SDK for Bedrock Runtime
    implementation("aws.sdk.kotlin:bedrockruntime:1.6.68")
    // Logging
    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("ch.qos.logback:logback-classic:1.5.12")
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}

tasks.register<JavaExec>("RunAdversarialAgenticFunction") {
    mainClass.set("io.github.mbbhalla.agentio.module.patterns.adversarial.RunnerKt")
    classpath = sourceSets["main"].runtimeClasspath
    debug = false
    debugOptions {
        server = true
        suspend = true
        host = "localhost"
        port = 5015
    }
    args =
        listOf(
            project.findProperty("requirements")?.toString()
                ?: """
                    |Build a task management API with users, projects, tasks, and comments.
                    | Users authenticate via OAuth2. Tasks have priorities and due dates.
                    | Support filtering and pagination.
                """.trimMargin(),
        )
}

tasks.register<JavaExec>("RunOrchestrationAgenticFunction") {
    mainClass.set("io.github.mbbhalla.agentio.module.patterns.orchestration.RunnerKt")
    classpath = sourceSets["main"].runtimeClasspath
    debug = false
    debugOptions {
        server = true
        suspend = true
        host = "localhost"
        port = 5016
    }
    args =
        listOf(
            rootProject.projectDir.absolutePath,
            "kt",
        )
}
