import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    // AGP 9.0+ provides built-in Kotlin; do not apply kotlin-android.
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ahif.lumen"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ahif.lumen"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            // Debug builds are much slower (no ART/R8 optimisation, baseline profiles ignored),
            // so this is what goes on the phone. Signed with the debug key so it installs
            // over the existing app and keeps its data.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // AGP 9 built-in Kotlin: configure the Kotlin compiler via android.kotlin { }
    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.coil.compose)
    implementation(libs.junrar)
    implementation(libs.androidx.exifinterface)
    implementation(libs.mlkit.subject.segmentation)
    implementation(libs.androidx.heifwriter)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
    // Test-only: creates real password-protected Office files to check OfficeCrypto against.
    testImplementation(libs.poi.ooxml)
    testImplementation(libs.pdfbox)
}
