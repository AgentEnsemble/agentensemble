import net.ltgt.gradle.errorprone.errorprone
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    java
    // spotless and errorprone are provided via buildSrc convention plugins;
    // declare without version here to avoid classpath conflicts.
    id("com.diffplug.spotless") apply false
    id("net.ltgt.errorprone") apply false
}

// Capture catalog references on the root project before entering subprojects {}.
// Inside subprojects {}, `this` is each subproject, which does not have the
// generated Kotlin DSL catalog accessor registered at configuration time.
val errorproneCoreLib = libs.errorprone.core

subprojects {
    // Skip projects that manage themselves via convention plugins:
    // - "bom" and "agentensemble-bom" are java-platform projects (cannot have java plugin applied)
    // - sub-modules of :agentensemble-tools use the agentensemble.tool-conventions plugin
    if (name == "bom" || name == "agentensemble-bom" || parent?.path == ":agentensemble-tools") {
        return@subprojects
    }

    apply(plugin = "java")
    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "net.ltgt.errorprone")
    apply(plugin = "jacoco")

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
            // MissingSummary fires on Lombok-generated methods that have no Javadoc.
            // Lombok marks generated code with @Generated, but this check still fires
            // on the enclosing class when some members lack summaries.
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

    dependencies {
        add("errorprone", errorproneCoreLib)
    }
}

tasks.register("setupGitHooks") {
    group = "setup"
    description = "Configures git to use the project's .githooks directory."
    doLast {
        val exitCode = ProcessBuilder("git", "config", "core.hooksPath", ".githooks")
            .directory(rootDir)
            .inheritIO()
            .start()
            .waitFor()
        if (exitCode != 0) {
            throw GradleException("Failed to set git core.hooksPath (exit $exitCode)")
        }
        logger.lifecycle("Git hooks configured. Pre-commit hook will run spotlessApply on staged files.")
    }
}
