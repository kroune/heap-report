plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    application
}

repositories {
    mavenCentral()
}

val matPlugins = rootDir.toPath().parent.parent.resolve(".tools/mat/plugins").toFile()

dependencies {
    implementation("com.github.ajalt.clikt:clikt-jvm:5.1.0")
    implementation("com.dynatrace.hash4j:hash4j:0.31.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test"))
    // MAT library, for the heap-dump extractor only: compile against the MAT
    // jars, run with the whole plugins dir on the classpath (equinox deps of MAT).
    compileOnly(files(matPlugins.listFiles { f -> f.extension == "jar" && f.name.startsWith("org.eclipse.mat") }))
    runtimeOnly(fileTree(matPlugins) { include("*.jar") })
}

kotlin {
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

application {
    mainClass.set("shapes.MainKt")
    // Direct CLI runs stay bounded; the backend sizes each analysis explicitly.
    applicationDefaultJvmArgs = listOf("-Xmx2g")
}

// Fat jar for the heap-report backend integration (SHAPES_JAR): the MAT jars
// are signed (bouncycastle), so signatures must be stripped on repack.
tasks.register<Jar>("fatJar") {
    archiveBaseName.set("shapes-experiment")
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes("Main-Class" to "shapes.MainKt") }
    from({ configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } })
    from(sourceSets.main.get().output)
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/MANIFEST.MF")
    exclude("META-INF/versions/**/module-info.class", "module-info.class")
}
