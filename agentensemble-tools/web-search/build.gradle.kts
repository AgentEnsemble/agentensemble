plugins {
    id("agentensemble.tool-conventions")
}

dependencies {
    implementation(libs.jackson.databind)
}

mavenPublishing {
    pom {
        name = "AgentEnsemble Tools: Web Search"
        description = "Web search tool for AgentEnsemble (Tavily and SerpApi providers)"
    }
}
