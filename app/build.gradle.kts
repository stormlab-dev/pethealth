plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.petpulse.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cutefamilypet.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.2.0"

        // Optional cloud and billing keys: set as gradle properties (~/.gradle/gradle.properties)
        // or environment variables. Left empty, the app runs offline with demo purchases.
        fun cfg(name: String) = (project.findProperty(name) as String?) ?: System.getenv(name) ?: ""
        buildConfigField("String", "SUPABASE_URL", "\"${cfg("CFP_SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${cfg("CFP_SUPABASE_ANON_KEY")}\"")
        buildConfigField("String", "REVENUECAT_ANDROID_KEY", "\"${cfg("CFP_REVENUECAT_ANDROID_KEY")}\"")

        // AdMob (free plan only). Defaults are Google's public test ids, which show "Test Ad"
        // banners and earn nothing; set your own for release builds.
        val admobApp = cfg("CFP_ADMOB_APP_ID").ifEmpty { "ca-app-pub-3940256099942544~3347511713" }
        val admobBanner = cfg("CFP_ADMOB_BANNER_ID").ifEmpty { "ca-app-pub-3940256099942544/9214589741" }
        manifestPlaceholders["admobAppId"] = admobApp
        buildConfigField("String", "ADMOB_BANNER_ID", "\"$admobBanner\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime.ktx)
    implementation(libs.revenuecat)
    implementation(libs.play.services.ads)
    implementation(libs.ump)

    testImplementation(libs.junit)
}
