pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // Google's mirror of Maven Central, asked only when Central answers "not found": Central sometimes answers
        // that for artifacts it has, failing CI builds
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralMirror" }
    }
}

rootProject.name = "miru"
