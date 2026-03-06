pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    // Declare all plugin versions here so that sub-projects can use id("...") without
    // re-specifying the version. buildSrc precompiled plugins resolve versions via
    // their own classpath; aligning the versions here prevents compatibility errors.
    plugins {
        id("com.vanniktech.maven.publish") version "0.36.0"
        id("com.diffplug.spotless") version "8.3.0"
        id("net.ltgt.errorprone") version "5.1.0"
    }
}

rootProject.name = "agentensemble"

include("agentensemble-bom")
include("agentensemble-core")
include("agentensemble-memory")
include("agentensemble-review")

// Per-tool modules nested under agentensemble-tools/
include("agentensemble-tools:calculator")
include("agentensemble-tools:datetime")
include("agentensemble-tools:json-parser")
include("agentensemble-tools:file-read")
include("agentensemble-tools:file-write")
include("agentensemble-tools:web-search")
include("agentensemble-tools:web-scraper")
include("agentensemble-tools:process")
include("agentensemble-tools:http")
include("agentensemble-tools:bom")

// Web module (WebSocket server + live dashboard -- optional)
include("agentensemble-web")

// Metrics integration modules
include("agentensemble-metrics-micrometer")

// Developer tooling module (DAG export, trace utilities)
include("agentensemble-devtools")

// Examples
include("agentensemble-examples")

// Maven artifact coordinates (groupId:artifactId:version) are configured via
// mavenPublishing in each module's build.gradle.kts or the convention plugin.
// This gives published artifacts the full name e.g. agentensemble-tools-calculator
// while keeping the Gradle project paths clean.
