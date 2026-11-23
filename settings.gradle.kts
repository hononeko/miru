pluginManagement {
    repositories {
        // Google's mirror of Maven Central first: Central rate-limits CI runners (HTTP 429), and Gradle moves to the
        // next repository only when one answers "not found", never on an error. Whatever the mirror lacks (plugins
        // published only to the portal, releases it has not copied yet) answers "not found" there and comes from below.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralMirror" }
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "miru"
