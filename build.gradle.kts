import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    kotlin("jvm") version "2.3.10"
    `java-library`
    `maven-publish`
}

group = "com.github.waynehamadi"
version = "0.1.0"

// The tools compile against the oldest openai-kotlin they support and are tested on the one given by -Popenai.
val openaiMin = "3.7.0"
val openaiTest = providers.gradleProperty("openai").getOrElse("4.2.0")

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("com.aallam.openai:openai-core:$openaiMin")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    compileOnly(kotlin("stdlib"))

    testImplementation("com.aallam.openai:openai-client:$openaiTest")
    testImplementation("io.ktor:ktor-client-cio:" + if (openaiTest.startsWith("3.")) "2.3.12" else "3.0.0")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    withSourcesJar()
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        // openai-kotlin 4.2.0 is built with Kotlin 2.0, so an app on Kotlin 2.0 reads this jar too.
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
    }
}

tasks.test {
    useJUnitPlatform()
    environment("DATACIRCLE_LIVE", System.getenv("DATACIRCLE_LIVE") ?: "")
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "openai-kotlin-datacircle"
            from(components["java"])
            pom {
                name.set("Datacircle tools for openai-kotlin")
                description.set(
                    "Four function tools on Datacircle's open API for openai-kotlin's chat completions, with no key: " +
                        "every GTM API, who is best for each, plan search and a provider's ranks."
                )
                url.set("https://datacircle.dev")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                scm {
                    url.set("https://github.com/waynehamadi/openai-kotlin-datacircle")
                }
            }
        }
    }
}
