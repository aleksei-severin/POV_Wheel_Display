plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.povwheel.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.povwheel.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    // exifinterface нужен: снимок с камеры приезжает с флагом поворота, и без
    // его учёта картинка легла бы на обод боком.
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Выброшены как неиспользуемые (сборка идёт без R8, так что мёртвый код
    // уезжает в APK целиком):
    //   material-icons-extended  — интерфейс рисует значки текстом («▶», «✕»),
    //                              а библиотека тянет несколько мегабайт;
    //   documentfile             — DocumentFile нигде не создаётся;
    //   lifecycle-viewmodel-compose — ViewModel берётся через by viewModels().
}
