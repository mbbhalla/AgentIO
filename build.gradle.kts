plugins {
    kotlin("jvm") version "2.2.0" apply false
    kotlin("plugin.serialization") version "2.2.0" apply false
    id("org.owasp.dependencycheck") version "12.2.2"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

allprojects {
    group = "io.github.mbbhalla"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
        maven {
            url = uri("https://aws.oss.sonatype.org/content/repositories/snapshots/")
        }
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    dependencyLocking {
        lockAllConfigurations()
    }

    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin" && requested.name.startsWith("kotlin-stdlib")) {
                useVersion("2.2.0")
            }
        }
    }
}

dependencyCheck {
    failBuildOnCVSS = 7.0f
    failOnError = false
    formats = listOf("HTML", "JSON")
    analyzers.assemblyEnabled = false
    nvd.apiKey = System.getenv("NVD_API_KEY") ?: ""

    // Only scan what actually ships: the runtime classpath. This excludes build-time tooling
    // (Kotlin compiler/daemon/scripting, ABI tools, Gradle buildscript deps) that dependency-check
    // otherwise flags but that is never packaged into AgentIO's artifacts.
    scanConfigurations = listOf("runtimeClasspath")

    // Suppress known false positives (e.g. the MCP Kotlin SDK mis-matched as the Kotlin language).
    suppressionFile = "$rootDir/dependency-check-suppressions.xml"
}
