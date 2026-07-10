plugins {
    // Apply the foojay-resolver plugin to allow automatic download of JDKs
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "AgentIO"

include("agentio-core")
include("agentio-module-camel")
include("agentio-module-data")
include("agentio-module-text2sql")
include("agentio-module-solver")
include("agentio-module-compass")
include("agentio-examples")
