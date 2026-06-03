import org.jetbrains.intellij.platform.gradle.TestFrameworkType

fun properties(key: String) = providers.gradleProperty(key)

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = properties("pluginGroup").get()
version = properties("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        val localIdePath = providers.gradleProperty("localIdePath")
            .orElse(providers.environmentVariable("LOCAL_IDE_PATH"))
        if (localIdePath.isPresent) {
            local(localIdePath)
        } else {
            create(properties("platformType").get(), properties("platformVersion").get())
        }

        // Bundled plugins we depend on at runtime. If a name fails to resolve in your IDE
        // edition (e.g. JSON plugin id changes), comment the offending line and remove the
        // matching <depends> from plugin.xml.
        bundledPlugin("com.intellij.modules.json")
        bundledPlugin("org.jetbrains.plugins.yaml")
        bundledPlugin("org.jetbrains.plugins.terminal")

        pluginVerifier()
        testFramework(TestFrameworkType.Platform)
    }
}

kotlin {
    jvmToolchain(properties("javaVersion").get().toInt())
}

intellijPlatform {
    pluginConfiguration {
        name = properties("pluginName")
        version = properties("pluginVersion")
        ideaVersion {
            sinceBuild = properties("pluginSinceBuild")
            // untilBuild deliberately omitted: the IntelliJ Platform Gradle Plugin will
            // auto-detect a sensible upper bound from `platformVersion`.
        }
    }

    signing {
        certificateChain.set(providers.environmentVariable("CERTIFICATE_CHAIN"))
        privateKey.set(providers.environmentVariable("PRIVATE_KEY"))
        password.set(providers.environmentVariable("PRIVATE_KEY_PASSWORD"))
    }

    publishing {
        token.set(providers.environmentVariable("PUBLISH_TOKEN"))
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = properties("javaVersion").get()
        targetCompatibility = properties("javaVersion").get()
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }

    wrapper {
        gradleVersion = "9.2.1"
        distributionType = Wrapper.DistributionType.BIN
    }

    val intellijTest = named<Test>("test")
    register<Test>("dockerIntegrationTest") {
        group = "verification"
        description = "Runs optional Docker-backed managed Canton sandbox integration checks."
        testClassesDirs = intellijTest.get().testClassesDirs
        classpath = intellijTest.get().classpath
        include("**/SandboxDockerIntegrationTest.class")
        shouldRunAfter(intellijTest)
        onlyIf {
            providers.gradleProperty("runDockerIntegration").orNull == "true" ||
                System.getenv("RUN_DOCKER_INTEGRATION") == "true"
        }
        systemProperty("runDockerIntegration", "true")
        systemProperty("cantonDockerImage", System.getenv("CANTON_DOCKER_IMAGE") ?: "canton-jetbrains-dev:latest")
    }
}
