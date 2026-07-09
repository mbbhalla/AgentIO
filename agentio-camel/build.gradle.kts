plugins {
    `java-library`
    jacoco
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

/*
    agentio-camel is an OPTIONAL edge adapter that lets an AgentIO Instructible (a single
    agentic function, an AgenticFunctionEvaluator, or any composite) participate in an
    Apache Camel route as a producer endpoint (to("agentio:<beanName>")).

    Architectural invariant: the dependency arrow points inward only.
    agentio-camel -> agentio-core. agentio-core MUST NOT depend on agentio-camel or on
    Apache Camel. This keeps the typed, coroutine-native core free of the connector
    ecosystem's transitive/CVE surface, which stays quarantined in this module.
 */
dependencies {
    api(project(":agentio-core"))

    // Apache Camel SPI to author a component/endpoint/producer. camel-support (not the full
    // camel-core engine) is all the production code needs: DefaultComponent, DefaultEndpoint,
    // DefaultAsyncProducer live here. Pinned to the latest stable Camel release.
    implementation("org.apache.camel:camel-support:4.21.0")

    // Coroutines — the producer bridges Camel's async SPI to the core's suspend invoke.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Logging — API facade only; consumers provide the backend.
    implementation("org.slf4j:slf4j-api:2.0.16")

    // Testing — the full camel-core engine (DefaultCamelContext, ProducerTemplate) is a test
    // concern only, so it is scoped to test and never leaks onto the production classpath.
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.2.0")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("org.apache.camel:camel-core:4.21.0")
    testRuntimeOnly("ch.qos.logback:logback-classic:1.5.12")
}
