import org.gradle.api.tasks.Exec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

android {
    namespace = "id.vern.antigravity"
    compileSdk = 36

    defaultConfig {
        applicationId = "id.vern.antigravity"
        applicationIdSuffix = ".turbo"
        minSdk = 26
        // Must remain 28: Android 10+ (targetSdk >= 29) enforces SELinux execute_no_trans W^X,
        // blocking execve() on agy/node/python3 binaries inside app filesDir.
        targetSdk = 28
        versionCode = 10
        versionName = "1.1.1-turbo"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("int", "GATEWAY_PORT", "45161")
        buildConfigField("int", "CORE_PORT", "45158")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        noCompress += listOf("zip", "gz", "tar")
    }

    val localProperties = Properties().apply {
        val propFile = rootProject.file("local.properties")
        if (propFile.exists()) {
            propFile.inputStream().use { load(it) }
        }
    }

    val keystorePath = localProperties.getProperty("signing.storeFile")
        ?: (project.findProperty("storeFile") as String?)
        ?: "KuatoDev.jks"
    val resolvedKeystore = file(keystorePath)

    signingConfigs {
        create("release") {
            if (resolvedKeystore.exists()) {
                storeFile = resolvedKeystore
                storePassword = localProperties.getProperty("signing.storePassword")
                    ?: (project.findProperty("storePassword") as String?)
                    ?: System.getenv("SIGNING_STORE_PASSWORD")
                    ?: ""
                keyAlias = localProperties.getProperty("signing.keyAlias")
                    ?: (project.findProperty("alias") as String?)
                    ?: System.getenv("SIGNING_KEY_ALIAS")
                    ?: ""
                keyPassword = localProperties.getProperty("signing.keyPassword")
                    ?: (project.findProperty("keyPassword") as String?)
                    ?: System.getenv("SIGNING_KEY_PASSWORD")
                    ?: ""
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null && releaseSigning.storeFile!!.exists()) {
                signingConfig = releaseSigning
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null && releaseSigning.storeFile!!.exists()) {
                signingConfig = releaseSigning
            }
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.google.material)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.coil.compose)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.serialization.json)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

val isWindowsHost = System.getProperty("os.name").orEmpty().lowercase(Locale.US).contains("win")
val testRustNative = tasks.register<Exec>("testRustNative") {
    description = "Runs cargo unit tests for rust_turbo when cargo is available on PATH"
    workingDir = rootProject.file("rust_turbo")
    if (isWindowsHost) {
        commandLine("cmd", "/c", "where cargo >nul 2>nul && cargo test || ver >nul")
    } else {
        commandLine("sh", "-c", "if command -v cargo >/dev/null 2>&1; then cargo test; else echo 'Skipping cargo test (cargo not found on PATH)'; fi")
    }
}

tasks.matching { it.name == "testDebugUnitTest" }.configureEach {
    dependsOn(testRustNative)
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    dependsOn("testDebugUnitTest")
}

