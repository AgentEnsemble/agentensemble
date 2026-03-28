plugins {
    id("agentensemble.tool-conventions")
}

dependencies {
    implementation(libs.jackson.databind)

    // Review module -- compileOnly so it is not shipped transitively
    compileOnly(project(":agentensemble-review"))
    testImplementation(project(":agentensemble-review"))
}

mavenPublishing {
    pom {
        name = "AgentEnsemble Tools: Coding"
        description = "Coding tools (glob, search, edit, git, shell, build, test) for AgentEnsemble"
    }
}
