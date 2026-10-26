import java.util.Properties

plugins {
    id("com.android.application")
}

// KinoLel Wünsche: Name, Server, Kennung, Version aus marke.properties (einzige Stelle)
val marke = Properties().apply { rootProject.file("marke.properties").reader(Charsets.UTF_8).use { load(it) } }
val appVersion: String = (System.getenv("APP_VERSION") ?: marke.getProperty("VERSION")).removePrefix("v")
// Firebase (Lel 26.10.: Push sofort): Werte aus app/google-services.json (im CI aus dem Secret GOOGLE_SERVICES_JSON,
// eingerichtet von Lel). Ohne Datei bleibt Push aus – die App prüft dann wie bisher alle 15 Minuten. Kein Gradle-Plugin.
val firebase = file("google-services.json").takeIf { it.exists() }?.let { groovy.json.JsonSlurper().parse(it) as Map<*, *> }
fun firebaseWert(paket: String, wert: String): String {
    val client = (firebase?.get("client") as? List<*>)?.map { it as Map<*, *> }?.firstOrNull {
        ((it["client_info"] as Map<*, *>)["android_client_info"] as Map<*, *>)["package_name"] == paket
    } ?: return ""
    return when (wert) {
        "app" -> (client["client_info"] as Map<*, *>)["mobilesdk_app_id"] as String
        else -> ((client["api_key"] as List<*>).first() as Map<*, *>)["current_key"] as String
    }
}
val firebaseProjekt = firebase?.get("project_info") as? Map<*, *>

fun versionCode(v: String): Int = v.split('.').map { it.substringBefore('-').toIntOrNull() ?: 0 }
    .let { it.getOrElse(0) { 0 } * 10000 + it.getOrElse(1) { 0 } * 100 + it.getOrElse(2) { 0 } }

android {
    namespace = "de.kinolel.wuensche"
    compileSdk = 37

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
        buildConfigField("String", "FB_PROJEKT", "\"${firebaseProjekt?.get("project_id") ?: ""}\"")
        buildConfigField("String", "FB_SENDER", "\"${firebaseProjekt?.get("project_number") ?: ""}\"")
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
            val paket = marke.getProperty("APPLICATION_ID")
            buildConfigField("String", "FB_APP_ID", "\"${firebaseWert(paket, "app")}\"")
            buildConfigField("String", "FB_API_KEY", "\"${firebaseWert(paket, "key")}\"")
        }
        getByName("debug") {
            applicationIdSuffix = ".debug"
            val paket = marke.getProperty("APPLICATION_ID") + ".debug"
            buildConfigField("String", "FB_APP_ID", "\"${firebaseWert(paket, "app")}\"")
            buildConfigField("String", "FB_API_KEY", "\"${firebaseWert(paket, "key")}\"")
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
    // Push (v1.4): nur Cloud Messaging, ohne Analytics
    implementation(platform("com.google.firebase:firebase-bom:33.13.0"))
    implementation("com.google.firebase:firebase-messaging")
}
