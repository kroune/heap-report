plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.github.ajalt.clikt:clikt-jvm:5.1.0")
    implementation("com.dynatrace.hash4j:hash4j:0.31.0")
    implementation("it.unimi.dsi:fastutil:8.5.16")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        optIn.add("kotlin.ExperimentalUnsignedTypes")
        optIn.add("kotlin.contracts.ExperimentalContracts")
        optIn.add("kotlin.contracts.ExperimentalExtendedContracts")
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "8g"
    // Local benchmark knobs for SccColorRefinementMemoryTest.
    for (name in listOf("wl.scale", "wl.fixtures", "wl.maxRounds")) {
        System.getProperty(name)?.let { systemProperty(name, it) }
    }
}

application {
    mainClass.set("shapes.MainKt")
    // Direct CLI runs stay bounded; the backend sizes each analysis explicitly.
    applicationDefaultJvmArgs = listOf("-Xmx2g")
}

// Fat jar for the heap-report backend integration (SHAPES_JAR).
tasks.register<Jar>("fatJar") {
    isZip64 = true
    archiveBaseName.set("shapes-experiment")
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes("Main-Class" to "shapes.MainKt") }
    from({ configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } })
    from(sourceSets.main.get().output)
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/MANIFEST.MF")
    exclude("META-INF/versions/**/module-info.class", "module-info.class")
}

// `-Dwl.jfr=/path.jfr` records the test JVM with JFR (profile settings).
System.getProperty("wl.jfr")?.let { recording ->
    tasks.withType<Test>().configureEach {
        jvmArgs("-XX:StartFlightRecording=filename=$recording,settings=profile")
    }
}
