import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}
android {
    namespace = "com.chesstutor.nativeapp"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.chesstutor.nativeapp"
        minSdk = 31
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"
        val revision = MessageDigest.getInstance("SHA-256")
            .digest(rootProject.file("../upstream-manifest.json").readBytes())
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        buildConfigField("String", "ANALYSIS_REVISION", "\"$revision\"")
        ndk { abiFilters += providers.gradleProperty("chessTutorAbi").getOrElse("arm64-v8a") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { jniLibs.useLegacyPackaging = true }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Preview APK is installable. Store distribution needs the owner's release key.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    lint { abortOnError = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
chaquopy {
    defaultConfig {
        version = "3.12"
        (System.getenv("CHESSTUTOR_BUILD_PYTHON")
            ?: rootProject.file("../.venv/Scripts/python.exe").takeIf { it.isFile }?.absolutePath)
            ?.let { buildPython(it) }
        pip {
            install(file("wheels/chess-1.11.2-py3-none-any.whl").absolutePath)
            install("httpx==0.28.1")
        }
        extractPackages("backend")
    }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.3")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation(files("libs/chesslib-1.3.7.jar"))
    implementation("org.apache.commons:commons-lang3:3.18.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.09.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
