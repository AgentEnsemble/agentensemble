plugins {
    id("agentensemble.tool-conventions")
}

dependencies {
    implementation(libs.jackson.databind)

    // Review module -- compileOnly so it is not shipped transitively; users who want
    // tool-level approval gates add agentensemble-review explicitly.
    compileOnly(project(":agentensemble-review"))
    testImplementation(project(":agentensemble-review"))
}

mavenPublishing {
    pom {
        name = "AgentEnsemble Tools: Process"
        description = "Subprocess execution tool for AgentEnsemble (cross-language support)"
    }
}
