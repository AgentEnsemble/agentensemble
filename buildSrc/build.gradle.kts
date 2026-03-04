plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    implementation("com.vanniktech.maven.publish:com.vanniktech.maven.publish.gradle.plugin:0.36.0")
    implementation("com.diffplug.spotless:com.diffplug.spotless.gradle.plugin:8.3.0")
    implementation("net.ltgt.gradle:gradle-errorprone-plugin:5.1.0")
}
