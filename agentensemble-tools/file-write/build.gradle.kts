plugins {
    id("agentensemble.tool-conventions")
}

dependencies {
    implementation(libs.jackson.databind)
}

mavenPublishing {
    pom {
        name = "AgentEnsemble Tools: File Write"
        description = "Sandboxed file writing tool for AgentEnsemble"
    }
}
