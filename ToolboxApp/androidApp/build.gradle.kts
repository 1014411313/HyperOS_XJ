plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 模块版本是唯一来源（dist/HyperOS_XJ_New/module.prop）：App 的 versionName/versionCode
// 跟随它，避免系统应用信息里显示脚手架默认 0.1.0 与模块列表对不上。
val moduleProp: Map<String, String> = rootProject.file("../dist/HyperOS_XJ_New/module.prop")
    .takeIf { it.isFile }
    ?.readLines()
    ?.mapNotNull { line ->
        val idx = line.indexOf('=')
        if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
    }
    ?.toMap()
    ?: emptyMap()

android {
    namespace = "com.hyperos.xj"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.hyperos.xj"
        minSdk = 23
        targetSdk = 37
        versionCode = moduleProp["versionCode"]?.toIntOrNull() ?: 1
        versionName = moduleProp["version"] ?: "0.0.0"
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
