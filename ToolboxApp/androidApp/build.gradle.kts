plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.hyperos.xj"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.hyperos.xj"
        minSdk = 23
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        resources {
            excludes += arrayOf(
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
                "kotlin/**",
                "META-INF/*.version",
                "META-INF/**/LICENSE.txt"
            )
        }
    }

    lint {
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(project(":app"))
    compileOnly(project(":xposed-stubs"))
}
