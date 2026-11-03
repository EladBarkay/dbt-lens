import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
    id("com.ncorti.ktfmt.gradle") version "0.27.0"
}

group = "dev.dbt.intellij"

version = "1.0.0"

ktfmt { kotlinLangStyle() }

tasks.processResources { from(files("LICENSE", "NOTICE")) { into("META-INF") } }

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        val localIde = providers.gradleProperty("localPlatformPath")
        if (localIde.isPresent) local(localIde.get())
        else {
            val platformVersion = providers.gradleProperty("platformVersion").orElse("2025.3.3")
            create(providers.gradleProperty("platformType").orElse("IU"), platformVersion) {
                useInstaller = false
            }
        }
        val runtime = providers.gradleProperty("localRuntimePath")
        if (runtime.isPresent) jetbrainsRuntimeLocal(runtime.get()) else jetbrainsRuntime()
        bundledPlugin("com.intellij.modules.json")
        bundledPlugin("org.jetbrains.plugins.yaml")
        testFramework(TestFrameworkType.Platform)
        if (providers.gradleProperty("withTextmateTests").orNull != "false")
            testBundledPlugin("org.jetbrains.plugins.textmate")
        if (providers.gradleProperty("withSqlTests").orNull == "true")
            testBundledPlugin("com.intellij.database")
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// The platform plugin otherwise selects the SDK runtime's bytecode target (25 on PyCharm 262).
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        languageVersion = KotlinVersion.KOTLIN_2_2
        apiVersion = KotlinVersion.KOTLIN_2_2
    }
}

intellijPlatform {
    pluginConfiguration {
        name = "dbt Lens"
        ideaVersion { sinceBuild = "253" }
    }
    pluginVerification {
        ides {
            current()
            providers.gradleProperty("verificationIdePath").orNull?.let { local(file(it)) }
            if (providers.gradleProperty("verifySupportedIdes").orNull == "true")
                create("PY", "2026.2.3")
        }
    }
}

tasks.check { dependsOn(tasks.named("ktfmtCheck")) }

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.verifyPlugin {
    if (providers.gradleProperty("verifierOffline").orNull == "true") offline = true
}

tasks.runIde {
    if (providers.gradleProperty("experimentalSql").orNull == "true")
        jvmArgs("-Ddbt.jinja.experimental.sql=true")
}

tasks.prepareTestSandbox {
    if (providers.gradleProperty("withYamlTests").orNull == "false") {
        disabledPlugins.add("org.jetbrains.plugins.yaml")
        disabledPlugins.add("com.intellij.modules.json")
    }
    // Omitting a test dependency does not disable plugins bundled in the target IDE.
    if (providers.gradleProperty("withSqlTests").orNull != "true")
        disabledPlugins.add("com.intellij.database")
    if (providers.gradleProperty("withTextmateTests").orNull == "false")
        disabledPlugins.add("org.jetbrains.plugins.textmate")
    if (providers.gradleProperty("freeEditionTests").orNull == "true")
        disabledPlugins.add("com.intellij.modules.ultimate")
}

tasks.test {
    maxHeapSize = "2g"
    systemProperty("java.awt.headless", "true")
    systemProperty("dbt.jinja.test.sql", providers.gradleProperty("withSqlTests").orNull == "true")
    systemProperty(
        "dbt.jinja.test.textmate",
        providers.gradleProperty("withTextmateTests").orNull != "false",
    )
    if (providers.gradleProperty("withYamlTests").orNull == "false")
        exclude("**/DbtYamlIntegrationTest*", "**/DbtDispatchYamlTest*")
    if (providers.gradleProperty("withSqlTests").orNull != "true")
        exclude("**/DbtSqlIntegrationTest*")
    if (providers.gradleProperty("withTextmateTests").orNull == "false") {
        filter {
            excludeTestsMatching(
                "dev.dbt.intellij.DbtProjectEditorTest.testRealTextmateHostUsesJinjaEditor"
            )
            excludeTestsMatching(
                "dev.dbt.intellij.DbtProjectEditorTest.testOrdinarySqlUsesAutomaticDetectionWithoutFileTypeOverrides"
            )
        }
    }
}
