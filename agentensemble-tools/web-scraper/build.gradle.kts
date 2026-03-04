plugins {
    id("agentensemble.tool-conventions")
}

dependencies {
    implementation(libs.jsoup)
}

mavenPublishing {
    pom {
        name = "AgentEnsemble Tools: Web Scraper"
        description = "Web page scraping tool for AgentEnsemble"
    }
}
