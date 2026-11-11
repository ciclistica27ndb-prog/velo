import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "it.velo.traduttore"
    compileSdk = 34

    defaultConfig {
        applicationId = "it.velo.traduttore"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"

        // Repository GitHub da cui l'app scarica gli aggiornamenti (es. "utente/velo")
        buildConfigField("String", "UPDATE_REPO", "\"${project.findProperty("veloUpdateRepo") ?: ""}\"")

        // Solo le architetture dei telefoni reali: APK molto più leggero
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        // Chiave fissa inclusa nel progetto: ogni build (anche da GitHub Actions)
        // è firmata allo stesso modo, così gli aggiornamenti si installano sopra
        // la versione precedente senza doverla disinstallare.
        getByName("debug") {
            storeFile = file("velo-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storeType = "pkcs12"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("com.google.android.material:material:1.12.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // Riconoscimento del testo sul dispositivo (modelli inclusi nell'APK, funziona offline)
    implementation("com.google.mlkit:text-recognition:16.0.0")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.0")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.0")
    implementation("com.google.mlkit:text-recognition-korean:16.0.0")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.0")

    // Riconoscimento della lingua e traduzione sul dispositivo
    implementation("com.google.mlkit:language-id:17.0.4")
    implementation("com.google.mlkit:translate:17.0.2")
}

// Prepara i file da pubblicare in una release GitHub: velo.apk + update.json
// (update.json è ciò che l'app legge per sapere se c'è una nuova versione).
val veloVersionCode = android.defaultConfig.versionCode ?: 0
val veloVersionName = android.defaultConfig.versionName ?: ""

tasks.register("veloRelease") {
    dependsOn("assembleDebug")
    doLast {
        val apk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
        val outDir = layout.buildDirectory.dir("velo-release").get().asFile
        outDir.mkdirs()
        val target = File(outDir, "velo.apk")
        apk.copyTo(target, overwrite = true)
        val sha = MessageDigest.getInstance("SHA-256")
            .digest(target.readBytes())
            .joinToString("") { "%02x".format(it) }
        val notesFile = rootProject.file("release-notes.txt")
        val notes = if (notesFile.exists()) notesFile.readText().trim() else ""
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "\\n")
        File(outDir, "update.json").writeText(
            "{\"versionCode\":$veloVersionCode,\"versionName\":\"${esc(veloVersionName)}\"," +
                "\"apk\":\"velo.apk\",\"sha256\":\"$sha\",\"notes\":\"${esc(notes)}\"}\n",
        )
        println("Velo $veloVersionName ($veloVersionCode) pronto in ${outDir.absolutePath}")
    }
}
