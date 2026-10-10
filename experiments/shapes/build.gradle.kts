plugins {
    kotlin("jvm") version "2.4.10"
    application
}

repositories {
    mavenCentral()
}

val matPlugins = rootDir.toPath().parent.parent.resolve(".tools/mat/plugins").toFile()

dependencies {
    testImplementation(kotlin("test"))
    // MAT library, for the heap-dump extractor only: compile against the MAT
    // jars, run with the whole plugins dir on the classpath (equinox deps of MAT).
    compileOnly(files(matPlugins.listFiles { f -> f.extension == "jar" && f.name.startsWith("org.eclipse.mat") }))
    runtimeOnly(fileTree(matPlugins) { include("*.jar") })
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("shapes.MainKt")
    // experiment budget: both the MAT extractor and the analyzer run capped
    applicationDefaultJvmArgs = listOf("-Xmx2g")
}
