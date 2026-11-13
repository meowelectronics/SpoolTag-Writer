plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val releaseStore = providers.environmentVariable("SPOOLTAG_KEYSTORE")
val releaseStorePassword = providers.environmentVariable("SPOOLTAG_STORE_PASSWORD")
val releaseKeyAlias = providers.environmentVariable("SPOOLTAG_KEY_ALIAS")
val releaseKeyPassword = providers.environmentVariable("SPOOLTAG_KEY_PASSWORD")
val releaseSigningReady = listOf(releaseStore, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
    .all { it.isPresent && it.get().isNotBlank() }

android {
    namespace = "io.spooltag.writer"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.spooltag.writer"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "1.0.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    signingConfigs {
        create("release") {
            if (releaseSigningReady) {
                storeFile = file(releaseStore.get())
                storePassword = releaseStorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
            }
        }
    }
    buildTypes {
        getByName("release") {
            if (releaseSigningReady) signingConfig = signingConfigs.getByName("release")
        }
    }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/licenseAssets"))
}

val prepareLicenseAssets by tasks.registering(Sync::class) {
    from(rootProject.file("LICENSE"), rootProject.file("NOTICE"))
    into(layout.buildDirectory.dir("generated/licenseAssets/licenses"))
}
tasks.named("preBuild") { dependsOn(prepareLicenseAssets) }

tasks.matching { it.name == "assembleRelease" }.configureEach {
    doFirst {
        check(releaseSigningReady) {
            "Release signing requires SPOOLTAG_KEYSTORE, SPOOLTAG_STORE_PASSWORD, SPOOLTAG_KEY_ALIAS and SPOOLTAG_KEY_PASSWORD."
        }
    }
}

tasks.register<Copy>("exportReleaseApk") {
    dependsOn("assembleRelease")
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(layout.buildDirectory.dir("outputs/installable"))
    rename { "SpoolTag-Writer-${android.defaultConfig.versionName}-release.apk" }
}

tasks.register<Copy>("exportDebugApk") {
    dependsOn("assembleDebug")
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(layout.buildDirectory.dir("outputs/installable"))
    rename { "SpoolTag-Writer-${android.defaultConfig.versionName}.apk" }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
