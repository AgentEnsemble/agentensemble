import net.ltgt.gradle.errorprone.errorprone
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    `java-library`
    id("com.vanniktech.maven.publish")
    id("com.diffplug.spotless")
    id("net.ltgt.errorprone")
    jacoco
}

// Capture the errorprone-core version from the root project's version catalog.
// buildSrc convention plugins cannot access the root catalog directly;
// we resolve the artifact by its coordinates instead.
val errorproneVersion = "2.48.0"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configure<com.diffplug.gradle.spotless.SpotlessExtension> {
    java {
        palantirJavaFormat("2.47.0")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
    options.errorprone {
        disable("MissingSummary")
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
    finalizedBy(tasks.named("jacocoTestReport"))
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.90".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                minimum = "0.75".toBigDecimal()
            }
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.named("jacocoTestCoverageVerification"))
}

// Common tool module dependencies
dependencies {
    api(project(":agentensemble-core"))

    add("errorprone", "com.google.errorprone:error_prone_core:$errorproneVersion")

    // Test dependencies
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("org.mockito:mockito-core:5.22.0")
    testImplementation("org.slf4j:slf4j-simple:2.0.17")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.3")
}

// Common Maven publishing configuration.
// Coordinates: net.agentensemble:agentensemble-tools-<submodule-name>:<version>
// Each module sets the POM name and description via mavenPublishing { pom { ... } }.
mavenPublishing {
    // Derive the artifactId as "agentensemble-tools-<name>" where <name> is the
    // Gradle subproject name (e.g., "calculator", "datetime", "web-search").
    coordinates(
        groupId = "net.agentensemble",
        artifactId = "agentensemble-tools-${project.name}",
        version = "${project.version}",
    )

    publishToMavenCentral()
    signAllPublications()

    pom {
        url = "https://github.com/AgentEnsemble/agentensemble"

        licenses {
            license {
                name = "MIT License"
                url = "https://opensource.org/licenses/MIT"
            }
        }

        developers {
            developer {
                id = "agentensemble"
                name = "AgentEnsemble"
                url = "https://github.com/AgentEnsemble"
            }
        }

        scm {
            connection = "scm:git:git://github.com/AgentEnsemble/agentensemble.git"
            developerConnection = "scm:git:ssh://github.com/AgentEnsemble/agentensemble.git"
            url = "https://github.com/AgentEnsemble/agentensemble"
        }

        issueManagement {
            system = "GitHub"
            url = "https://github.com/AgentEnsemble/agentensemble/issues"
        }
    }
}
