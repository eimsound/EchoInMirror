plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization") version "1.9.20"
    id("org.jetbrains.compose")
}

kotlin {
    val jvmVersion = extra["jvm.version"] as String
    jvmToolchain(jvmVersion.toInt())
    jvm {
        compilations.all {
            kotlinOptions.jvmTarget = jvmVersion
        }
    }
    sourceSets {
        named("commonMain") {
            dependencies {
                api(project(":commons"))
                api(project(":dsp"))
                api(project(":utils"))
                compileOnly(compose.runtime)
            }
        }
        named("commonTest") {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
        named("jvmMain") {
            dependencies {
                implementation("com.google.guava:guava:31.1-jre")
            }
        }
    }
}