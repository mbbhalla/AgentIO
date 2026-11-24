// Pinned Camel version (from gradle.properties) shared across the routing-engine and
// observability dependencies.
val camelVersion: String by project

dependencies {
    implementation(project(":agentio-core"))
    implementation(project(":agentio-module-camel"))
    implementation(project(":agentio-module-data"))
    implementation(project(":agentio-module-text2sql"))
    implementation(project(":agentio-module-solver"))
    implementation(project(":agentio-module-compass"))
    // Human-in-the-loop review for the ETL example
    implementation(project(":agentio-module-patterns"))

    // Apache Camel routing engine for the route example.
    implementation("org.apache.camel:camel-core:$camelVersion")

    // Camel JMX management: registers a managed MBean per route and per node (exchange counts,
    // processing times, throughput). This is the per-node statistic data the Camel CLI / TUI
    // surface alongside the route and topology diagrams.
    implementation("org.apache.camel:camel-management:$camelVersion")

    // CLI connector: exposes this standalone CamelContext to the local `camel` CLI over a
    // file-based protocol (no HTTP port, no embedded web server). Auto-detected from the classpath
    // on context.start() — look for the "Local CLI Connector started" log line. Once running, attach
    // `camel tui`, `camel get route`, or `camel trace` from another terminal to the RunCamelText2SqlRoute
    // / RunRerouteDemo tasks. Pulls in camel-console (dev-console SPI) transitively for the diagrams.
    implementation("org.apache.camel:camel-cli-connector:$camelVersion")

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // AWS SDK for Bedrock Runtime
    implementation("aws.sdk.kotlin:bedrockruntime:1.6.68")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("ch.qos.logback:logback-classic:1.5.12")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    // Testing — the ETL example's deterministic parts (source guard, workspace, plan check, pipeline, script) run without an LLM
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.2.0")
}

/** The engine-neutral AdventureWorks contract, converted from the ODCS project's PostgreSQL example and tested in agentio-module-data. */
val adventureWorksContract = "${rootProject.projectDir}/agentio-module-data/src/test/resources/odcs/odcs_example.generic.yaml"
val adventureWorksSources = "${project.projectDir}/src/main/resources/etl/adventureworks/source"

tasks.withType<Test> {
    systemProperty("etl.contract", adventureWorksContract)
    systemProperty("etl.sources", adventureWorksSources)
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

tasks.register<JavaExec>("RunCamelText2SqlRoute") {
    mainClass.set("io.github.mbbhalla.agentio.examples.camel.RunnerKt")
    classpath = sourceSets["main"].runtimeClasspath
    debug = false
    debugOptions {
        server = true
        suspend = true
        host = "localhost"
        port = 5020
    }
    args =
        listOf(
            project.findProperty("queriesFile")?.toString()
                ?: "${project.projectDir}/src/main/resources/camel/text2sql-queries.txt",
        )
}

/** Who answers the ETL agents' questions: "file" (SYSTEM_INPUT.md, default) or "console". */
val etlReview = project.findProperty("review")?.toString() ?: "file"

tasks.register<JavaExec>("RunEtlAgenticFunction-AdventureWorks") {
    mainClass.set("io.github.mbbhalla.agentio.examples.etl.RunnerKt")
    classpath = sourceSets["main"].runtimeClasspath
    debug = false
    debugOptions {
        server = true
        suspend = true
        host = "localhost"
        port = 5022
    }
    args =
        listOf(
            project.findProperty("contract")?.toString() ?: adventureWorksContract,
            project.findProperty("sourceDir")?.toString() ?: adventureWorksSources,
            project.findProperty("outputDir")?.toString() ?: "${project.layout.buildDirectory.get()}/etl-adventureworks",
            etlReview,
        )
    // The console reviewer answers in this terminal; the file reviewer (default) answers in SYSTEM_INPUT.md between runs.
    if (etlReview == "console") standardInput = System.`in`
}

tasks.register<JavaExec>("RunRerouteDemo") {
    mainClass.set("io.github.mbbhalla.agentio.examples.camel.RerouteRunnerKt")
    classpath = sourceSets["main"].runtimeClasspath
    debug = false
    debugOptions {
        server = true
        suspend = true
        host = "localhost"
        port = 5021
    }
    args =
        listOf(
            project.findProperty("eventsDir")?.toString()
                ?: "${project.projectDir}/src/main/resources/reroute/carrier-events",
            project.findProperty("outputDir")?.toString()
                ?: "${project.layout.buildDirectory.get()}/reroute-recommendations",
        )
}
