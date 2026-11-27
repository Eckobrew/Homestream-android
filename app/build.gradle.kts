plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
val previewInstall = providers.gradleProperty("previewInstall").orNull == "true"
val customCastReceiverId = providers.gradleProperty("castReceiverAppId").orNull.orEmpty().uppercase().takeUnless { it == "CC1AD845" }.orEmpty()
require(customCastReceiverId.isEmpty() || customCastReceiverId.matches(Regex("[A-F0-9]{8}"))) { "castReceiverAppId must be an 8-character Cast application ID" }
android {
    buildFeatures { buildConfig = true }
    namespace = "com.homestream.app"
    compileSdk = 35
    defaultConfig {
        applicationId = if (previewInstall) "com.homestream.app.preview" else "com.homestream.app"
        manifestPlaceholders["appLabel"] = if (previewInstall) "HomeStream Preview" else "@string/app_name"
        minSdk = 26
        targetSdk = 35
        buildConfigField("String", "CAST_RECEIVER_APP_ID", "\"$customCastReceiverId\"")
        versionCode = 17
        versionName = "1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    val releaseStorePath = providers.gradleProperty("HOMESTREAM_KEYSTORE_PATH").orNull ?: System.getenv("HOMESTREAM_KEYSTORE_PATH")
    val releaseStorePassword = providers.gradleProperty("HOMESTREAM_KEYSTORE_PASSWORD").orNull ?: System.getenv("HOMESTREAM_KEYSTORE_PASSWORD")
    val releaseKeyAlias = providers.gradleProperty("HOMESTREAM_KEY_ALIAS").orNull ?: System.getenv("HOMESTREAM_KEY_ALIAS")
    val releaseKeyPassword = providers.gradleProperty("HOMESTREAM_KEY_PASSWORD").orNull ?: System.getenv("HOMESTREAM_KEY_PASSWORD")
    val hasReleaseSigning = !releaseStorePath.isNullOrBlank() && !releaseStorePassword.isNullOrBlank() && !releaseKeyAlias.isNullOrBlank() && !releaseKeyPassword.isNullOrBlank()
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.6.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
    implementation("androidx.media3:media3-session:1.6.1")
    implementation("com.google.android.gms:play-services-cast-framework:22.0.0")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil:2.7.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20240303")
}
