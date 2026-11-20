import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}
android {
    namespace = "es.luchis.calendario"
    compileSdk = 35
    defaultConfig { applicationId = "es.luchis.calendario"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "1.0" }
    buildFeatures { compose = true }
    sourceSets.getByName("test").java.srcDir("../tools")
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    val signingFile = rootProject.file(".signing/signing.properties")
    if (signingFile.exists()) {
        val values = Properties().apply { signingFile.inputStream().use { load(it) } }
        signingConfigs.create("personal") {
            storeFile = rootProject.file(".signing/lucia.jks")
            storePassword = values.getProperty("password")
            keyAlias = "lucia"
            keyPassword = values.getProperty("password")
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("personal")
    }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.room:room-runtime:2.7.1")
    kapt("androidx.room:room-compiler:2.7.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("junit:junit:4.13.2")
}
