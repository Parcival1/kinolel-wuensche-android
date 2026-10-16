import java.util.Properties

plugins {
    id("com.android.application")
}

// KinoLel Wünsche: Name, Server, Kennung, Version aus marke.properties (einzige Stelle)
val marke = Properties().apply { rootProject.file("marke.properties").reader(Charsets.UTF_8).use { load(it) } }
val appVersion: String = (System.getenv("APP_VERSION") ?: marke.getProperty("VERSION")).removePrefix("v")
fun versionCode(v: String): Int = v.split('.').map { it.substringBefore('-').toIntOrNull() ?: 0 }
    .let { it.getOrElse(0) { 0 } * 10000 + it.getOrElse(1) { 0 } * 100 + it.getOrElse(2) { 0 } }

android {
    namespace = "de.kinolel.wuensche"
    compileSdk = 36

    defaultConfig {
        applicationId = marke.getProperty("APPLICATION_ID")
        minSdk = 26
        targetSdk = 36
        versionName = appVersion
        versionCode = versionCode(appVersion)
        resValue("string", "app_name", marke.getProperty("APP_NAME"))
        buildConfigField("String", "APP_NAME", "\"${marke.getProperty("APP_NAME")}\"")
        buildConfigField("String", "SERVER_DE", "\"${marke.getProperty("SERVER_DE")}\"")
        buildConfigField("String", "SERVER_HU", "\"${marke.getProperty("SERVER_HU")}\"")
        buildConfigField("String", "UPDATE_REPO", "\"${marke.getProperty("UPDATE_REPO")}\"")
        manifestPlaceholders["hostDe"] = marke.getProperty("SERVER_DE").removePrefix("https://")
        manifestPlaceholders["hostHu"] = marke.getProperty("SERVER_HU").removePrefix("https://")
    }

    // Gleicher Signierschlüssel wie die KinoLel-App (CT 104 /claude/kinolel-android-signing/), Werte kommen als Umgebung
    signingConfigs {
        val datei = System.getenv("KEYSTORE_FILE")
        if (datei != null) {
            create("release") {
                storeFile = file(datei)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        buildConfig = true
        resValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

base.archivesName.set("kinolel-wuensche-v$appVersion")

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
