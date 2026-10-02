import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.dbt.intellij"
version = "0.1.1-dev"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        val localIde = providers.gradleProperty("localPlatformPath")
        if (localIde.isPresent) local(localIde.get())
        else intellijIdea("2025.3.3") { useInstaller = false }
        val runtime = providers.gradleProperty("localRuntimePath")
        if (runtime.isPresent) jetbrainsRuntimeLocal(runtime.get())
        testFramework(TestFrameworkType.Platform)
        if (providers.gradleProperty("withSqlTests").orNull == "true") testBundledPlugin("com.intellij.database")
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_21 } }
java { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }

intellijPlatform {
    pluginConfiguration {
        name = "dbt Jinja"
        ideaVersion { sinceBuild = "253"; untilBuild = "262.*" }
    }
    pluginVerification {
        ides {
            current()
            providers.gradleProperty("verificationIdePath").orNull?.let { local(file(it)) }
        }
        if (providers.gradleProperty("verifierOffline").orNull == "true") freeArgs = listOf("-offline")
    }
}

tasks.runIde {
    if (providers.gradleProperty("experimentalSql").orNull == "true") jvmArgs("-Ddbt.jinja.experimental.sql=true")
}

tasks.test {
    maxHeapSize = "2g"
    systemProperty("java.awt.headless", "true")
    systemProperty("dbt.jinja.test.sql", providers.gradleProperty("withSqlTests").orNull == "true")
    if (providers.gradleProperty("withSqlTests").orNull != "true") exclude("**/DbtSqlIntegrationTest*")
}
