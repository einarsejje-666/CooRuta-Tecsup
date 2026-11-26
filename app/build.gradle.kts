import java.util.Properties

// Las claves (Google Maps) viven en local.properties, que no se sube al repositorio.
val propiedadesLocales = Properties().apply {
    val archivo = rootProject.file("local.properties")
    if (archivo.exists()) archivo.inputStream().use { load(it) }
}
val claveGoogleMaps: String = propiedadesLocales.getProperty("GOOGLE_MAPS_KEY") ?: ""
// Clave vinculada a la cuenta de servicio cooruta-voz: solo Gemini TTS (Vertex AI).
val claveVoz: String = propiedadesLocales.getProperty("GEMINI_VOZ_KEY") ?: ""
// La clave de subida se guarda fuera del código y se lee solo en builds release.
val archivoFirma = propiedadesLocales.getProperty("RELEASE_STORE_FILE")
val contrasenaFirma = propiedadesLocales.getProperty("RELEASE_STORE_PASSWORD")
val aliasFirma = propiedadesLocales.getProperty("RELEASE_KEY_ALIAS")
val contrasenaClave = propiedadesLocales.getProperty("RELEASE_KEY_PASSWORD")

// Solo debug y solo si se activa en local.properties: Auth y Firestore contra los emuladores locales.
val usarEmuladores: Boolean = propiedadesLocales.getProperty("cooruta.emulador")?.trim() == "true"
val hostEmuladores: String = propiedadesLocales.getProperty("cooruta.emulador.host")?.trim() ?: "10.0.2.2"

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
    alias(libs.plugins.ksp)
}

android {
    namespace = "pe.cooruta.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "pe.cooruta.app"
        minSdk = 29
        targetSdk = 37
        versionCode = 6
        versionName = "1.4.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Si todavia no hay token, la app dibuja un mapa de maqueta en vez del real.
        buildConfigField("boolean", "HAY_MAPA", claveGoogleMaps.isNotBlank().toString())
        // Google Maps Platform (Places, Geocoding, Routes y el SDK de mapas).
        buildConfigField("String", "GOOGLE_MAPS_KEY", "\"$claveGoogleMaps\"")
        manifestPlaceholders["GOOGLE_MAPS_KEY"] = claveGoogleMaps
        buildConfigField("String", "GEMINI_VOZ_KEY", "\"$claveVoz\"")
        // Release siempre sin emuladores.
        buildConfigField("boolean", "EMULADOR", "false")
        buildConfigField("String", "EMULADOR_HOST", "\"\"")
        // Versión para Play Store (gradlew bundleRelease -Pplay): sin actualizador por APK.
        buildConfigField("boolean", "TIENDA_PLAY", project.hasProperty("play").toString())
    }
    if (project.hasProperty("play")) {
        sourceSets.getByName("release").manifest.srcFile("src/play/AndroidManifest.xml")
    }

    signingConfigs {
        create("release") {
            if (!archivoFirma.isNullOrBlank() && !contrasenaFirma.isNullOrBlank() &&
                !aliasFirma.isNullOrBlank() && !contrasenaClave.isNullOrBlank()) {
                storeFile = rootProject.file(archivoFirma)
                storePassword = contrasenaFirma
                keyAlias = aliasFirma
                keyPassword = contrasenaClave
            }
        }
    }
    buildTypes {
        debug {
            buildConfigField("boolean", "EMULADOR", usarEmuladores.toString())
            buildConfigField("String", "EMULADOR_HOST", "\"$hostEmuladores\"")
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // Navigation SDK lo exige.
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }
}

// Navigation SDK ya trae el SDK de mapas: no se puede incluir play-services-maps además.
configurations.all {
    exclude(group = "com.google.android.gms", module = "play-services-maps")
}

dependencies {
    implementation(libs.navigation.sdk)
    coreLibraryDesugaring(libs.desugar.nio)
    implementation(platform(libs.androidx.compose.bom))
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.functions)
    implementation(libs.firebase.ai)
    implementation(libs.firebase.appcheck.playintegrity)
    debugImplementation(libs.firebase.appcheck.debug)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.play.services.location)
    implementation(libs.maps.compose)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}