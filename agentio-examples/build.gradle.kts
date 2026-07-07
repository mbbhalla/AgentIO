dependencies {
    implementation(project(":agentio-core"))
    implementation(project(":agentio-module-data"))
    implementation(project(":agentio-module-text2sql"))
    implementation(project(":agentio-module-solver"))
    implementation(project(":agentio-module-compass"))

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
    args =
        listOf(
            "${project.projectDir}/src/main/kotlin/io/github/mbbhalla/agentio/examples/hackernews/server/server_hacker_news.sh",
        )
}

tasks.register<JavaExec>("RunFetchAgenticFunction") {
    mainClass.set("io.github.mbbhalla.agentio.examples.fetch.RunnerKt")
    classpath = sourceSets["main"].runtimeClasspath
    debug = false
    debugOptions {
        server = true
        suspend = true
        host = "localhost"
        port = 5012
    }
    args =
        listOf(
            "${project.projectDir}/src/main/kotlin/io/github/mbbhalla/agentio/examples/fetch/server/server_fetch.sh",
        )
}

tasks.register<JavaExec>("RunGitAnalyzerAgenticFunction") {
    mainClass.set("io.github.mbbhalla.agentio.examples.gitanalyzer.RunnerKt")
    classpath = sourceSets["main"].runtimeClasspath
    debug = false
    debugOptions {
        server = true
        suspend = true
        host = "localhost"
        port = 5013
    }
    args =
        listOf(
            rootProject.projectDir.absolutePath,
        )
}

tasks.register<JavaExec>("RunCodeMetricsAgenticFunction") {
    mainClass.set("io.github.mbbhalla.agentio.examples.codemetrics.RunnerKt")
    classpath = sourceSets["main"].runtimeClasspath
    debug = false
    debugOptions {
        server = true
        suspend = true
        host = "localhost"
        port = 5014
    }
    args =
        listOf(
            rootProject.projectDir.absolutePath,
            project.findProperty("checkpointDir")?.toString()
                ?: "${rootProject.projectDir}/build/checkpoints",
        )
}

tasks.register<JavaExec>("RunAdversarialAgenticFunction") {
    mainClass.set("io.github.mbbhalla.agentio.examples.adversarial.RunnerKt")
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
    mainClass.set("io.github.mbbhalla.agentio.examples.orchestration.RunnerKt")
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

tasks.register<JavaExec>("RunText2SqlAgenticFunction-RetailDB") {
    mainClass.set("io.github.mbbhalla.agentio.examples.text2sql.RunnerKt")
    classpath = sourceSets["main"].runtimeClasspath
    debug = false
    debugOptions {
        server = true
        suspend = true
        host = "localhost"
        port = 5017
    }
    jvmArgs = listOf("-Dagentio.text2sql.entrypoint=retail")
    args =
        listOf(
            project.findProperty("query")?.toString()
                ?: "What products will have inventory below safety stock levels in the next week and above the week after ?",
        )
}

tasks.register<JavaExec>("RunText2SqlAgenticFunction-EmployeeDB") {
    mainClass.set("io.github.mbbhalla.agentio.examples.text2sql.RunnerKt")
    classpath = sourceSets["main"].runtimeClasspath
    debug = false
    debugOptions {
        server = true
        suspend = true
        host = "localhost"
        port = 5018
    }
    jvmArgs = listOf("-Dagentio.text2sql.entrypoint=employee")
    args =
        listOf(
            project.findProperty("query")?.toString()
                ?: "Who are the top-rated employees and what projects are they working on?",
        )
}

tasks.register<JavaExec>("RunCompassAgenticFunction") {
    mainClass.set("io.github.mbbhalla.agentio.examples.compass.RunnerKt")
    classpath = sourceSets["main"].runtimeClasspath
    debug = false
    debugOptions {
        server = true
        suspend = true
        host = "localhost"
        port = 5019
    }
    args =
        listOf(
            project.findProperty("objective")?.toString()
                ?: "Overstock at site DC-SEATTLE for product SSD-1TB-NVMe in the month of June 2025",
        )
}
