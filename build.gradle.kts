plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
    application
}

group = "app.hononeko.miru"
version = "0.1.0"

repositories {
    mavenCentral()
    // Google's mirror of Maven Central, asked only when Central answers "not found": Central sometimes answers
    // that for artifacts it has, failing CI builds
    maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralMirror" }
}

dependencies {
    // Kotlin, Coroutines & Serialization
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // Arrow-kt
    implementation(libs.arrow.core)
    implementation(libs.arrow.fx.coroutines)

    // Ktor Server & Client
    implementation(libs.bundles.ktor.server)
    implementation(libs.bundles.ktor.client)

    // Structured Logging
    implementation(libs.bundles.logging)

    // Persistence & Migrations
    implementation(libs.bundles.persistence)

    // Cryptography
    implementation(libs.bouncycastle.bcprov)

    // Testing
    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.server.websockets)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
}

application {
    mainClass.set("app.hononeko.miru.ApplicationKt")
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        allWarningsAsErrors.set(true)
        freeCompilerArgs.addAll(
            "-Xjsr305=strict",
        )
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.named<Test>("test") {
    filter {
        excludeTestsMatching("*IntegrationTest")
        excludeTestsMatching("*IT")
    }
}

val testTask = tasks.named<Test>("test")

tasks.register<Test>("integrationTest") {
    description = "Runs integration tests (e.g. Testcontainers)."
    group = "verification"
    testClassesDirs = testTask.get().testClassesDirs
    classpath = testTask.get().classpath
    filter {
        includeTestsMatching("*IntegrationTest")
        includeTestsMatching("*IT")
        isFailOnNoMatchingTests = false
    }
    shouldRunAfter(testTask)
}

tasks.shadowJar {
    archiveFileName.set("miru-all.jar")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

detekt {
    buildUponDefaultConfig = true
    parallel = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
}

kover {
    currentProject {
        instrumentation {
            disabledForTestTasks.add("integrationTest")
        }
    }
}

tasks.named("detekt") {
    enabled = false
}

tasks.named("check") {
    dependsOn("detektMain", "detektTest", "koverXmlReport")
}
