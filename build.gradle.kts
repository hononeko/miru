plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
    application
}

group = "app.hononeko.miru"
version =
    findProperty("version")
        ?.toString()
        ?.takeIf { it != "unspecified" && it.isNotBlank() }
        ?: "1.0.0"

repositories {
    // Google's mirror of Maven Central first: Central rate-limits CI runners (HTTP 429), and Gradle moves to the next
    // repository only when one answers "not found", never on an error. Releases the mirror has not copied yet answer
    // "not found" there and come from Central.
    maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralMirror" }
    mavenCentral()
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
    // Checked against the payloads by WebhookEventEvaluatorTest
    inputs.file("docs/webhooks.md").withPathSensitivity(PathSensitivity.RELATIVE)
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

tasks.jar {
    manifest {
        attributes["Implementation-Version"] = project.version.toString()
    }
}

tasks.shadowJar {
    archiveFileName.set("miru-all.jar")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    manifest {
        attributes["Implementation-Version"] = project.version.toString()
    }
}

// Everything `shadowJar` resolves: the libraries, plus the Kotlin compiler and its serialization plugin.
val shadowJarBuildClasspaths =
    setOf(
        "compileClasspath",
        "runtimeClasspath",
        "annotationProcessor",
        "kotlinCompilerClasspath",
        "kotlinCompilerPluginClasspathMain",
        "kotlinBuildToolsApiClasspath",
    )

// The Docker build runs this with only the build scripts copied in, so the downloaded jars live in a layer that only
// changes with the build scripts, and a source-only change builds the image without downloading anything.
tasks.register("downloadDependencies") {
    description = "Downloads every dependency shadowJar needs, without compiling anything."
    val classpaths = files(configurations.matching { it.name in shadowJarBuildClasspaths })
    doLast {
        logger.lifecycle("Downloaded {} dependency files", classpaths.files.size)
    }
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
