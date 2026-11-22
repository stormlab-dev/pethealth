import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    // Android app consumes the JVM target.
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    // iPhone app consumes the PetPulseCore.framework (build on a Mac: ./gradlew :core:assemblePetPulseCoreXCFramework).
    val xcf = XCFramework("PetPulseCore")
    listOf(iosArm64(), iosSimulatorArm64(), iosX64()).forEach { target ->
        target.binaries.framework {
            baseName = "PetPulseCore"
            isStatic = true
            export(libs.kotlinx.datetime)
            xcf.add(this)
        }
    }

    // Optional native target to check the shared code compiles off-JVM on Linux CI (-PlinuxCheck).
    if (providers.gradleProperty("linuxCheck").isPresent) linuxX64()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
