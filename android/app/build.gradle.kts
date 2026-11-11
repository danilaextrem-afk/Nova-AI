plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ai.nova.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.nova.app"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        minSdk = 26
        targetSdk = 36
        // CI run number keeps every published build installable as an update over the previous one.
        versionCode = 100 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0)
        versionName = "0.2.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Non-secret HTTPS endpoint: CI variable first, then the committed public URL of the deployed backend.
    val committedUrl = rootProject.file("nova-api-url.txt").takeIf { it.exists() }?.readText()?.trim().orEmpty()
    val configuredUrl = (System.getenv("NOVA_API_BASE_URL")?.takeIf { it.isNotBlank() } ?: committedUrl).trim()
    val safeUrl = if (configuredUrl.matches(Regex("^https://[A-Za-z0-9.-]+/?$"))) configuredUrl else ""
    defaultConfig {
        buildConfigField("String", "NOVA_API_BASE_URL", "\"$safeUrl\"")
        buildConfigField("boolean", "MONETIZATION_ENABLED", if(System.getenv("NOVA_MONETIZATION_ENABLED")=="true") "true" else "false")
        manifestPlaceholders["admobAppId"] = System.getenv("ADMOB_APP_ID") ?: "ca-app-pub-3940256099942544~3347511713"
    }
    // TEST signing key (committed on purpose, password is public): gives every published preview APK the
    // same signature so it installs as an update. It is NOT a production key and must never be used as a
    // Google Play upload key. Production builds override it with the KEYSTORE_* repository secrets.
    signingConfigs {
        create("novaTest") {
            storeFile = rootProject.file("keystore/nova-test.jks")
            storePassword = "nova-test-only"; keyAlias = "nova-test"; keyPassword = "nova-test-only"
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("novaTest")
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    implementation("com.android.billingclient:billing-ktx:8.0.0")
    implementation("com.google.android.gms:play-services-ads:24.3.0")
    implementation("com.google.android.ump:user-messaging-platform:3.2.0")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
