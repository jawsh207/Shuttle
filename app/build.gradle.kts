plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.shuttle.assistant"
    // Material 3 Expressive (1.5) requires compileSdk 37 and AGP 9.1+.
    compileSdk = 37

    defaultConfig {
        applicationId = "app.shuttle.assistant"
        minSdk = 29 // RoleManager.ROLE_ASSISTANT and Settings.Panel
        targetSdk = 36
        // CI passes the run number / tag so every build installs over the previous one.
        versionCode = System.getenv("SHUTTLE_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("SHUTTLE_VERSION_NAME") ?: "0.1.0"

        // The on-device model (llama.cpp) is native code. Only 64-bit ARM is built: every phone
        // GrapheneOS supports is arm64, and each extra ABI would add several MB.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_static")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    // Debug builds use a throwaway test key kept in the repo (app/debug.keystore, the standard
    // debug passwords), so every CI debug build installs as an update over the last one. It is
    // only for testing: releases are never signed with it.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    // Release signing comes from the environment (GitHub secrets in CI). Every build CI ships
    // is an optimized release build signed with this key, so each one installs as an update.
    val keystorePath = System.getenv("SHUTTLE_KEYSTORE_FILE")
    if (keystorePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SHUTTLE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SHUTTLE_KEY_ALIAS")
                keyPassword = System.getenv("SHUTTLE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // Installs next to a release build, as "Shuttle Debug" (src/debug/res).
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            // R8: shrink, optimize and obfuscate; drop unused resources.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
        // The test build CI publishes when there's no release key: the release build (R8,
        // not debuggable, so it opens and scrolls at real speed; a debuggable Compose app is
        // several times slower) signed with the throwaway test key, installing as "Shuttle
        // Debug" over the debug build without losing its model or chats.
        create("preview") {
            initWith(getByName("release"))
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
    sourceSets.getByName("preview").res.srcDirs("src/debug/res")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Unit tests run on the plain JVM: Android framework calls return defaults instead of
    // throwing, so the parser and schema code can be tested without a phone.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES",
                "META-INF/{AL2.0,LGPL2.1}",
            )
        }
    }
}

// AGP 9 compiles Kotlin itself ("built-in Kotlin"); JVM target follows compileOptions.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
