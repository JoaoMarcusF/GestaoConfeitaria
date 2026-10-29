plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gestaoconfeitaria.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gestaoconfeitaria.app"
        minSdk = 26
        targetSdk = 35
        // No GitHub, cada build ganha um número maior, para o Android aceitar a atualização
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        versionCode = 100 + (build ?: 0)
        versionName = if (build != null) "2.$build" else "2.0-local"
    }
    // A interface web é a mesma do computador
    sourceSets["main"].assets.srcDir("../shared-assets")

    // Chave de assinatura: app/assinatura.jks + variável ASSINATURA_SENHA (no GitHub vêm dos secrets).
    // Sem o arquivo, usa a chave de testes do Android Studio.
    val chave = file("assinatura.jks")
    signingConfigs {
        if (chave.exists()) create("confeitaria") {
            storeFile = chave
            storePassword = System.getenv("ASSINATURA_SENHA")
            keyAlias = System.getenv("ASSINATURA_ALIAS") ?: "gestao"
            keyPassword = System.getenv("ASSINATURA_SENHA")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("confeitaria") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.1")
}
