dependencies {
    implementation(project(":agentio-core"))

    // Vavr
    implementation("io.vavr:vavr-kotlin:0.10.2")

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

tasks.register<JavaExec>("RunAdaptiveExperiment") {
    description = "Run the Adaptive CMM experiment (Needle-in-a-Haystack / Multi-Needle)"
    group = "experiment"
    mainClass.set("io.github.mbbhalla.agentio.experiments.adaptive.RunnerKt")
    classpath = sourceSets["main"].runtimeClasspath
}
