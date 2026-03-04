plugins {
    id("agentensemble.tool-conventions")
}

dependencies {
    implementation(libs.jackson.databind)
}

mavenPublishing {
    pom {
        name = "AgentEnsemble Tools: HTTP"
        description = "HTTP endpoint wrapping tool for AgentEnsemble"
    }
}
