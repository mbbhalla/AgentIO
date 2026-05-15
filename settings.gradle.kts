plugins {
    // Apply the foojay-resolver plugin to allow automatic download of JDKs
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "AgentIO"

include("agentio-core")
include("agentio-cmm-impl")
include("agentio-eventlistener-impl")
include("agentio-examples")
include("agentio-experiments")
