plugins {
    `java-library`
}

dependencies {
    api(project(":agentio-core"))
    api(project(":agentio-module-data"))

    // Z3 SMT solver — turnkey bundle includes native libraries for all major platforms
    api("tools.aqua:z3-turnkey:4.14.0")

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.16")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.2.0")
    testImplementation("io.mockk:mockk:1.13.13")
    testRuntimeOnly("ch.qos.logback:logback-classic:1.5.12")
}
