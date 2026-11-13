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
    // Testing — the pattern machinery (planner, cascade, handoff policy, HITL loop) is pure and tested without an LLM
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.2.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
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

tasks.register<JavaExec>("RunGoapAgenticFunction") {
    mainClass.set("io.github.mbbhalla.agentio.module.patterns.goap.RunnerKt")
    classpath = sourceSets["main"].runtimeClasspath
    debug = false
    debugOptions {
        server = true
        suspend = true
        host = "localhost"
        port = 5030
    }
    args =
        listOf(
            rootProject.projectDir.absolutePath,
            // "heuristic": a cheap deterministic risk scan is available and the planner prefers it.
            // "llm": the scan is excluded, so the planner routes through the LLM assessor instead.
            project.findProperty("riskMode")?.toString() ?: "heuristic",
        )
}

tasks.register<JavaExec>("RunPlanExecuteAgenticFunction") {
    mainClass.set("io.github.mbbhalla.agentio.module.patterns.planexecute.RunnerKt")
    classpath = sourceSets["main"].runtimeClasspath
    debug = false
    debugOptions {
        server = true
        suspend = true
        host = "localhost"
        port = 5031
    }
    args =
        listOfNotNull(
            rootProject.projectDir.absolutePath,
            project.findProperty("goal")?.toString(),
        )
}

tasks.register<JavaExec>("RunRoutingAgenticFunction") {
    mainClass.set("io.github.mbbhalla.agentio.module.patterns.routing.RunnerKt")
    classpath = sourceSets["main"].runtimeClasspath
    debug = false
    debugOptions {
        server = true
        suspend = true
        host = "localhost"
        port = 5032
    }
    args =
        listOfNotNull(
            rootProject.projectDir.absolutePath,
            project.findProperty("request")?.toString(),
        )
}

tasks.register<JavaExec>("RunHandoffAgenticFunction") {
    mainClass.set("io.github.mbbhalla.agentio.module.patterns.handoff.RunnerKt")
    classpath = sourceSets["main"].runtimeClasspath
    debug = false
    debugOptions {
        server = true
        suspend = true
        host = "localhost"
        port = 5033
    }
    args =
        listOfNotNull(
            project.findProperty("customerId")?.toString() ?: "cust-1001",
            project.findProperty("message")?.toString(),
        )
}

tasks.register<JavaExec>("RunModelCascadeAgenticFunction") {
    mainClass.set("io.github.mbbhalla.agentio.module.patterns.cascade.RunnerKt")
    classpath = sourceSets["main"].runtimeClasspath
    debug = false
    debugOptions {
        server = true
        suspend = true
        host = "localhost"
        port = 5034
    }
    args = listOfNotNull(project.findProperty("task")?.toString())
}

tasks.register<JavaExec>("RunHumanInTheLoopAgenticFunction") {
    mainClass.set("io.github.mbbhalla.agentio.module.patterns.hitl.RunnerKt")
    classpath = sourceSets["main"].runtimeClasspath
    debug = false
    debugOptions {
        server = true
        suspend = true
        host = "localhost"
        port = 5035
    }
    // "scripted" replays a fixed reviewer; "interactive" reads approve/reject/feedback from the terminal.
    val mode = project.findProperty("mode")?.toString() ?: "scripted"
    if (mode == "interactive") standardInput = System.`in`
    args = listOf(mode)
}
